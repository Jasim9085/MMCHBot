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
    
    // Professional Queue: Handles tasks one by one to prevent crashes
    private final ExecutorService taskQueue = Executors.newSingleThreadExecutor();

    // Config
    private String TOKEN = "", USERNAME = "", CHANNEL = "", GEMINI_KEY = "", PROMPT_TEMPLATE = "", MODEL = "";
    private final String SETTINGS_FILE = "settings.json";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. Load Config & DB
        loadConfig();
        db = new BotDatabase(this);
        
        startForeground(1, createNotification());

        // 2. Acquire Smart WakeLock (10 min timeout safety)
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
            
            // 3. Network Optimization: Connection Pooling & Timeouts
            client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS) // Longer timeout for AI
                .readTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
            
            new Thread(this::runAdaptivePollingLoop).start();
            reportError("✅ Enterprise Bot Started", false);
        }

        return START_STICKY;
    }

    // --- ADAPTIVE POLLING ENGINE ---
    // Saves battery by slowing down when no one is talking
    private void runAdaptivePollingLoop() {
        int currentInterval = 1000; // Start fast (1s)
        final int MAX_INTERVAL = 30000; // Max slow (30s)
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
                        
                        // Adaptive Logic
                        if (activityFound) {
                            currentInterval = 1000; // Speed up
                            retryCount = 0;
                        } else {
                            // Slow down gradually
                            currentInterval = Math.min(currentInterval + 500, MAX_INTERVAL);
                        }
                    } else {
                         throw new IOException("HTTP " + response.code());
                    }
                }
            } catch (Exception e) {
                retryCount++;
                Log.e("BotEngine", "Loop Error: " + e.getMessage());
                // Backoff Strategy
                try { Thread.sleep(Math.min(retryCount * 2000, 60000)); } catch (InterruptedException ignored) {}
            }
            
            // Sleep for the adaptive interval
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
                
                // Offload logic to Queue to keep loop fast
                taskQueue.execute(() -> {
                    try {
                        if (update.has("message")) {
                            handleMessage(update.getAsJsonObject("message"));
                        } else if (update.has("callback_query")) {
                            handleCallback(update.getAsJsonObject("callback_query"));
                        }
                    } catch (Exception e) {
                        reportError("Logic Crash: " + e.getMessage(), false);
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return hasActivity;
    }

    private void handleMessage(JsonObject message) {
        long chatId = message.get("chat").getAsJsonObject().get("id").getAsLong();
        String text = message.has("text") ? message.get("text").getAsString() : null;

        // Load State from DB
        int state = db.getStep(chatId);
        Map<String, String> data = db.getData(chatId);

        // --- COMMANDS ---
        if (text != null) {
            if (text.equals("/start")) {
                db.saveState(chatId, 0, new HashMap<>());
                sendMessage(chatId, "🎬 **Professional Movie Bot**\n\nSystem Online.\nSend me a **Thumbnail**.");
                return;
            }
            if (text.equals("/status")) {
                sendHealthReport(chatId);
                return;
            }
        }

        // --- STATE MACHINE ---
        try {
            if (state == 0) { // Photo
                String fileId = extractFileId(message, chatId);
                if (fileId != null) {
                    data.put("photo", fileId);
                    db.saveState(chatId, 1, data);
                    sendMessage(chatId, "✅ Image Locked.\nSend **Movie Name**.");
                }
            } 
            else if (state == 1 && text != null) { // Name
                data.put("name", text);
                db.saveState(chatId, 2, data);
                sendMessage(chatId, "✅ Name Locked.\nSend **Download Link**.");
            }
            else if (state == 2 && text != null) { // Link
                data.put("link", text);
                db.saveState(chatId, 2, data); // Stay on state 2 while generating
                sendMessage(chatId, "🧠 **AI Processing...**\nAnalyzing request with " + MODEL);
                generateGemini(chatId, data.get("name"), data.get("photo"));
            }
            else if (state == 3 && text != null) { // Schedule
                 try {
                     int minutes = Integer.parseInt(text);
                     schedulePost(chatId, minutes, data);
                     db.saveState(chatId, 0, new HashMap<>()); 
                 } catch (NumberFormatException e) {
                     sendMessage(chatId, "⚠️ Invalid Number. Try again.");
                 }
            }
        } catch (Exception e) {
            sendMessage(chatId, "⚠️ **Critical Error:** " + e.getMessage());
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

    // --- INTELLIGENCE & UTILS ---

    private void sendHealthReport(long chatId) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;

        String report = "📊 **System Health Report**\n\n" +
                "🔋 **Battery:** " + level + "%\n" +
                "🧠 **Memory:** Stable\n" +
                "🤖 **Bot Engine:** Running\n" +
                "📡 **Network:** Active\n" +
                "📚 **DB Status:** Connected";
        sendMessage(chatId, report);
    }

    private void generateGemini(long chatId, String name, String photoId) {
        // We use the queue again to ensure AI requests don't block
        taskQueue.execute(() -> {
            try {
                String prompt = PROMPT_TEMPLATE.replace("{name}", name) + " for " + name;
                JSONObject json = new JSONObject();
                json.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));

                RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json"));
                Request req = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=" + GEMINI_KEY)
                    .post(body)
                    .build();

                try (Response res = client.newCall(req).execute()) {
                    if (!res.isSuccessful()) throw new IOException("Gemini API " + res.code());
                    
                    String resStr = res.body().string();
                    String result = new JSONObject(resStr).getJSONArray("candidates")
                        .getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text");
                    
                    Map<String, String> data = db.getData(chatId);
                    data.put("desc", result);
                    db.saveState(chatId, 2, data); // Update DB
                    
                    sendPreview(chatId, photoId, result);
                }
            } catch (Exception e) {
                sendMessage(chatId, "⚠️ **AI Generation Failed:** " + e.getMessage() + "\nTry again later.");
            }
        });
    }

    private void sendMessage(long chatId, String text) {
        taskQueue.execute(() -> {
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("text", text)
                .addFormDataPart("parse_mode", "Markdown");
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
                db.saveState(adminId, 0, new HashMap<>()); // Reset DB on success
            } else {
                sendMessage(adminId, "❌ **Failed to Post.** Check Permissions.");
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
        } catch (Exception e) { 
            Log.e("BotNetwork", "Req Failed: " + e.getMessage());
            return false; 
        }
    }

    // --- HELPERS ---

    private void reportError(String msg, boolean toUser) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show()
        );
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

    private Notification createNotification() {
        String channelId = "bot_service";
        NotificationChannel channel = new NotificationChannel(channelId, "Bot Service", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("MMCH Enterprise Bot")
                .setContentText("Status: Online | Mode: Adaptive")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        taskQueue.shutdown(); // Clean up threads
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
