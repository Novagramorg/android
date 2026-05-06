package org.fenixuz.todo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.List;

public class TodoStorage extends SQLiteOpenHelper {

    private static final String DB_NAME = "fenixuz_todo.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_TODOS = "todos";
    private static final String TABLE_FORWARDS = "todo_forwards";

    private static volatile TodoStorage instance;

    public static TodoStorage getInstance() {
        TodoStorage local = instance;
        if (local == null) {
            synchronized (TodoStorage.class) {
                local = instance;
                if (local == null) {
                    local = new TodoStorage(ApplicationLoader.applicationContext);
                    instance = local;
                }
            }
        }
        return local;
    }

    private TodoStorage(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TODOS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT, " +
                "description TEXT, " +
                "completed INTEGER NOT NULL DEFAULT 0, " +
                "created_date INTEGER NOT NULL, " +
                "updated_date INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_FORWARDS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "todo_id INTEGER NOT NULL, " +
                "dialog_id INTEGER NOT NULL, " +
                "current_account INTEGER NOT NULL DEFAULT 0, " +
                "sent_date INTEGER NOT NULL, " +
                "scheduled INTEGER NOT NULL DEFAULT 0, " +
                "scheduled_date INTEGER NOT NULL DEFAULT 0, " +
                "message_id INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(todo_id) REFERENCES " + TABLE_TODOS + "(id) ON DELETE CASCADE)");

        db.execSQL("CREATE INDEX idx_forwards_todo ON " + TABLE_FORWARDS + "(todo_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public long insertTodo(TodoItem todo) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", todo.title);
        cv.put("description", todo.description);
        cv.put("completed", todo.completed ? 1 : 0);
        cv.put("created_date", todo.createdDate);
        cv.put("updated_date", todo.updatedDate);
        long id = db.insert(TABLE_TODOS, null, cv);
        todo.id = id;
        return id;
    }

    public void updateTodo(TodoItem todo) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", todo.title);
        cv.put("description", todo.description);
        cv.put("completed", todo.completed ? 1 : 0);
        cv.put("updated_date", System.currentTimeMillis());
        db.update(TABLE_TODOS, cv, "id=?", new String[]{String.valueOf(todo.id)});
    }

    public void setCompleted(long todoId, boolean completed) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("completed", completed ? 1 : 0);
        cv.put("updated_date", System.currentTimeMillis());
        db.update(TABLE_TODOS, cv, "id=?", new String[]{String.valueOf(todoId)});
    }

    public void deleteTodo(long todoId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_FORWARDS, "todo_id=?", new String[]{String.valueOf(todoId)});
        db.delete(TABLE_TODOS, "id=?", new String[]{String.valueOf(todoId)});
    }

    public TodoItem getTodo(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_TODOS, null, "id=?", new String[]{String.valueOf(id)}, null, null, null);
        TodoItem item = null;
        if (c.moveToFirst()) {
            item = readTodo(c);
        }
        c.close();
        return item;
    }

    public List<TodoItem> getAllTodos() {
        List<TodoItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_TODOS, null, null, null, null, null,
                "completed ASC, updated_date DESC");
        while (c.moveToNext()) {
            list.add(readTodo(c));
        }
        c.close();
        return list;
    }

    private TodoItem readTodo(Cursor c) {
        TodoItem item = new TodoItem();
        item.id = c.getLong(c.getColumnIndexOrThrow("id"));
        item.title = c.getString(c.getColumnIndexOrThrow("title"));
        item.description = c.getString(c.getColumnIndexOrThrow("description"));
        item.completed = c.getInt(c.getColumnIndexOrThrow("completed")) != 0;
        item.createdDate = c.getLong(c.getColumnIndexOrThrow("created_date"));
        item.updatedDate = c.getLong(c.getColumnIndexOrThrow("updated_date"));
        return item;
    }

    public long insertForward(ForwardHistoryEntry entry) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("todo_id", entry.todoId);
        cv.put("dialog_id", entry.dialogId);
        cv.put("current_account", entry.currentAccount);
        cv.put("sent_date", entry.sentDate);
        cv.put("scheduled", entry.scheduled ? 1 : 0);
        cv.put("scheduled_date", entry.scheduledDate);
        cv.put("message_id", entry.messageId);
        long id = db.insert(TABLE_FORWARDS, null, cv);
        entry.id = id;
        return id;
    }

    public List<ForwardHistoryEntry> getForwardsForTodo(long todoId) {
        List<ForwardHistoryEntry> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_FORWARDS, null, "todo_id=?",
                new String[]{String.valueOf(todoId)}, null, null, "sent_date DESC");
        while (c.moveToNext()) {
            ForwardHistoryEntry e = new ForwardHistoryEntry();
            e.id = c.getLong(c.getColumnIndexOrThrow("id"));
            e.todoId = c.getLong(c.getColumnIndexOrThrow("todo_id"));
            e.dialogId = c.getLong(c.getColumnIndexOrThrow("dialog_id"));
            e.currentAccount = c.getInt(c.getColumnIndexOrThrow("current_account"));
            e.sentDate = c.getLong(c.getColumnIndexOrThrow("sent_date"));
            e.scheduled = c.getInt(c.getColumnIndexOrThrow("scheduled")) != 0;
            e.scheduledDate = c.getLong(c.getColumnIndexOrThrow("scheduled_date"));
            e.messageId = c.getInt(c.getColumnIndexOrThrow("message_id"));
            list.add(e);
        }
        c.close();
        return list;
    }

    public int getForwardCount(long todoId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_FORWARDS + " WHERE todo_id=?",
                new String[]{String.valueOf(todoId)});
        int count = 0;
        if (c.moveToFirst()) {
            count = c.getInt(0);
        }
        c.close();
        return count;
    }
}
