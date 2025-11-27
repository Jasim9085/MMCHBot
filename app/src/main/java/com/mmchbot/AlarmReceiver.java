package com.mmchbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.util.Map;
import okhttp3.*;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String filePath = intent.getStringExtra("filePath");
        if (filePath == null) return;

        File file = new File(filePath);
        if (!file.exists()) return;

        try {
            // Load Settings Manually
            File settingsFile = new File(context.getFilesDir(), "settings.json");
            if (!settingsFile.exists()) return;
            
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
                new Thread(() -> postRaw(token, channel, data)).start();
            }
            file.delete(); // Cleanup

        } catch (Exception e) { e.printStackTrace(); }
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

            client.newCall(request).execute();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
