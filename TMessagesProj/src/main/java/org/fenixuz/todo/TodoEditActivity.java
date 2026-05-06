package org.fenixuz.todo;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

public class TodoEditActivity extends BaseFragment {

    private static final int DONE_BUTTON = 1;

    private TodoItem todo;
    private boolean isNew;

    private EditTextBoldCursor titleEdit;
    private EditTextBoldCursor descriptionEdit;

    public TodoEditActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        long todoId = arguments != null ? arguments.getLong("todoId", 0) : 0;
        if (todoId > 0) {
            todo = TodoStorage.getInstance().getTodo(todoId);
        }
        if (todo == null) {
            todo = new TodoItem();
            isNew = true;
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(isNew ? R.string.FenixTodoNew : R.string.FenixTodoEdit));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (hasUnsavedChanges()) {
                        confirmDiscard();
                    } else {
                        finishFragment();
                    }
                } else if (id == DONE_BUTTON) {
                    saveAndExit();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(DONE_BUTTON, R.drawable.input_done);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(8), dp(16), dp(16));
        scrollView.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleEdit = new EditTextBoldCursor(context);
        titleEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleEdit.setTypeface(AndroidUtilities.bold());
        titleEdit.setHintText(getString(R.string.FenixTodoTitleHint));
        titleEdit.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        titleEdit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleEdit.setBackgroundDrawable(null);
        titleEdit.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular)
        );
        titleEdit.setSingleLine(true);
        titleEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleEdit.setText(todo.title == null ? "" : todo.title);
        column.addView(titleEdit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        descriptionEdit = new EditTextBoldCursor(context);
        descriptionEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        descriptionEdit.setHintText(getString(R.string.FenixTodoDescHint));
        descriptionEdit.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        descriptionEdit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        descriptionEdit.setBackgroundDrawable(null);
        descriptionEdit.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular)
        );
        descriptionEdit.setGravity(Gravity.TOP | Gravity.LEFT);
        descriptionEdit.setMinLines(4);
        descriptionEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        descriptionEdit.setText(todo.description == null ? "" : todo.description);
        column.addView(descriptionEdit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        AndroidUtilities.runOnUIThread(() -> {
            if (titleEdit != null && isNew) {
                titleEdit.requestFocus();
                AndroidUtilities.showKeyboard(titleEdit);
            }
        }, 200);

        return fragmentView;
    }

    private boolean hasUnsavedChanges() {
        if (titleEdit == null) return false;
        String t = titleEdit.getText().toString();
        String d = descriptionEdit.getText().toString();
        if (isNew) {
            return !TextUtils.isEmpty(t) || !TextUtils.isEmpty(d);
        }
        return !equalsNull(t, todo.title) || !equalsNull(d, todo.description);
    }

    private static boolean equalsNull(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private void confirmDiscard() {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(getString(R.string.FenixTodoDiscardTitle));
        b.setMessage(getString(R.string.FenixTodoDiscardMessage));
        b.setPositiveButton(getString(R.string.PassportDiscard), (d, w) -> finishFragment());
        b.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dlg = b.create();
        dlg.show();
        dlg.redPositive();
    }

    private void saveAndExit() {
        String t = titleEdit.getText().toString().trim();
        String d = descriptionEdit.getText().toString().trim();
        if (TextUtils.isEmpty(t) && TextUtils.isEmpty(d)) {
            finishFragment();
            return;
        }
        todo.title = t;
        todo.description = d;
        if (isNew) {
            todo.createdDate = System.currentTimeMillis();
            todo.updatedDate = todo.createdDate;
            TodoStorage.getInstance().insertTodo(todo);
        } else {
            TodoStorage.getInstance().updateTodo(todo);
        }
        AndroidUtilities.hideKeyboard(titleEdit);
        finishFragment();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (hasUnsavedChanges()) {
            confirmDiscard();
            return false;
        }
        return super.onBackPressed(invoked);
    }
}
