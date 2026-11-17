package com.liuguang.cast.airplay;

import static com.liuguang.cast.shared.Theme.*;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AirPlayPairPrompt {
    private AirPlayPairPrompt() {}

    static Dialog show(Context service, Activity activity, String name, String pin) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        Context displayContext = activity == null ? service : activity;
        if (activity == null && Build.VERSION.SDK_INT >= 30) {
            Display display = service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) throw new WindowManager.InvalidDisplayException("Default TV display unavailable");
            displayContext = service.createDisplayContext(display).createWindowContext(type, null);
        }
        Context context = new ContextThemeWrapper(displayContext, android.R.style.Theme_Material_Dialog_Alert);
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = card(context, 26);
        content.setBackground(box(context, CARD, CARD_RADIUS, BORDER));
        TextView title = text(context, "投屏验证", 24, TEXT, true);
        title.setGravity(Gravity.CENTER);
        add(content, title, 0);
        TextView instructions = text(context, name + " · 在 Mac 上输入投屏码", 15, MUTED, false);
        instructions.setGravity(Gravity.CENTER);
        add(content, instructions, 10);

        LinearLayout digits = row(context);
        digits.setGravity(Gravity.CENTER);
        for (int i = 0; i < pin.length(); i++) {
            TextView digit = text(context, pin.substring(i, i + 1), 30, TEXT, true);
            digit.setGravity(Gravity.CENTER);
            digit.setBackground(box(context, CANVAS, 10, BORDER));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 56), dp(context, 64));
            if (i > 0) params.leftMargin = dp(context, 10);
            digits.addView(digit, params);
        }
        add(content, digits, 18);
        TextView close = action(context, "关闭", true);
        close.setOnClickListener(view -> dialog.dismiss());
        add(content, close, 20);

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (activity == null) window.setType(type);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.45f);
        dialog.show();
        window.setLayout(dp(context, 396), WindowManager.LayoutParams.WRAP_CONTENT);
        close.requestFocus();
        return dialog;
    }

    private static void add(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), gap);
        parent.addView(view, params);
    }
}
