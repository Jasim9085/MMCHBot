package com.mmchbot;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText token, botName, channel, gemini, prompt;
    private Spinner modelSpinner;
    private TextView logs;

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
        String[] models = {"gemini-2.5-flash", "gemini-2.5-pro"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, models);
        modelSpinner.setAdapter(adapter);

        loadSettings();

        // Save & Restart
        findViewById(R.id.btnSave).setOnClickListener(v -> {
            saveSettings();
            restartBotService();
        });

        // Stop
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            stopService(new Intent(this, BotService.class));
            logs.setText("🛑 Service Stopped");
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("BotConfig", MODE_PRIVATE);
        token.setText(prefs.getString("token", ""));
        botName.setText(prefs.getString("botName", ""));
        channel.setText(prefs.getString("channel", ""));
        gemini.setText(prefs.getString("gemini", ""));
        prompt.setText(prefs.getString("prompt", "Create an aesthetic post..."));
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = getSharedPreferences("BotConfig", MODE_PRIVATE).edit();
        editor.putString("token", token.getText().toString());
        editor.putString("botName", botName.getText().toString());
        editor.putString("channel", channel.getText().toString());
        editor.putString("gemini", gemini.getText().toString());
        editor.putString("prompt", prompt.getText().toString());
        editor.putString("model", modelSpinner.getSelectedItem().toString());
        editor.apply();
        Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show();
    }

    private void restartBotService() {
        Intent intent = new Intent(this, BotService.class);
        stopService(intent); // Stop old instance
        startForegroundService(intent); // Start new
        logs.setText("🚀 Bot Restarting with new settings...");
    }
}