package org.fenixuz.todo;

import java.util.ArrayList;
import java.util.List;

public class TodoItem {
    public long id;
    public String title;
    public String description;
    public boolean completed;
    public long createdDate;
    public long updatedDate;
    public List<ForwardHistoryEntry> forwardHistory = new ArrayList<>();

    public TodoItem() {
    }

    public TodoItem(String title, String description) {
        this.title = title;
        this.description = description;
        this.completed = false;
        this.createdDate = System.currentTimeMillis();
        this.updatedDate = this.createdDate;
    }
}
