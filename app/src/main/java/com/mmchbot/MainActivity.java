package com.mmchbot;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText token, botName, channel, gemini, prompt;
    private Spinner modelSpinner;
    private TextView logs;
    private final String SETTINGS_FILE = "settings.json";

    private final String DEFAULT_PROMPT = 
        "Analyze the movie '{name}' and the attached poster image.\n" +
        "Create a premium Telegram post using this EXACT structure:\n\n" +
        "\n\n🎬 <b>TITLE</b> (Year)\n" +
        "▬▬▬▬▬▬▬▬▬▬▬▬▬▬\n" +
        "🌟 <b>Rating:</b> [If unreleased, write \"Coming Soon 📅\", else \"X.X/10 (IMDb)\"]\n" +
        "🎭 <b>Starring:</b> [List top 3 Main Actors visible on poster or known for the movie]\n" +
        "🔥 <b>Genre:</b> [Genre 1] | [Genre 2] | [more if available]\n\n" +
        "📝 <b>The Hype:</b>\n" +
        "<i>[Write a 1-sentence 'Hook' that makes people want to watch it. Use italics.]</i>\n\n" +
        "📖 <b>Storyline:</b>\n" +
        "[Write a short, engaging 2-3 sentence synopsis. Do NOT use emojis inside sentences.]\n\n" +
        "[Insert 3 relevant hashtags here, e.g., #Action #NewMovie]\n\n" +
        "STRICT RULES:\n" +
        "1. Do NOT use markdown (**). Use HTML <b> and <i> only.\n" +
        "2. Do NOT include download links in the text.\n" +
        "3. Prioritize actors shown on the poster for the Cast list.";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Init Views
        token = findViewById(R.id.inputToken);
        botName = findViewById(R.id.inputBotName);
        channel = findViewById(R.id.inputChannel);
        gemini = findViewById(R.id.inputGemini);
        prompt = findViewById(R.id.inputPrompt);
        modelSpinner = findViewById(R.id.spinnerModel);
        logs = findViewById(R.id.txtLogs);

        // Setup Spinner
        String[] models = {"gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-pro-preview", "gemini-flash-lite-latest"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, models);
        modelSpinner.setAdapter(adapter);

        loadSettingsFromJson();

        findViewById(R.id.btnSave).setOnClickListener(v -> {
            saveSettingsToJson();
            restartBotService();
        });

        findViewById(R.id.btnStop).setOnClickListener(v -> {
            stopService(new Intent(this, BotService.class));
            logs.setText("🛑 Service Stopped");
            Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadSettingsFromJson() {
        File file = new File(getFilesDir(), SETTINGS_FILE);
        if (!file.exists()) {
            prompt.setText(DEFAULT_PROMPT);
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            Map<String, String> settings = new Gson().fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
            if (settings != null) {
                token.setText(settings.getOrDefault("token", ""));
                botName.setText(settings.getOrDefault("botName", ""));
                channel.setText(settings.getOrDefault("channel", ""));
                gemini.setText(settings.getOrDefault("gemini", ""));
                
                String savedPrompt = settings.getOrDefault("prompt", "");
                prompt.setText(savedPrompt.isEmpty() ? DEFAULT_PROMPT : savedPrompt);
                
                String savedModel = settings.getOrDefault("model", "gemini-2.5-flash");
                modelSpinner.setSelection(savedModel.contains("pro") ? 1 : 0);
                
                logs.setText("✅ Settings loaded from storage.");
            }
        } catch (Exception e) {
            logs.setText("⚠️ Load Error: " + e.getMessage());
        }
    }

    private void saveSettingsToJson() {
        Map<String, String> settings = new HashMap<>();
        settings.put("token", token.getText().toString().trim());
        settings.put("botName", botName.getText().toString().trim());
        settings.put("channel", channel.getText().toString().trim());
        settings.put("gemini", gemini.getText().toString().trim());
        settings.put("prompt", prompt.getText().toString());
        settings.put("model", modelSpinner.getSelectedItem().toString());

        try {
            File file = new File(getFilesDir(), SETTINGS_FILE);
            try (FileWriter writer = new FileWriter(file)) {
                new Gson().toJson(settings, writer);
            }
            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show();
            logs.setText(" Settings Saved.");
        } catch (Exception e) {
            Toast.makeText(this, "Save Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restartBotService() {
        Intent intent = new Intent(this, BotService.class);
        stopService(intent); 
        startForegroundService(intent); 
        logs.append("\n🚀 Service Restarted.");
    }
}
