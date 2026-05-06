package org.fenixuz.todo;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.List;

public class TodoActivity extends BaseFragment {

    private SizeNotifierFrameLayout contentView;
    private RecyclerListView listView;
    private TodoAdapter adapter;
    private View emptyView;

    private final List<TodoItem> activeTodos = new ArrayList<>();
    private final List<TodoItem> completedTodos = new ArrayList<>();

    public boolean hasMainTabs;

    public TodoActivity() {
        super();
    }

    public TodoActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        if (!hasMainTabs) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.FenixTodoTitle));
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault));
        actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false);
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSelector), false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        contentView = new SizeNotifierFrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = contentView;

        listView = new RecyclerListView(context);
        LinearLayoutManager lm = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(lm);
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        int bottom = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 8) : dp(8);
        int top = hasMainTabs ? ActionBar.getCurrentActionBarHeight() + dp(16) : dp(16);
        listView.setPadding(0, top, 0, bottom);

        adapter = new TodoAdapter(context);
        listView.setAdapter(adapter);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = createEmptyView(context);
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            int type = adapter.getItemViewType(position);
            if (type == VIEW_TYPE_HEADER) {
                openEditor(null);
                return;
            }
            TodoItem item = adapter.getTodoAt(position);
            if (item != null) {
                openEditor(item);
            }
        });

        return fragmentView;
    }

    private View createEmptyView(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        TextView icon = new TextView(context);
        icon.setText("📝");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 64);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setText(getString(R.string.FenixTodoEmpty));
        title.setGravity(Gravity.CENTER);
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        hint.setText(getString(R.string.FenixTodoEmptyHint));
        hint.setGravity(Gravity.CENTER);
        box.addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        FrameLayout container = new FrameLayout(context);
        container.addView(box, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        return container;
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadTodos();
    }

    private void reloadTodos() {
        activeTodos.clear();
        completedTodos.clear();
        for (TodoItem item : TodoStorage.getInstance().getAllTodos()) {
            if (item.completed) {
                completedTodos.add(item);
            } else {
                activeTodos.add(item);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            boolean isEmpty = activeTodos.isEmpty() && completedTodos.isEmpty();
            emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
    }

    private void openEditor(TodoItem item) {
        Bundle args = new Bundle();
        if (item != null) {
            args.putLong("todoId", item.id);
        }
        presentFragment(new TodoEditActivity(args));
    }

    public void onTodoMenuClick(View anchor, TodoItem item) {
        ItemOptions options = ItemOptions.makeOptions(this, anchor);
        options.add(R.drawable.msg_edit, getString(R.string.Edit), () -> openEditor(item));
        options.add(R.drawable.msg_markread,
                getString(item.completed ? R.string.FenixTodoMarkActive : R.string.FenixTodoMarkDone), () -> {
                    item.completed = !item.completed;
                    TodoStorage.getInstance().setCompleted(item.id, item.completed);
                    reloadTodos();
                });
        options.add(R.drawable.msg_forward, getString(R.string.FenixTodoForward), () -> openForward(item, false));
        options.add(R.drawable.input_schedule, getString(R.string.FenixTodoSchedule), () -> openForward(item, true));
        options.add(R.drawable.msg_list, getString(R.string.FenixTodoHistory), () -> openHistory(item));
        options.addGap();
        options.add(R.drawable.msg_delete, getString(R.string.Delete), true, () -> confirmDelete(item));
        options.show();
    }

    private void confirmDelete(TodoItem item) {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(getString(R.string.FenixTodoDeleteTitle));
        b.setMessage(getString(R.string.FenixTodoDeleteMessage));
        b.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            TodoStorage.getInstance().deleteTodo(item.id);
            reloadTodos();
        });
        b.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dlg = b.create();
        dlg.show();
        dlg.redPositive();
    }

    private void openForward(TodoItem item, boolean schedule) {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        DialogsActivity activity = new DialogsActivity(args);
        activity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) return false;
            long dialogId = dids.get(0).dialogId;
            if (schedule) {
                fragment.finishFragment();
                AndroidUtilities.runOnUIThread(() -> TodoForwarder.showSchedulePicker(this, dialogId, sd -> {
                    TodoForwarder.sendScheduled(currentAccount, item, dialogId, sd);
                    showSentBulletin(true);
                }), 200);
            } else {
                TodoForwarder.sendNow(currentAccount, item, dialogId);
                fragment.finishFragment();
                showSentBulletin(false);
            }
            return true;
        });
        presentFragment(activity);
    }

    private void showSentBulletin(boolean scheduled) {
        if (getParentActivity() == null) return;
        BulletinFactory.of(this).createSimpleBulletin(
                R.raw.forward,
                getString(scheduled ? R.string.FenixTodoScheduledSent : R.string.FenixTodoForwardedSent)
        ).show();
        reloadTodos();
    }

    private void openHistory(TodoItem item) {
        Bundle args = new Bundle();
        args.putLong("todoId", item.id);
        presentFragment(new TodoForwardHistoryActivity(args));
    }

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_TODO = 1;
    private static final int VIEW_TYPE_SECTION = 2;
    private static final int VIEW_TYPE_GAP = 3;

    private class TodoAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        TodoAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int t = holder.getItemViewType();
            return t == VIEW_TYPE_HEADER || t == VIEW_TYPE_TODO;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return VIEW_TYPE_HEADER;
            int p = position - 1;
            if (p < activeTodos.size()) return VIEW_TYPE_TODO;
            p -= activeTodos.size();
            if (completedTodos.isEmpty()) return VIEW_TYPE_GAP;
            if (p == 0) return VIEW_TYPE_SECTION;
            return VIEW_TYPE_TODO;
        }

        TodoItem getTodoAt(int position) {
            if (position == 0) return null;
            int p = position - 1;
            if (p < activeTodos.size()) return activeTodos.get(p);
            p -= activeTodos.size();
            if (!completedTodos.isEmpty()) {
                p -= 1;
                if (p >= 0 && p < completedTodos.size()) return completedTodos.get(p);
            }
            return null;
        }

        @Override
        public int getItemCount() {
            int count = 1 + activeTodos.size();
            if (!completedTodos.isEmpty()) {
                count += 1 + completedTodos.size();
            }
            return count;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v;
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    v = new CreateNoteHeaderView(context);
                    break;
                case VIEW_TYPE_SECTION:
                    v = new SectionHeaderView(context);
                    break;
                case VIEW_TYPE_GAP:
                    v = new View(context);
                    v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                    break;
                case VIEW_TYPE_TODO:
                default:
                    v = new TodoCell(context);
                    break;
            }
            if (!(v.getLayoutParams() instanceof RecyclerView.LayoutParams)) {
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == VIEW_TYPE_TODO) {
                TodoItem item = getTodoAt(position);
                if (item == null) return;
                TodoCell cell = (TodoCell) holder.itemView;
                boolean lastInActive = !item.completed && activeTodos.indexOf(item) == activeTodos.size() - 1;
                boolean lastInCompleted = item.completed && completedTodos.indexOf(item) == completedTodos.size() - 1;
                cell.setTodo(item, !(lastInActive || lastInCompleted));
                cell.setOnCheckClick(v -> {
                    item.completed = !item.completed;
                    TodoStorage.getInstance().setCompleted(item.id, item.completed);
                    reloadTodos();
                });
                cell.menuButton.setOnClickListener(v -> onTodoMenuClick(v, item));
            } else if (type == VIEW_TYPE_SECTION) {
                ((SectionHeaderView) holder.itemView).setText(getString(R.string.FenixTodoCompleted) + " (" + completedTodos.size() + ")");
            }
        }
    }

    private static class CreateNoteHeaderView extends FrameLayout {
        public CreateNoteHeaderView(@NonNull Context context) {
            super(context);
            setPadding(dp(16), dp(12), dp(16), dp(12));

            FrameLayout button = new FrameLayout(context);
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            int accentText = Theme.getColor(Theme.key_featuredStickers_buttonText);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(28));
            bg.setColor(accent);
            button.setBackground(bg);
            button.setForeground(Theme.createSelectorDrawable(0x33ffffff, 0, dp(28)));

            android.widget.ImageView plus = new android.widget.ImageView(context);
            plus.setImageResource(R.drawable.menu_topic_add);
            plus.setColorFilter(new android.graphics.PorterDuffColorFilter(accentText, android.graphics.PorterDuff.Mode.MULTIPLY));
            plus.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            button.addView(plus, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 22, 0, 0, 0));

            TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            label.setTextColor(accentText);
            label.setTypeface(AndroidUtilities.bold());
            label.setAllCaps(false);
            label.setText(getString(R.string.FenixTodoCreate));
            button.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL, 56, 0, 16, 0));

            addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 52));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(76), MeasureSpec.EXACTLY));
        }
    }

    private static class SectionHeaderView extends FrameLayout {
        private final TextView text;

        public SectionHeaderView(@NonNull Context context) {
            super(context);
            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            text.setTypeface(AndroidUtilities.bold());
            addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL, 24, 0, 16, 0));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }

        public void setText(CharSequence s) {
            text.setText(s);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(40), MeasureSpec.EXACTLY));
        }
    }
}
