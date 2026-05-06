package org.fenixuz.todo;

public class ForwardHistoryEntry {
    public long id;
    public long todoId;
    public long dialogId;
    public long sentDate;
    public boolean scheduled;
    public long scheduledDate;
    public int messageId;
    public int currentAccount;

    public ForwardHistoryEntry() {
    }

    public ForwardHistoryEntry(long todoId, long dialogId, int currentAccount, boolean scheduled, long scheduledDate) {
        this.todoId = todoId;
        this.dialogId = dialogId;
        this.currentAccount = currentAccount;
        this.scheduled = scheduled;
        this.scheduledDate = scheduledDate;
        this.sentDate = System.currentTimeMillis();
    }
}
