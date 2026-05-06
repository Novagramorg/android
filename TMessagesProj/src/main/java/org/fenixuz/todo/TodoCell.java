package org.fenixuz.todo;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public class TodoCell extends FrameLayout {

    private final ImageView checkBox;
    private final TextView titleView;
    private final TextView descriptionView;
    public final ImageView menuButton;

    private TodoItem todo;
    private boolean needDivider;
    private final Paint dividerPaint;

    public TodoCell(@NonNull Context context) {
        super(context);

        setBackground(Theme.getSelectorDrawable(false));

        checkBox = new ImageView(context);
        checkBox.setScaleType(ImageView.ScaleType.CENTER);
        checkBox.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        addView(checkBox, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 56, 12, 56, 0));

        descriptionView = new TextView(context);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        descriptionView.setMaxLines(2);
        descriptionView.setEllipsize(TextUtils.TruncateAt.END);
        addView(descriptionView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 56, 36, 56, 10));

        menuButton = new ImageView(context);
        menuButton.setScaleType(ImageView.ScaleType.CENTER);
        menuButton.setImageResource(R.drawable.ic_ab_other);
        menuButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        addView(menuButton, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        dividerPaint = new Paint();
        dividerPaint.setStrokeWidth(1);
        setWillNotDraw(false);
    }

    public void setTodo(TodoItem item, boolean divider) {
        this.todo = item;
        this.needDivider = divider;

        titleView.setText(TextUtils.isEmpty(item.title) ? "" : item.title);
        if (TextUtils.isEmpty(item.description)) {
            descriptionView.setVisibility(GONE);
        } else {
            descriptionView.setVisibility(VISIBLE);
            descriptionView.setText(item.description);
        }

        updateColors();
        requestLayout();
    }

    private void updateColors() {
        if (todo == null) return;
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int subColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
        int accent = Theme.getColor(Theme.key_chats_actionBackground);

        if (todo.completed) {
            titleView.setTextColor(subColor);
            titleView.setPaintFlags(titleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            descriptionView.setTextColor(subColor);

            Drawable d = getResources().getDrawable(R.drawable.account_check).mutate();
            d.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            checkBox.setImageDrawable(d);
        } else {
            titleView.setTextColor(textColor);
            titleView.setPaintFlags(titleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            descriptionView.setTextColor(subColor);

            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setStroke(dp(2), subColor);
            circle.setSize(dp(22), dp(22));
            checkBox.setImageDrawable(circle);
        }

        menuButton.setColorFilter(new PorterDuffColorFilter(subColor, PorterDuff.Mode.MULTIPLY));
        dividerPaint.setColor(Theme.getColor(Theme.key_divider));
    }

    public void setOnCheckClick(View.OnClickListener l) {
        checkBox.setOnClickListener(l);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = todo != null && !TextUtils.isEmpty(todo.description) ? 70 : 56;
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(height), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider) {
            canvas.drawLine(dp(56), getHeight() - 1, getWidth() - dp(16), getHeight() - 1, dividerPaint);
        }
    }
}
