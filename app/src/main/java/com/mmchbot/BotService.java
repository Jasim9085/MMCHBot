package com.mmchbot;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class BotService extends Service {

    private TelegramLongPollingBot bot;
    private TelegramBotsApi botsApi;

    // States
    private final Map<Long, Integer> userState = new HashMap<>();
    private final Map<Long, Map<String, String>> userData = new HashMap<>();
    
    // Config
    private String TOKEN = "", USERNAME = "", CHANNEL = "", GEMINI_KEY = "", PROMPT_TEMPLATE = "", MODEL = "";
    private final String SETTINGS_FILE = "settings.json";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadConfig(); // <--- READ FROM JSON HERE

        startForeground(1, createNotification());

        if (TOKEN.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (botsApi == null) botsApi = new TelegramBotsApi(DefaultBotSession.class);
            bot = new MovieBot();
            botsApi.registerBot(bot);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return START_STICKY;
    }

    private void loadConfig() {
        File file = new File(getFilesDir(), SETTINGS_FILE);
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            Map<String, String> settings = new Gson().fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
            if (settings != null) {
                TOKEN = settings.getOrDefault("token", "");
                USERNAME = settings.getOrDefault("botName", "");
                CHANNEL = settings.getOrDefault("channel", "");
                GEMINI_KEY = settings.getOrDefault("gemini", "");
                PROMPT_TEMPLATE = settings.getOrDefault("prompt", "");
                MODEL = settings.getOrDefault("model", "gemini-1.5-flash");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Notification createNotification() {
        String channelId = "bot_service";
        NotificationChannel channel = new NotificationChannel(channelId, "Bot Service", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("MMCH Bot Running")
                .setContentText("Connected as: " + USERNAME)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // --- BOT INNER CLASS ---
    public class MovieBot extends TelegramLongPollingBot {

        @Override
        public String getBotUsername() { return USERNAME; }
        @Override
        public String getBotToken() { return TOKEN; }

        @Override
        public void onUpdateReceived(Update update) {
            try {
                if (update.hasMessage()) {
                    long chatId = update.getMessage().getChatId();
                    String text = update.getMessage().getText();
                    
                    userState.putIfAbsent(chatId, 0);
                    userData.putIfAbsent(chatId, new HashMap<>());

                    if (text != null && text.equals("/start")) {
                        userState.put(chatId, 0);
                        sendMsg(chatId, "🎬 **Movie Helper**\nSend Thumbnail.");
                        return;
                    } else if (text != null && text.equals("/cancel")) {
                        userState.put(chatId, 0);
                        sendMsg(chatId, "❌ Cancelled.");
                        return;
                    }

                    int state = userState.get(chatId);

                    if (state == 0 && update.getMessage().hasPhoto()) {
                        String fileId = update.getMessage().getPhoto().get(update.getMessage().getPhoto().size() - 1).getFileId();
                        userData.get(chatId).put("photo", fileId);
                        userState.put(chatId, 1);
                        sendMsg(chatId, "✅ Send Movie Name.");
                    } 
                    else if (state == 1 && text != null) {
                        userData.get(chatId).put("name", text);
                        userState.put(chatId, 2);
                        sendMsg(chatId, "✅ Send Download Link.");
                    }
                    else if (state == 2 && text != null) {
                        userData.get(chatId).put("link", text);
                        sendMsg(chatId, "🤖 Generating with " + MODEL + "...");
                        new GeminiGenTask(chatId).execute(userData.get(chatId).get("name"));
                    }
                    else if (state == 3 && text != null) {
                         try {
                             int minutes = Integer.parseInt(text);
                             schedulePost(chatId, minutes);
                             userState.put(chatId, 0); 
                         } catch (Exception e) {
                             sendMsg(chatId, "⚠️ Invalid number. Try again.");
                         }
                    }
                }
                else if (update.hasCallbackQuery()) {
                    String data = update.getCallbackQuery().getData();
                    long chatId = update.getCallbackQuery().getMessage().getChatId();
                    
                    if (data.equals("post_now")) {
                        postToChannel(chatId, userData.get(chatId));
                    } else if (data.equals("schedule")) {
                         userState.put(chatId, 3);
                         sendMsg(chatId, "⏳ Enter delay in minutes (e.g. 60):");
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        private void schedulePost(long chatId, int minutes) {
            Map<String, String> data = userData.get(chatId);
            File file = new File(getFilesDir(), "pending_post_" + System.currentTimeMillis() + ".json");
            try (Writer writer = new FileWriter(file)) {
                new Gson().toJson(data, writer);
            } catch (IOException e) { e.printStackTrace(); }

            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(BotService.this, AlarmReceiver.class);
            intent.putExtra("filePath", file.getAbsolutePath());
            
            PendingIntent pi = PendingIntent.getBroadcast(BotService.this, (int)System.currentTimeMillis(), intent, PendingIntent.FLAG_IMMUTABLE);
            
            long triggerTime = System.currentTimeMillis() + (minutes * 60 * 1000);
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi);

            sendMsg(chatId, "✅ Scheduled! I will wake up in " + minutes + " mins to post.");
        }

        private void postToChannel(long adminId, Map<String, String> data) {
            try {
                SendPhoto photo = new SendPhoto();
                photo.setChatId(CHANNEL);
                photo.setPhoto(new InputFile(data.get("photo")));
                photo.setCaption(data.get("desc"));
                photo.setParseMode("HTML");
                
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                List<InlineKeyboardButton> row = new ArrayList<>();
                InlineKeyboardButton btn = new InlineKeyboardButton();
                btn.setText("📥 Download Movie 📥");
                btn.setUrl(data.get("link"));
                row.add(btn);
                rows.add(row);
                markup.setKeyboard(rows);
                photo.setReplyMarkup(markup);
                
                // FIXED SCOPE ISSUE
                MovieBot.this.execute(photo); 
                sendMsg(adminId, "✅ Posted successfully!");
            } catch (Exception e) { sendMsg(adminId, "Error: " + e.getMessage()); }
        }

        private void sendMsg(long chatId, String text) {
            try { execute(new SendMessage(String.valueOf(chatId), text)); } catch (Exception e) {}
        }
        
        // --- GEMINI TASK ---
        private class GeminiGenTask extends AsyncTask<String, Void, String> {
            long chatId;
            public GeminiGenTask(long chatId) { this.chatId = chatId; }

            @Override
            protected String doInBackground(String... params) {
                try {
                    OkHttpClient client = new OkHttpClient();
                    String prompt = PROMPT_TEMPLATE.replace("{name}", params[0]) + " for " + params[0];
                    
                    JSONObject json = new JSONObject();
                    json.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));

                    RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json"));
                    Request req = new Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=" + GEMINI_KEY)
                        .post(body)
                        .build();

                    Response res = client.newCall(req).execute();
                    String resStr = res.body().string();
                    return new JSONObject(resStr).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                } catch (Exception e) { return "Error: " + e.getMessage(); }
            }

            @Override
            protected void onPostExecute(String result) {
                userData.get(chatId).put("desc", result);
                
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                List<InlineKeyboardButton> row = new ArrayList<>();
                InlineKeyboardButton b1 = new InlineKeyboardButton("🚀 Post Now"); b1.setCallbackData("post_now");
                InlineKeyboardButton b2 = new InlineKeyboardButton("⏰ Schedule"); b2.setCallbackData("schedule");
                row.add(b1); row.add(b2);
                rows.add(row);
                markup.setKeyboard(rows);

                SendPhoto photo = new SendPhoto();
                photo.setChatId(String.valueOf(chatId));
                photo.setPhoto(new InputFile(userData.get(chatId).get("photo")));
                photo.setCaption(result);
                photo.setParseMode("HTML");
                photo.setReplyMarkup(markup);
                
                // FIXED SCOPE ISSUE
                try { MovieBot.this.execute(photo); } catch (Exception e) {}
            }
        }
    }
}
