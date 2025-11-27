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

    // 👇 THIS IS THE NEW STRICT PROMPT 👇
    private final String DEFAULT_PROMPT = 
        "Generate an aesthetic Telegram movie post for '{name}'.\n\n" +
        "STRICT RULES:\n" +
        "1. Use HTML tags <b> and <i> ONLY. Do NOT use Markdown (** or __).\n" +
        "2. Do NOT include download links.\n" +
        "3. Keep the Synopsis short (2-3 sentences).\n\n" +
        "REQUIRED FORMAT:\n" +
        "🎬 <b>Title</b> (Year)\n" +
        "⭐️ <b>Rating:</b> 0.0/10 (IMDb)\n" +
        "🎭 <b>Cast:</b> Actor 1, Actor 2, Actor 3\n" +
        "📚 <b>Genre:</b> Action, Drama, Horror, Thriller etc.\n\n" +
        "📝 <b>Storyline:</b>\n" +
        "<i>[Insert engaging synopsis here with emojis]</i>";

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
        String[] models = {"gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-pro-preview"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, models);
        modelSpinner.setAdapter(adapter);

        // Load Settings
        loadSettingsFromJson();

        // Save & Restart
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            saveSettingsToJson();
            restartBotService();
        });

        // Stop
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            stopService(new Intent(this, BotService.class));
            logs.setText("🛑 Service Stopped");
        });
    }

    private void loadSettingsFromJson() {
        File file = new File(getFilesDir(), SETTINGS_FILE);
        
        // If file doesn't exist, fill the Prompt with our new Default
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
                
                // Use saved prompt, OR default if empty
                String savedPrompt = settings.getOrDefault("prompt", "");
                if (savedPrompt.isEmpty()) {
                    prompt.setText(DEFAULT_PROMPT);
                } else {
                    prompt.setText(savedPrompt);
                }
                
                // Set Spinner
                String savedModel = settings.getOrDefault("model", "gemini-2.5-flash");
                if (savedModel.contains("pro")) modelSpinner.setSelection(1);
                else modelSpinner.setSelection(0);
                
                logs.setText("✅ Settings loaded.");
            }
        } catch (Exception e) {
            logs.setText("⚠️ Error loading settings: " + e.getMessage());
            prompt.setText(DEFAULT_PROMPT); // Fallback
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
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
            logs.setText("💾 Settings Saved.");
        } catch (Exception e) {
            Toast.makeText(this, "Save Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restartBotService() {
        Intent intent = new Intent(this, BotService.class);
        stopService(intent); 
        startForegroundService(intent); 
        logs.append("\n🚀 Bot Restarting...");
    }
}
