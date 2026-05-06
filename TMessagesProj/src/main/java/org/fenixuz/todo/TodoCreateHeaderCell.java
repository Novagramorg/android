package org.fenixuz.todo;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public class TodoCreateHeaderCell extends FrameLayout {

    public TodoCreateHeaderCell(@NonNull Context context) {
        super(context);

        setBackground(Theme.getSelectorDrawable(false));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.menu_topic_add);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_actionBackground), PorterDuff.Mode.MULTIPLY));
        addView(icon, LayoutHelper.createFrame(28, 28, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setTextColor(Theme.getColor(Theme.key_chats_actionBackground));
        text.setText(LocaleController.getString(R.string.FenixTodoCreate));
        addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 56, 0, 16, 0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
    }
}
