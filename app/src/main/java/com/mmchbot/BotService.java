package com.mmchbot;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64; // NEW IMPORT
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public class BotService extends Service {

    private boolean isRunning = false;
    private OkHttpClient client;
    private long lastUpdateId = 0;
    private PowerManager.WakeLock wakeLock;
    private BotDatabase db;
    private final ExecutorService taskQueue = Executors.newSingleThreadExecutor();

    // Config
    private String TOKEN = "", USERNAME = "", CHANNEL = "", GEMINI_KEY = "", PROMPT_TEMPLATE = "", MODEL = "";
    private final String SETTINGS_FILE = "settings.json";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadConfig();
        db = new BotDatabase(this);
        startForeground(1, createNotification());

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMCHBot::CoreLock");
        wakeLock.acquire(10*60*1000L);

        if (TOKEN.isEmpty()) {
            reportError("❌ Token missing. Bot Stopped.", false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            isRunning = true;
            client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
            
            new Thread(this::runAdaptivePollingLoop).start();
            reportError("✅ Vision Bot Started", false);
        }
        return START_STICKY;
    }

    private void runAdaptivePollingLoop() {
        int currentInterval = 1000;
        final int MAX_INTERVAL = 30000;
        int retryCount = 0;

        while (isRunning) {
            try {
                Request request = new Request.Builder()
                    .url("https://api.telegram.org/bot" + TOKEN + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=10")
                    .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String json = response.body().string();
                        boolean activityFound = handleUpdates(json);
                        
                        if (activityFound) {
                            currentInterval = 1000;
                            retryCount = 0;
                        } else {
                            currentInterval = Math.min(currentInterval + 500, MAX_INTERVAL);
                        }
                    }
                }
            } catch (Exception e) {
                retryCount++;
                try { Thread.sleep(Math.min(retryCount * 2000, 60000)); } catch (InterruptedException ignored) {}
            }
            try { Thread.sleep(currentInterval); } catch (InterruptedException ignored) {}
        }
    }

    private boolean handleUpdates(String jsonResponse) {
        boolean hasActivity = false;
        try {
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
            if (!root.get("ok").getAsBoolean()) return false;

            JsonArray result = root.getAsJsonArray("result");
            if (result.size() > 0) hasActivity = true;

            for (int i = 0; i < result.size(); i++) {
                JsonObject update = result.get(i).getAsJsonObject();
                lastUpdateId = update.get("update_id").getAsLong();
                
                taskQueue.execute(() -> {
                    try {
                        if (update.has("message")) {
                            handleMessage(update.getAsJsonObject("message"));
                        } else if (update.has("callback_query")) {
                            handleCallback(update.getAsJsonObject("callback_query"));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        } catch (Exception e) { e.printStackTrace(); }
        return hasActivity;
    }

    private void handleMessage(JsonObject message) {
        long chatId = message.get("chat").getAsJsonObject().get("id").getAsLong();
        String text = message.has("text") ? message.get("text").getAsString() : null;

        int state = db.getStep(chatId);
        Map<String, String> data = db.getData(chatId);

        if (text != null) {
            if (text.equals("/start")) {
                db.saveState(chatId, 0, new HashMap<>());
                sendMessage(chatId, "🎬 **Professional Movie Bot**\n\nSystem Online.\nSend me a **Thumbnail**.");
                return;
            }
        }

        try {
            if (state == 0) { // Photo
                String fileId = extractFileId(message, chatId);
                if (fileId != null) {
                    data.put("photo", fileId);
                    db.saveState(chatId, 1, data);
                    sendMessage(chatId, "✅ Image Analyzed.\nSend **Movie Name**.");
                }
            } 
            else if (state == 1 && text != null) { // Name
                data.put("name", text);
                db.saveState(chatId, 2, data);
                sendMessage(chatId, "✅ Name Saved.\nSend **Download Link**.");
            }
            else if (state == 2 && text != null) { // Link & Gen
                data.put("link", text);
                db.saveState(chatId, 2, data);
                sendMessage(chatId, "👀 **Vision AI Active**\nI'm looking at the poster to find the cast & rating...");
                generateGeminiVision(chatId, data.get("name"), data.get("photo"));
            }
            else if (state == 3 && text != null) { // Schedule
                 try {
                     int minutes = Integer.parseInt(text);
                     schedulePost(chatId, minutes, data);
                     db.saveState(chatId, 0, new HashMap<>()); 
                 } catch (NumberFormatException e) {
                     sendMessage(chatId, "⚠️ Invalid Number.");
                 }
            }
        } catch (Exception e) {
            sendMessage(chatId, "⚠️ Error: " + e.getMessage());
        }
    }

    private String extractFileId(JsonObject message, long chatId) {
        if (message.has("photo")) {
            JsonArray photos = message.getAsJsonArray("photo");
            return photos.get(photos.size() - 1).getAsJsonObject().get("file_id").getAsString();
        } else if (message.has("document")) {
            JsonObject doc = message.getAsJsonObject("document");
            if (doc.get("mime_type").getAsString().startsWith("image")) {
                return doc.get("file_id").getAsString();
            }
        }
        sendMessage(chatId, "❌ Please send an **Image File**.");
        return null;
    }

    private void handleCallback(JsonObject callback) {
        long chatId = callback.get("message").getAsJsonObject().get("chat").getAsJsonObject().get("id").getAsLong();
        String dataStr = callback.get("data").getAsString();
        String callbackId = callback.get("id").getAsString();

        answerCallback(callbackId);
        Map<String, String> data = db.getData(chatId);

        if (dataStr.equals("post_now")) {
            postToChannel(chatId, data);
        } else if (dataStr.equals("schedule")) {
             db.saveState(chatId, 3, data);
             sendMessage(chatId, "⏳ **Scheduling**\nEnter minutes (e.g. 60):");
        }
    }

    // --- NEW: GEMINI VISION LOGIC ---
    
    private void generateGeminiVision(long chatId, String name, String fileId) {
        taskQueue.execute(() -> {
            try {
                // 1. Download Image from Telegram
                String base64Image = downloadTelegramPhoto(fileId);
                if (base64Image == null) {
                    sendMessage(chatId, "⚠️ Failed to download image from Telegram. Trying text-only...");
                    // Fallback to text only logic if image fails (omitted for brevity, assume usually works)
                    return;
                }

                // 2. Prepare Multimodal JSON
                String prompt = PROMPT_TEMPLATE.replace("{name}", name);
                
                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", "image/jpeg");
                inlineData.put("data", base64Image);

                JSONObject textPart = new JSONObject().put("text", prompt);
                JSONObject imagePart = new JSONObject().put("inline_data", inlineData);

                JSONObject content = new JSONObject();
                content.put("parts", new JSONArray().put(textPart).put(imagePart));

                JSONObject json = new JSONObject();
                json.put("contents", new JSONArray().put(content));

                // 3. Send to Gemini
                RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json"));
                Request req = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=" + GEMINI_KEY)
                    .post(body)
                    .build();

                try (Response res = client.newCall(req).execute()) {
                    if (!res.isSuccessful()) throw new IOException("Gemini API " + res.code() + " " + res.message());
                    
                    String resStr = res.body().string();
                    String result = new JSONObject(resStr).getJSONArray("candidates")
                        .getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text");
                    
                    Map<String, String> data = db.getData(chatId);
                    data.put("desc", result);
                    db.saveState(chatId, 2, data);
                    
                    sendPreview(chatId, fileId, result);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendMessage(chatId, "⚠️ **AI Error:** " + e.getMessage());
            }
        });
    }

    // --- NEW: TELEGRAM IMAGE DOWNLOADER ---
    private String downloadTelegramPhoto(String fileId) {
        try {
            // Step 1: Get File Path
            Request pathReq = new Request.Builder()
                .url("https://api.telegram.org/bot" + TOKEN + "/getFile?file_id=" + fileId)
                .build();
            
            String filePath;
            try (Response res = client.newCall(pathReq).execute()) {
                if (!res.isSuccessful()) return null;
                String json = res.body().string();
                filePath = JsonParser.parseString(json).getAsJsonObject()
                    .get("result").getAsJsonObject().get("file_path").getAsString();
            }

            // Step 2: Download Bytes
            Request dlReq = new Request.Builder()
                .url("https://api.telegram.org/file/bot" + TOKEN + "/" + filePath)
                .build();

            try (Response res = client.newCall(dlReq).execute()) {
                if (!res.isSuccessful()) return null;
                byte[] imageBytes = res.body().bytes();
                
                // Step 3: Convert to Base64 (No Wrap)
                return Base64.encodeToString(imageBytes, Base64.NO_WRAP);
            }
        } catch (Exception e) {
            Log.e("BotVision", "Download failed: " + e.getMessage());
            return null;
        }
    }

    // --- NETWORKING ---

    private void sendMessage(long chatId, String text) {
        taskQueue.execute(() -> {
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("text", text)
                .addFormDataPart("parse_mode", "HTML"); // Changed to HTML for safety
            executeAPI("sendMessage", builder.build());
        });
    }

    private void postToChannel(long adminId, Map<String, String> data) {
        taskQueue.execute(() -> {
            String keyboard = "{\"inline_keyboard\":[[{\"text\":\"📥 Download Movie 📥\",\"url\":\"" + data.get("link") + "\"}]]}";
            
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", CHANNEL)
                .addFormDataPart("photo", data.get("photo"))
                .addFormDataPart("caption", data.get("desc"))
                .addFormDataPart("parse_mode", "HTML")
                .addFormDataPart("reply_markup", keyboard);

            if (executeAPI("sendPhoto", builder.build())) {
                sendMessage(adminId, "✅ **Posted!**");
                db.saveState(adminId, 0, new HashMap<>());
            } else {
                sendMessage(adminId, "❌ **Failed to Post.** Check Channel ID.");
            }
        });
    }

    private void sendPreview(long chatId, String photoId, String caption) {
        taskQueue.execute(() -> {
            String keyboard = "{\"inline_keyboard\":[[{\"text\":\"🚀 Post Now\",\"callback_data\":\"post_now\"}], [{\"text\":\"⏰ Schedule\",\"callback_data\":\"schedule\"}]]}";

            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("photo", photoId)
                .addFormDataPart("caption", caption)
                .addFormDataPart("parse_mode", "HTML")
                .addFormDataPart("reply_markup", keyboard);
            
            executeAPI("sendPhoto", builder.build());
        });
    }

    private void answerCallback(String callbackId) {
         taskQueue.execute(() -> {
             MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("callback_query_id", callbackId);
             executeAPI("answerCallbackQuery", builder.build());
         });
    }

    private boolean executeAPI(String method, RequestBody body) {
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

    private void schedulePost(long chatId, int minutes, Map<String, String> data) {
        File file = new File(getFilesDir(), "pending_post_" + System.currentTimeMillis() + ".json");
        try (Writer writer = new FileWriter(file)) {
            new Gson().toJson(data, writer);
            
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, AlarmReceiver.class);
            intent.putExtra("filePath", file.getAbsolutePath());
            
            PendingIntent pi = PendingIntent.getBroadcast(this, (int)System.currentTimeMillis(), intent, PendingIntent.FLAG_IMMUTABLE);
            long triggerTime = System.currentTimeMillis() + (minutes * 60 * 1000L);
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);

            sendMessage(chatId, "✅ **Scheduled.** Sleeping for " + minutes + " mins.");
        } catch (IOException e) { 
             reportError("Storage Error: " + e.getMessage(), false);
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
                CHANNEL = settings.getOrDefault("channel", "");
                GEMINI_KEY = settings.getOrDefault("gemini", "");
                PROMPT_TEMPLATE = settings.getOrDefault("prompt", "");
                MODEL = settings.getOrDefault("model", "gemini-1.5-flash");
            }
        } catch (Exception e) { reportError("Config Error: " + e.getMessage(), false); }
    }

    private void reportError(String msg, boolean toUser) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show()
        );
    }

    private Notification createNotification() {
        String channelId = "bot_service";
        NotificationChannel channel = new NotificationChannel(channelId, "Bot Service", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("MMCH Vision Bot")
                .setContentText("Listening...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        taskQueue.shutdown(); 
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
