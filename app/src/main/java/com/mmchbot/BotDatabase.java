package com.mmchbot;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.HashMap;
import java.util.Map;

public class BotDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "bot_brain.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_STATE = "user_states";
    private static final String COL_CHAT_ID = "chat_id";
    private static final String COL_STEP = "step";
    private static final String COL_DATA = "data_json";

    public BotDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_STATE + " (" +
                COL_CHAT_ID + " INTEGER PRIMARY KEY, " +
                COL_STEP + " INTEGER, " +
                COL_DATA + " TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_STATE);
        onCreate(db);
    }

    // --- METHODS ---

    public void saveState(long chatId, int step, Map<String, String> data) {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_CHAT_ID, chatId);
            values.put(COL_STEP, step);
            values.put(COL_DATA, new Gson().toJson(data));

            db.insertWithOnConflict(TABLE_STATE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            db.close();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public int getStep(long chatId) {
        int step = 0;
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.query(TABLE_STATE, new String[]{COL_STEP}, COL_CHAT_ID + "=?", new String[]{String.valueOf(chatId)}, null, null, null);
            if (cursor.moveToFirst()) step = cursor.getInt(0);
            cursor.close();
            db.close();
        } catch (Exception e) { e.printStackTrace(); }
        return step;
    }

    public Map<String, String> getData(long chatId) {
        Map<String, String> data = new HashMap<>();
        try {
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.query(TABLE_STATE, new String[]{COL_DATA}, COL_CHAT_ID + "=?", new String[]{String.valueOf(chatId)}, null, null, null);
            if (cursor.moveToFirst()) {
                String json = cursor.getString(0);
                data = new Gson().fromJson(json, new TypeToken<Map<String, String>>(){}.getType());
            }
            cursor.close();
            db.close();
        } catch (Exception e) { e.printStackTrace(); }
        return data;
    }
}
