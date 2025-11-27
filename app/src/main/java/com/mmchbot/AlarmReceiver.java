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

        // Load Data
        File file = new File(filePath);
        if (!file.exists()) return;

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            Map<String, String> data = new Gson().fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
            reader.close();

            SharedPreferences prefs = context.getSharedPreferences("BotConfig", Context.MODE_PRIVATE);
            String token = prefs.getString("token", "");
            String channel = prefs.getString("channel", "");

            if (!token.isEmpty() && !channel.isEmpty()) {
                // Post in background thread
                new Thread(() -> postRaw(token, channel, data)).start();
            }
            
            // Cleanup
            file.delete();

        } catch (Exception e) { e.printStackTrace(); }
    }

    private void postRaw(String token, String channel, Map<String, String> data) {
        try {
            OkHttpClient client = new OkHttpClient();
            
            // Construct Link Button JSON
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