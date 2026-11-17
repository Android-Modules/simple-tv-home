package com.liuguang.cast.tv;

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

import com.liuguang.cast.shared.Pairing;

import dev.dalong.tvhome.R;

final class TvPairPrompt {
    final Activity host;
    final Dialog dialog;
    private final TextView[] digits = new TextView[Pairing.SHORT_CODE_LENGTH];
    private final TextView instructions;
    private final TextView close;

    TvPairPrompt(Context context, Activity host, Runnable refreshCode) {
        this.host = host;
        Context theme = promptContext(context, host);
        dialog = new Dialog(theme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout content = card(theme, 26);
        content.setBackground(box(theme, CARD, CARD_RADIUS, BORDER));
        TextView title = text(theme, TvDetails.name(), 23, TEXT, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title);
        instructions = text(theme, "", 15, MUTED, false);
        instructions.setGravity(Gravity.CENTER);
        add(content, instructions, 8);

        LinearLayout codes = row(theme);
        codes.setGravity(Gravity.CENTER);
        for (int i = 0; i < digits.length; i++) {
            TextView digit = text(theme, "", 29, TEXT, true);
            digit.setGravity(Gravity.CENTER);
            digit.setBackground(box(theme, CANVAS, 9, BORDER));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(theme, 53), dp(theme, 62));
            if (i > 0) layout.leftMargin = dp(theme, 9);
            codes.addView(digit, layout);
            digits[i] = digit;
        }
        add(content, codes, 18);

        LinearLayout actions = row(theme);
        TextView refresh = action(theme, "更新投屏码", false);
        refresh.setOnClickListener(v -> refreshCode.run());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        close = action(theme, "关闭", true);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLayout = new LinearLayout.LayoutParams(0, -2, 1);
        closeLayout.leftMargin = dp(theme, 12);
        actions.addView(close, closeLayout);
        add(content, actions, 20);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (host == null) window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.45f);
    }

    void update(TvServer.PairCode code, boolean locked) {
        instructions.setText(locked ? "配对尝试过多，请先更新投屏码" : "在手机上输入以下四位投屏码");
        instructions.setTextColor(locked ? WARNING : MUTED);
        for (int i = 0; i < digits.length; i++) {
            digits[i].setText(code.digits().substring(i, i + 1));
        }
    }

    void show() {
        dialog.show();
        Window window = dialog.getWindow();
        window.setLayout(dp(dialog.getContext(), 372), WindowManager.LayoutParams.WRAP_CONTENT);
        close.requestFocus();
    }

    private static Context promptContext(Context context, Activity host) {
        if (host != null) return host;
        if (Build.VERSION.SDK_INT >= 30) {
            Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) throw new WindowManager.InvalidDisplayException("Default display is unavailable");
            context = context.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        return new ContextThemeWrapper(context, R.style.TvTheme);
    }

    private static void add(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(parent.getContext(), gap);
        parent.addView(view, layout);
    }
}
