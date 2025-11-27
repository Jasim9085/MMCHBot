package com.mmchbot;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class BotService extends Service {

    private boolean isRunning = false;
    private OkHttpClient client;
    private long lastUpdateId = 0;

    // States
    private final Map<Long, Integer> userState = new HashMap<>();
    private final Map<Long, Map<String, String>> userData = new HashMap<>();
    
    // Config
    private String TOKEN = "", USERNAME = "", CHANNEL = "", GEMINI_KEY = "", PROMPT_TEMPLATE = "", MODEL = "";
    private final String SETTINGS_FILE = "settings.json";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadConfig();
        startForeground(1, createNotification());

        if (TOKEN.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            isRunning = true;
            client = new OkHttpClient();
            new Thread(this::runBotLoop).start();
        }

        return START_STICKY;
    }

    private void runBotLoop() {
        while (isRunning) {
            try {
                // 1. Get Updates from Telegram
                Request request = new Request.Builder()
                    .url("https://api.telegram.org/bot" + TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=10")
                    .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String json = response.body().string();
                        handleUpdates(json);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                // Wait a bit before retrying on error
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handleUpdates(String jsonResponse) {
        try {
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
            if (!root.get("ok").getAsBoolean()) return;

            JsonArray result = root.getAsJsonArray("result");
            for (int i = 0; i < result.size(); i++) {
                JsonObject update = result.get(i).getAsJsonObject();
                lastUpdateId = update.get("update_id").getAsLong();
                
                if (update.has("message")) {
                    handleMessage(update.getAsJsonObject("message"));
                } else if (update.has("callback_query")) {
                    handleCallback(update.getAsJsonObject("callback_query"));
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void handleMessage(JsonObject message) {
        long chatId = message.get("chat").getAsJsonObject().get("id").getAsLong();
        userState.putIfAbsent(chatId, 0);
        userData.putIfAbsent(chatId, new HashMap<>());

        String text = message.has("text") ? message.get("text").getAsString() : null;

        if (text != null && text.equals("/start")) {
            userState.put(chatId, 0);
            sendMessage(chatId, "🎬 **Movie Helper**\nSend Thumbnail.");
            return;
        }

        int state = userState.get(chatId);

        if (state == 0 && message.has("photo")) {
            JsonArray photos = message.getAsJsonArray("photo");
            String fileId = photos.get(photos.size() - 1).getAsJsonObject().get("file_id").getAsString();
            userData.get(chatId).put("photo", fileId);
            userState.put(chatId, 1);
            sendMessage(chatId, "✅ Send Movie Name.");
        } 
        else if (state == 1 && text != null) {
            userData.get(chatId).put("name", text);
            userState.put(chatId, 2);
            sendMessage(chatId, "✅ Send Download Link.");
        }
        else if (state == 2 && text != null) {
            userData.get(chatId).put("link", text);
            sendMessage(chatId, "🤖 Generating with " + MODEL + "...");
            new Thread(() -> generateGemini(chatId, userData.get(chatId).get("name"))).start();
        }
        else if (state == 3 && text != null) {
             try {
                 int minutes = Integer.parseInt(text);
                 schedulePost(chatId, minutes);
                 userState.put(chatId, 0); 
             } catch (Exception e) {
                 sendMessage(chatId, "⚠️ Invalid number. Try again.");
             }
        }
    }

    private void handleCallback(JsonObject callback) {
        long chatId = callback.get("message").getAsJsonObject().get("chat").getAsJsonObject().get("id").getAsLong();
        String data = callback.get("data").getAsString();
        String callbackId = callback.get("id").getAsString();

        // Answer callback to stop loading animation
        answerCallback(callbackId);

        if (data.equals("post_now")) {
            postToChannel(chatId, userData.get(chatId));
        } else if (data.equals("schedule")) {
             userState.put(chatId, 3);
             sendMessage(chatId, "⏳ Enter delay in minutes (e.g. 60):");
        }
    }

    // --- API CALLS USING OKHTTP ---

    private void sendMessage(long chatId, String text) {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", String.valueOf(chatId))
            .addFormDataPart("text", text)
            .addFormDataPart("parse_mode", "Markdown");
        
        executeTelegram("sendMessage", builder.build());
    }

    private void postToChannel(long adminId, Map<String, String> data) {
        String keyboard = "{\"inline_keyboard\":[[{\"text\":\"📥 Download Movie 📥\",\"url\":\"" + data.get("link") + "\"}]]}";
        
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", CHANNEL)
            .addFormDataPart("photo", data.get("photo"))
            .addFormDataPart("caption", data.get("desc"))
            .addFormDataPart("parse_mode", "HTML")
            .addFormDataPart("reply_markup", keyboard);

        if (executeTelegram("sendPhoto", builder.build())) {
            sendMessage(adminId, "✅ Posted successfully!");
        } else {
            sendMessage(adminId, "❌ Failed to post. Check Channel ID/Permissions.");
        }
    }

    private void sendPreview(long chatId, String photoId, String caption) {
        String keyboard = "{\"inline_keyboard\":[[{\"text\":\"🚀 Post Now\",\"callback_data\":\"post_now\"}], [{\"text\":\"⏰ Schedule\",\"callback_data\":\"schedule\"}]]}";

        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", String.valueOf(chatId))
            .addFormDataPart("photo", photoId)
            .addFormDataPart("caption", caption)
            .addFormDataPart("parse_mode", "HTML")
            .addFormDataPart("reply_markup", keyboard);
        
        executeTelegram("sendPhoto", builder.build());
    }

    private void answerCallback(String callbackId) {
         MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("callback_query_id", callbackId);
         executeTelegram("answerCallbackQuery", builder.build());
    }

    private boolean executeTelegram(String method, RequestBody body) {
        try {
            Request request = new Request.Builder()
                .url("https://api.telegram.org/bot" + TOKEN + "/" + method)
                .post(body)
                .build();
            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) { return false; }
    }

    // --- GEMINI LOGIC ---
    
    private void generateGemini(long chatId, String movieName) {
        try {
            String prompt = PROMPT_TEMPLATE.replace("{name}", movieName) + " for " + movieName;
            JSONObject json = new JSONObject();
            json.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));

            RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json"));
            Request req = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=" + GEMINI_KEY)
                .post(body)
                .build();

            try (Response res = client.newCall(req).execute()) {
                String resStr = res.body().string();
                String result = new JSONObject(resStr).getJSONArray("candidates")
                    .getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text");
                
                userData.get(chatId).put("desc", result);
                sendPreview(chatId, userData.get(chatId).get("photo"), result);
            }
        } catch (Exception e) {
            sendMessage(chatId, "⚠️ AI Error: " + e.getMessage());
        }
    }

    // --- SYSTEM UTILS ---

    private void schedulePost(long chatId, int minutes) {
        Map<String, String> data = userData.get(chatId);
        File file = new File(getFilesDir(), "pending_post_" + System.currentTimeMillis() + ".json");
        try (Writer writer = new FileWriter(file)) {
            new Gson().toJson(data, writer);
        } catch (IOException e) { e.printStackTrace(); }

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.putExtra("filePath", file.getAbsolutePath());
        
        PendingIntent pi = PendingIntent.getBroadcast(this, (int)System.currentTimeMillis(), intent, PendingIntent.FLAG_IMMUTABLE);
        long triggerTime = System.currentTimeMillis() + (minutes * 60 * 1000);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);

        sendMessage(chatId, "✅ Scheduled! I will wake up in " + minutes + " mins.");
    }

    private void loadConfig() {
        File file = new File(getFilesDir(), SETTINGS_FILE);
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> settings = new Gson().fromJson(reader, type);
            if (settings != null) {
                TOKEN = settings.getOrDefault("token", "");
                USERNAME = settings.getOrDefault("botName", "");
                CHANNEL = settings.getOrDefault("channel", "");
                GEMINI_KEY = settings.getOrDefault("gemini", "");
                PROMPT_TEMPLATE = settings.getOrDefault("prompt", "");
                MODEL = settings.getOrDefault("model", "gemini-1.5-flash");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private Notification createNotification() {
        String channelId = "bot_service";
        NotificationChannel channel = new NotificationChannel(channelId, "Bot Service", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("MMCH Bot Running")
                .setContentText("Listening for commands...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
