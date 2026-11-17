package com.liuguang.cast.tv;

import static com.liuguang.cast.shared.Theme.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class FilePreview extends LinearLayout {
    private final Handler main = new Handler(Looper.getMainLooper());
    private VideoView media;
    private TextView hint;
    private boolean released;

    static boolean supported(String type) {
        return type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")
                || type.startsWith("text/");
    }

    FilePreview(Context context, File file, Runnable close) {
        super(context);
        setOrientation(VERTICAL);
        int pad = dp(context, 22);
        setPadding(pad, pad, pad, pad);
        setBackgroundColor(CANVAS);
        LinearLayout header = row(context);
        TextView back = action(context, "返回文件", false);
        back.setOnClickListener(v -> close.run());
        header.addView(back);
        TextView title = text(context, file.getName(), 20, TEXT, true);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(0, -2, 1);
        caption.leftMargin = dp(context, 16);
        header.addView(title, caption);
        addView(header, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout content = new FrameLayout(context);
        content.setBackground(box(context, CARD, CARD_RADIUS, BORDER));
        LinearLayout.LayoutParams section = new LinearLayout.LayoutParams(-1, 0, 1);
        section.topMargin = dp(context, 14);
        addView(content, section);
        hint = text(context, "正在打开…", 15, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        content.addView(hint, new FrameLayout.LayoutParams(-1, -1));
        String type = TvFiles.mime(file);
        if (type.startsWith("image/")) showImage(file, content);
        else if (type.startsWith("text/")) showText(file, content);
        else showMedia(file, content, type.startsWith("audio/"));
        post(() -> { if (!released) (media == null ? back : media).requestFocus(); });
    }

    private void showImage(File file, FrameLayout content) {
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(image, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        new Thread(() -> {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            int sampling = 1;
            while (Math.max(options.outWidth, options.outHeight) / sampling > 2560) sampling *= 2;
            options.inSampleSize = sampling;
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            main.post(() -> {
                if (released) { if (bitmap != null) bitmap.recycle(); return; }
                if (bitmap == null) hint.setText("无法预览这个图片");
                else { content.removeView(hint); image.setImageBitmap(bitmap); }
            });
        }, "tv-image-preview").start();
    }

    private void showText(File file, FrameLayout content) {
        byte[] preview = new byte[32768];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (offset < preview.length) {
                int count = input.read(preview, offset, preview.length - offset);
                if (count <= 0) break;
                offset += count;
            }
        } catch (IOException e) {
            hint.setText("无法读取这个文件");
            return;
        }
        TextView text = text(getContext(), new String(preview, 0, offset, StandardCharsets.UTF_8)
                + (file.length() > offset ? "\n\n… 仅显示文件开头" : ""), 17, TEXT, false);
        int padding = dp(getContext(), 18);
        text.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFocusable(true);
        scroll.addView(text, new ScrollView.LayoutParams(-1, -2));
        content.removeView(hint);
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showMedia(File file, FrameLayout content, boolean audio) {
        content.setBackgroundColor(SIDEBAR);
        hint.setTextColor(TEXT);
        media = new VideoView(getContext());
        media.setFocusable(true);
        content.addView(media, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        MediaController controller = new MediaController(getContext());
        media.setMediaController(controller);
        hint.setText(audio ? "音频  ·  按确定键播放或暂停" : "");
        media.setOnErrorListener((player, what, extra) -> {
            hint.setText("电视无法播放这个媒体文件");
            return true;
        });
        media.setOnCompletionListener(p -> hint.setText("播放结束  ·  按确定键重播"));
        media.setVideoPath(file.getAbsolutePath());
        media.start();
        media.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (media != null && media.hasFocus()
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (media.isPlaying()) media.pause();
                else media.start();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    void release() {
        released = true;
        if (media != null) media.stopPlayback();
    }
}
