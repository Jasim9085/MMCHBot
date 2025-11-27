package com.mmchbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || 
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                
                Intent serviceIntent = new Intent(context, BotService.class);
                context.startForegroundService(serviceIntent);
                
                Toast.makeText(context, "🤖 MMCH Bot Auto-Started", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
