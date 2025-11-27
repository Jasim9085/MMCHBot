package com.mmchbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.util.Map;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // 1. Acquire a temporary WakeLock to ensure the device stays awake 
        // long enough to send the post.
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMCHBot::AlarmWakeLock");
        wl.acquire(60 * 1000L); // Hold for 60 seconds max

        String filePath = intent.getStringExtra("filePath");
        if (filePath == null) {
            wl.release();
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            wl.release();
            return;
        }

        // 2. Start a background thread (Network cannot run on main thread)
        new Thread(() -> {
            try {
                // Load Settings (To get Token/Channel)
                File settingsFile = new File(context.getFilesDir(), "settings.json");
                Map<String, String> settings;
                try (FileReader reader = new FileReader(settingsFile)) {
                     settings = new Gson().fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
                }
                
                String token = settings.get("token");
                String channel = settings.get("channel");

                // Load Post Data
                Map<String, String> data;
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    data = new Gson().fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
                }

                if (token != null && channel != null) {
                    postRaw(token, channel, data);
                    Log.d("AlarmReceiver", "Scheduled Post Sent!");
                }
                
                // Cleanup
                file.delete();

            } catch (Exception e) {
                Log.e("AlarmReceiver", "Failed: " + e.getMessage());
            } finally {
                // 3. Always release the lock
                if (wl.isHeld()) wl.release();
            }
        }).start();
    }

    private void postRaw(String token, String channel, Map<String, String> data) {
        try {
            OkHttpClient client = new OkHttpClient();
            String keyboard = "{\"inline_keyboard\":[[{\"text\":\"📥 Download Movie 📥\",\"url\":\"" + data.get("link") + "\"}]]}";

            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", channel)
                .addFormDataPart("photo", data.get("photo"))
                .addFormDataPart("caption", data.get("desc"))
                .addFormDataPart("parse_mode", "HTML")
                .addFormDataPart("reply_markup", keyboard);

            Request request = new Request.Builder()
                .url("https://api.telegram.org/bot" + token + "/sendPhoto")
                .post(builder.build())
                .build();

            client.newCall(request).execute().close();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
