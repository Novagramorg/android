package org.fenixuz.todo;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ProfileSearchCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class TodoForwardHistoryActivity extends BaseFragment {

    private long todoId;
    private TodoItem todo;
    private final List<ForwardHistoryEntry> entries = new ArrayList<>();
    private RecyclerListView listView;
    private FrameLayout emptyContainer;

    public TodoForwardHistoryActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        todoId = arguments != null ? arguments.getLong("todoId", 0) : 0;
        todo = TodoStorage.getInstance().getTodo(todoId);
        return todo != null && super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.FenixTodoHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new HistoryAdapter(context));
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyContainer = new FrameLayout(context);
        TextView emptyText = new TextView(context);
        emptyText.setText(getString(R.string.FenixTodoHistoryEmpty));
        emptyText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setTextSize(15);
        emptyContainer.addView(emptyText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        root.addView(emptyContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyContainer.setVisibility(View.GONE);

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= entries.size()) return;
            ForwardHistoryEntry e = entries.get(position);
            Bundle args = new Bundle();
            if (e.dialogId > 0) {
                args.putLong("user_id", e.dialogId);
            } else {
                args.putLong("chat_id", -e.dialogId);
            }
            presentFragment(new ChatActivity(args));
        });

        loadEntries();
        return fragmentView;
    }

    private void loadEntries() {
        entries.clear();
        entries.addAll(TodoStorage.getInstance().getForwardsForTodo(todoId));
        if (listView != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
        if (emptyContainer != null) {
            emptyContainer.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private class HistoryAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        HistoryAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ProfileSearchCell cell = new ProfileSearchCell(context, resourceProvider);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ForwardHistoryEntry e = entries.get(position);
            ProfileSearchCell cell = (ProfileSearchCell) holder.itemView;

            Object dialogObj = null;
            CharSequence name = "";
            if (e.dialogId > 0) {
                TLRPC.User user = MessagesController.getInstance(e.currentAccount).getUser(e.dialogId);
                if (user != null) {
                    dialogObj = user;
                    name = org.telegram.messenger.UserObject.getUserName(user);
                }
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(e.currentAccount).getChat(-e.dialogId);
                if (chat != null) {
                    dialogObj = chat;
                    name = chat.title;
                }
            }
            if (dialogObj == null) {
                name = String.valueOf(e.dialogId);
            }

            CharSequence status;
            if (e.scheduled) {
                long when = e.scheduledDate;
                status = LocaleController.formatString("FenixTodoScheduledOn", R.string.FenixTodoScheduledOn,
                        LocaleController.formatDateTime(when / 1000L, true));
            } else {
                status = LocaleController.formatString("FenixTodoSentOn", R.string.FenixTodoSentOn,
                        LocaleController.formatDateTime(e.sentDate / 1000L, true));
            }

            cell.setData(dialogObj, null, name, status, false, false);
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }
}
