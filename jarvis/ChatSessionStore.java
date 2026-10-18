package org.jarvis.mobile.jarvis;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ChatSessionStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "jarvis_chats.db";
    private static final int DATABASE_VERSION = 2;
    private static final String SESSIONS = "sessions";
    private static final String MESSAGES = "messages";

    public ChatSessionStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        database.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, "
            + "role TEXT NOT NULL, content TEXT NOT NULL, display_content TEXT NOT NULL, "
            + "attachment TEXT, created_at INTEGER NOT NULL)");
        database.execSQL("CREATE INDEX messages_session_order ON messages(session_id, id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            database.execSQL("ALTER TABLE messages ADD COLUMN display_content TEXT");
            database.execSQL("UPDATE messages SET display_content = content");
            database.execSQL("ALTER TABLE messages ADD COLUMN attachment TEXT");
        }
    }

    @Override
    public void onConfigure(SQLiteDatabase database) {
        database.setForeignKeyConstraintsEnabled(true);
    }

    public synchronized long createSession() {
        long now = System.currentTimeMillis();
        String title = new SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.getDefault())
                .format(new Date(now));
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("created_at", now);
        values.put("updated_at", now);
        return getWritableDatabase().insertOrThrow(SESSIONS, null, values);
    }

    public synchronized long getLatestSessionId() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id FROM sessions ORDER BY updated_at DESC, id DESC LIMIT 1", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    public synchronized boolean hasSession(long sessionId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM sessions WHERE id = ?", new String[]{Long.toString(sessionId)})) {
            return cursor.moveToFirst();
        }
    }

    public synchronized boolean deleteSession(long sessionId) {
        return getWritableDatabase().delete(SESSIONS, "id = ?",
                new String[]{Long.toString(sessionId)}) > 0;
    }

    public synchronized void addMessage(long sessionId, String role, String content) {
        addMessage(sessionId, role, content, content, null);
    }

    public synchronized void addMessage(long sessionId, String role, String content,
                                        String displayContent, String attachment) {
        long now = System.currentTimeMillis();
        SQLiteDatabase database = getWritableDatabase();
        ContentValues message = new ContentValues();
        message.put("session_id", sessionId);
        message.put("role", role);
        message.put("content", content);
        message.put("display_content", displayContent);
        message.put("attachment", attachment);
        message.put("created_at", now);
        database.insertOrThrow(MESSAGES, null, message);
        ContentValues session = new ContentValues();
        session.put("updated_at", now);
        database.update(SESSIONS, session, "id = ?", new String[]{Long.toString(sessionId)});
    }

    public synchronized JSONArray getSessions(long activeSessionId) {
        JSONArray sessions = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id, title FROM sessions ORDER BY updated_at DESC, id DESC", null)) {
            while (cursor.moveToNext()) {
                JSONObject session = new JSONObject();
                session.put("id", cursor.getLong(0));
                session.put("title", cursor.getString(1));
                session.put("active", cursor.getLong(0) == activeSessionId);
                sessions.put(session);
            }
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
        return sessions;
    }

    public synchronized JSONArray getMessages(long sessionId) {
        JSONArray messages = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT role, display_content, attachment, created_at "
                        + "FROM messages WHERE session_id = ? ORDER BY id",
                new String[]{Long.toString(sessionId)})) {
            while (cursor.moveToNext()) {
                JSONObject message = new JSONObject();
                message.put("role", cursor.getString(0));
                message.put("content", cursor.getString(1));
                if (!cursor.isNull(2)) {
                    message.put("attachment", cursor.getString(2));
                }
                message.put("createdAt", cursor.getLong(3));
                messages.put(message);
            }
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
        return messages;
    }

    public synchronized List<JSONObject> getAssistantHistory(long sessionId) {
        List<JSONObject> history = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT role, content FROM messages WHERE session_id = ? "
                        + "AND role IN ('user', 'assistant') ORDER BY id",
                new String[]{Long.toString(sessionId)})) {
            while (cursor.moveToNext()) {
                JSONObject message = new JSONObject();
                message.put("role", cursor.getString(0));
                message.put("content", cursor.getString(1));
                history.add(message);
            }
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
        return history;
    }

    public synchronized JSONObject getSessionState(long sessionId) {
        JSONObject state = new JSONObject();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT title FROM sessions WHERE id = ?", new String[]{Long.toString(sessionId)})) {
            if (cursor.moveToFirst()) {
                state.put("id", sessionId);
                state.put("title", cursor.getString(0));
                state.put("messages", getMessages(sessionId));
                state.put("sessions", getSessions(sessionId));
            }
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
        return state;
    }
}