package org.fenixuz.todo;

import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;

public class TodoForwarder {

    public static String formatTodoText(TodoItem todo) {
        StringBuilder sb = new StringBuilder();
        sb.append(todo.completed ? "✅ " : "📝 ");
        if (!TextUtils.isEmpty(todo.title)) {
            sb.append(todo.title);
        }
        if (!TextUtils.isEmpty(todo.description)) {
            if (sb.length() > 2) {
                sb.append("\n\n");
            }
            sb.append(todo.description);
        }
        return sb.toString();
    }

    public static void sendNow(int currentAccount, TodoItem todo, long dialogId) {
        String text = formatTodoText(todo);
        SendMessagesHelper.SendMessageParams params =
                SendMessagesHelper.SendMessageParams.of(text, dialogId, null, null, null,
                        true, null, null, null, true, 0, 0, null, false);
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params);

        ForwardHistoryEntry entry = new ForwardHistoryEntry(todo.id, dialogId, currentAccount, false, 0);
        TodoStorage.getInstance().insertForward(entry);
    }

    public static void sendScheduled(int currentAccount, TodoItem todo, long dialogId, int scheduleDate) {
        String text = formatTodoText(todo);
        SendMessagesHelper.SendMessageParams params =
                SendMessagesHelper.SendMessageParams.of(text, dialogId, null, null, null,
                        true, null, null, null, true, scheduleDate, 0, null, false);
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params);

        ForwardHistoryEntry entry = new ForwardHistoryEntry(todo.id, dialogId, currentAccount, true, scheduleDate * 1000L);
        TodoStorage.getInstance().insertForward(entry);
    }

    public static void showSchedulePicker(BaseFragment fragment, long dialogId, ScheduleCallback callback) {
        Context context = fragment.getParentActivity();
        if (context == null) return;
        AlertsCreator.createScheduleDatePickerDialog(context, dialogId,
                (notify, scheduleDate, scheduleRepeatPeriod) -> callback.onScheduled(scheduleDate)).show();
    }

    public interface ScheduleCallback {
        void onScheduled(int scheduleDate);
    }
}
