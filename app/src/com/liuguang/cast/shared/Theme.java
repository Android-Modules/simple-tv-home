package com.liuguang.cast.shared;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Theme {
    public static final int CANVAS = 0xff191a1d, SIDEBAR = 0xff151619, CARD = 0xff27292e;
    public static final int TEXT = 0xfff5f5f6, MUTED = 0xffb0b2ba, BORDER = 0xff41434b;
    public static final int ACCENT = 0xff8ac6ff, ACCENT_SURFACE = 0xff263746;
    public static final int GREEN = 0xff9fdeb6, GREEN_SURFACE = 0xff243a2d;
    public static final int WARNING = 0xffffc298, WARNING_SURFACE = 0xff442e25;
    public static final int CARD_RADIUS = 13;

    private static final int ON_LIGHT = 0xff18191c, ACTION = 0xff2e3036, FOCUS = 0xff373a42;
    private static final int ACTION_RADIUS = 10, TAB_RADIUS = 9, RIPPLE = 0x20f5f5f6;

    private Theme() {}

    public static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable box(Context context, int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(context, radius));
        if (stroke != 0) d.setStroke(dp(context, 1), stroke);
        return d;
    }

    public static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    public static LinearLayout column(Context c) {
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    public static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    public static LinearLayout card(Context c, int padding) {
        LinearLayout card = column(c);
        card.setBackground(box(c, CARD, CARD_RADIUS, 0));
        int p = dp(c, padding);
        card.setPadding(p, p, p, p);
        return card;
    }

    public static TextView tag(Context c, String value, int color, int fill) {
        TextView t = text(c, value, 12, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        t.setBackground(box(c, fill, ACTION_RADIUS, 0));
        return t;
    }

    private static TextView control(Context c, String label) {
        TextView control = text(c, label, 14, TEXT, true);
        control.setGravity(Gravity.CENTER);
        control.setMinHeight(dp(c, 46));
        control.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        control.setClickable(true);
        control.setFocusable(true);
        control.setDefaultFocusHighlightEnabled(false);
        return control;
    }

    public static TextView action(Context c, String label, boolean primary) {
        TextView button = control(c, label);
        int[] disabled = {-android.R.attr.state_enabled};
        int[] focused = {android.R.attr.state_focused};
        StateListDrawable background = new StateListDrawable();
        background.addState(disabled, box(c, CANVAS, ACTION_RADIUS, 0));
        GradientDrawable focus = box(c, FOCUS, ACTION_RADIUS, 0);
        focus.setStroke(dp(c, 2), TEXT);
        background.addState(focused, focus);
        background.addState(new int[] {}, box(c, primary ? ACCENT : ACTION, ACTION_RADIUS, 0));
        button.setTextColor(new ColorStateList(new int[][] {disabled, focused, {}},
                new int[] {MUTED, TEXT, primary ? ON_LIGHT : TEXT}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), background, null));
        return button;
    }

    public static TextView tab(Context c, String label) {
        TextView tab = control(c, label);
        int[] selected = {android.R.attr.state_selected};
        int[] focused = {android.R.attr.state_focused};
        StateListDrawable background = new StateListDrawable();
        background.addState(focused, box(c, TEXT, TAB_RADIUS, 0));
        background.addState(selected, box(c, ACTION, TAB_RADIUS, 0));
        background.addState(new int[] {}, box(c, SIDEBAR, TAB_RADIUS, 0));
        tab.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), background, null));
        tab.setTextColor(new ColorStateList(new int[][] {focused, selected, {}},
                new int[] {ON_LIGHT, TEXT, MUTED}));
        return tab;
    }

    public static void line(LinearLayout parent, int top) {
        View line = new View(parent.getContext());
        line.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(parent.getContext(), 1));
        params.topMargin = dp(parent.getContext(), top);
        parent.addView(line, params);
    }
}
