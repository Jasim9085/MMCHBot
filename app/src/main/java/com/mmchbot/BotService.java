package com.mmchbot;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public class BotService extends Service {

    private boolean isRunning = false;
    private OkHttpClient client;
    private long lastUpdateId = 0;
    private PowerManager.WakeLock wakeLock;

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

        // Acquire WakeLock to prevent CPU sleeping
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMCHBot::Wakelock");
        wakeLock.acquire(10*60*1000L /*10 mins*/);

        if (TOKEN.isEmpty()) {
            showToast("❌ Token missing! Stop and Configure.");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            isRunning = true;
            // Configured with timeouts
            client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
            
            new Thread(this::runBotLoop).start();
            showToast("✅ Bot Service Started");
        }

        return START_STICKY;
    }

    // --- MAIN LOOP WITH RETRY LOGIC ---
    private void runBotLoop() {
        int retryCount = 0;
        
        while (isRunning) {
            try {
                Request request = new Request.Builder()
                    .url("https://api.telegram.org/bot" + TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=10")
                    .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String json = response.body().string();
                        handleUpdates(json);
                        retryCount = 0; // Reset retry on success
                    } else {
                        throw new IOException("Server Error: " + response.code());
                    }
                }
            } catch (Exception e) {
                retryCount++;
                Log.e("BotService", "Network Error: " + e.getMessage());
                
                if (retryCount <= 10) {
                    // Retry 10 times with 2s interval
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                } else {
                    // After 10 fails, wait longer (15s) to save battery
                    try { Thread.sleep(15000); } catch (InterruptedException ignored) {}
                }
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
        } catch (Exception e) {
            e.printStackTrace();
            showToast("❌ Update Error: " + e.getMessage());
        }
    }

    private void handleMessage(JsonObject message) {
        long chatId = message.get("chat").getAsJsonObject().get("id").getAsLong();
        userState.putIfAbsent(chatId, 0);
        userData.putIfAbsent(chatId, new HashMap<>());

        String text = message.has("text") ? message.get("text").getAsString() : null;

        if (text != null && text.equals("/start")) {
            userState.put(chatId, 0);
            sendMessage(chatId, "🎬 **Movie Helper**\n\nSend me the **Thumbnail Image**.");
            return;
        }

        int state = userState.get(chatId);

        // --- STATE 0: PHOTO HANDLING ---
        if (state == 0) {
            String fileId = null;
            
            // 1. Check Compressed Photo
            if (message.has("photo")) {
                JsonArray photos = message.getAsJsonArray("photo");
                fileId = photos.get(photos.size() - 1).getAsJsonObject().get("file_id").getAsString();
            } 
            // 2. Check Document (File)
            else if (message.has("document")) {
                JsonObject doc = message.getAsJsonObject("document");
                String mime = doc.has("mime_type") ? doc.get("mime_type").getAsString() : "";
                if (mime.startsWith("image")) {
                    fileId = doc.get("file_id").getAsString();
                } else {
                    sendMessage(chatId, "❌ **Error:** That is a " + mime + " file.\n\nPlease send an **Image** (JPG/PNG).");
                    return;
                }
            }
            // 3. Reject anything else (Text, Video, Audio)
            else {
                sendMessage(chatId, "❌ **Error:** I didn't see an image.\n\nPlease send a Photo or a File (Image).");
                return;
            }

            if (fileId != null) {
                userData.get(chatId).put("photo", fileId);
                userState.put(chatId, 1);
                sendMessage(chatId, "✅ Thumbnail Received.\n\nNow, send the **Movie Name**.");
            }
        } 
        // --- STATE 1: NAME ---
        else if (state == 1) {
            if (text != null) {
                userData.get(chatId).put("name", text);
                userState.put(chatId, 2);
                sendMessage(chatId, "✅ Movie: " + text + "\n\nNow, send the **Download Link**.");
            } else {
                sendMessage(chatId, "❌ **Error:** Please send the Movie Name as text.");
            }
        }
        // --- STATE 2: LINK ---
        else if (state == 2) {
            if (text != null) {
                userData.get(chatId).put("link", text);
                sendMessage(chatId, "🤖 Asking Gemini to write description...\n_(This may take a few seconds)_");
                new Thread(() -> generateGemini(chatId, userData.get(chatId).get("name"))).start();
            } else {
                sendMessage(chatId, "❌ **Error:** Please send the Link as text.");
            }
        }
        // --- STATE 3: SCHEDULE ---
        else if (state == 3 && text != null) {
             try {
                 int minutes = Integer.parseInt(text);
                 schedulePost(chatId, minutes);
                 userState.put(chatId, 0); 
             } catch (NumberFormatException e) {
                 sendMessage(chatId, "⚠️ **Error:** '" + text + "' is not a valid number.\n\nPlease enter the number of minutes (e.g. 60).");
             }
        }
    }

    private void handleCallback(JsonObject callback) {
        long chatId = callback.get("message").getAsJsonObject().get("chat").getAsJsonObject().get("id").getAsLong();
        String data = callback.get("data").getAsString();
        String callbackId = callback.get("id").getAsString();

        answerCallback(callbackId);

        if (data.equals("post_now")) {
            postToChannel(chatId, userData.get(chatId));
        } else if (data.equals("schedule")) {
             userState.put(chatId, 3);
             sendMessage(chatId, "⏳ **Schedule Mode**\n\nEnter delay in minutes:");
        }
    }

    // --- NETWORKING ---

    private void sendMessage(long chatId, String text) {
        // Robust send with internal retry
        new Thread(() -> {
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("text", text)
                .addFormDataPart("parse_mode", "Markdown");
            
            if (!executeTelegramWithRetry("sendMessage", builder.build())) {
                showToast("Failed to send msg to " + chatId);
            }
        }).start();
    }

    private void postToChannel(long adminId, Map<String, String> data) {
        new Thread(() -> {
            String keyboard = "{\"inline_keyboard\":[[{\"text\":\"📥 Download Movie 📥\",\"url\":\"" + data.get("link") + "\"}]]}";
            
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", CHANNEL)
                .addFormDataPart("photo", data.get("photo"))
                .addFormDataPart("caption", data.get("desc"))
                .addFormDataPart("parse_mode", "HTML")
                .addFormDataPart("reply_markup", keyboard);

            if (executeTelegramWithRetry("sendPhoto", builder.build())) {
                sendMessage(adminId, "✅ **Success!** Posted to channel.");
                userState.put(adminId, 0);
            } else {
                sendMessage(adminId, "❌ **Post Failed!**\n\n1. Check Channel ID.\n2. Check if Bot is Admin.\n3. Retry.");
            }
        }).start();
    }

    private void sendPreview(long chatId, String photoId, String caption) {
        new Thread(() -> {
            String keyboard = "{\"inline_keyboard\":[[{\"text\":\"🚀 Post Now\",\"callback_data\":\"post_now\"}], [{\"text\":\"⏰ Schedule\",\"callback_data\":\"schedule\"}]]}";

            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("photo", photoId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("parse_mode", "HTML")
                .addFormDataPart("reply_markup", keyboard);
            
            if (!executeTelegramWithRetry("sendPhoto", builder.build())) {
                sendMessage(chatId, "❌ **Error:** Failed to load preview.");
            }
        }).start();
    }

    private void answerCallback(String callbackId) {
         new Thread(() -> {
             MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("callback_query_id", callbackId);
             executeTelegramWithRetry("answerCallbackQuery", builder.build());
         }).start();
    }

    // ⚡ RETRY LOGIC FOR SENDING ⚡
    private boolean executeTelegramWithRetry(String method, RequestBody body) {
        int attempts = 0;
        while (attempts < 10) {
            try {
                Request request = new Request.Builder()
                    .url("https://api.telegram.org/bot" + TOKEN + "/" + method)
                    .post(body)
                    .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) return true;
                    Log.e("BotService", "API Error " + response.code() + ": " + response.message());
                }
            } catch (Exception e) {
                Log.e("BotService", "Send Fail: " + e.getMessage());
            }
            attempts++;
            try { Thread.sleep(2000); } catch (Exception ignored) {}
        }
        return false;
    }

    // --- GEMINI LOGIC ---
    
    private void generateGemini(long chatId, String movieName) {
        int attempts = 0;
        boolean success = false;
        
        while (attempts < 3 && !success) { // Retry Gemini 3 times
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
                    if (!res.isSuccessful()) {
                        throw new IOException("API " + res.code());
                    }
                    String resStr = res.body().string();
                    String result = new JSONObject(resStr).getJSONArray("candidates")
                        .getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text");
                    
                    userData.get(chatId).put("desc", result);
                    sendPreview(chatId, userData.get(chatId).get("photo"), result);
                    success = true;
                }
            } catch (Exception e) {
                attempts++;
                if (attempts >= 3) {
                    sendMessage(chatId, "⚠️ **Gemini Error:** " + e.getMessage() + "\n\nPlease check API Key or Internet.");
                }
                try { Thread.sleep(2000); } catch (Exception ignored) {}
            }
        }
    }

    // --- SYSTEM UTILS ---

    private void schedulePost(long chatId, int minutes) {
        Map<String, String> data = userData.get(chatId);
        File file = new File(getFilesDir(), "pending_post_" + System.currentTimeMillis() + ".json");
        try (Writer writer = new FileWriter(file)) {
            new Gson().toJson(data, writer);
            
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, AlarmReceiver.class);
            intent.putExtra("filePath", file.getAbsolutePath());
            
            PendingIntent pi = PendingIntent.getBroadcast(this, (int)System.currentTimeMillis(), intent, PendingIntent.FLAG_IMMUTABLE);
            long triggerTime = System.currentTimeMillis() + (minutes * 60 * 1000L);
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);

            sendMessage(chatId, "✅ **Scheduled!**\n\nI will wake up and post in " + minutes + " minutes.");
        } catch (IOException e) { 
            sendMessage(chatId, "❌ Storage Error: " + e.getMessage());
        }
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
        } catch (Exception e) { showToast("Config Error: " + e.getMessage()); }
    }

    private Notification createNotification() {
        String channelId = "bot_service";
        NotificationChannel channel = new NotificationChannel(channelId, "Bot Service", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("MMCH Bot Active")
                .setContentText("Listening for movie updates...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    private void showToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show()
        );
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
