package com.liuguang.cast.tv;

import static com.liuguang.cast.shared.Theme.*;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.liuguang.cast.shared.Wire;

public final class TvActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout base;
    private SurfaceView video;
    private TextView hint;
    private TvReceiverService receiver;
    private Surface attachedSurface;
    private Runnable releaseBack;
    private boolean playing, remote, fillPicture;
    private int width, height, pictureScale = Wire.DEFAULT_SCALE;
    private final Runnable hideHint = () -> hint.setVisibility(View.GONE);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        buildPlayer();
        fullScreen();
    }

    @Override protected void onStart() {
        super.onStart();
        receiver = TvReceiverService.get();
        if (receiver == null || !receiver.attachPlayer(this)) {
            receiver = null;
            finish();
            return;
        }
        attachSurface(video.getHolder());
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
    }

    @Override protected void onStop() {
        ui.removeCallbacks(hideHint);
        clearBackCallback();
        if (receiver != null) {
            detachSurface();
            receiver.detachPlayer(this);
            receiver = null;
        }
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) fullScreen();
    }

    @Override public void onBackPressed() { disconnect(); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (receiver == null) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN && !event.isCanceled()
                && event.getRepeatCount() == 0;
        boolean up = event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
            if (up) disconnect();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (up && playing) liveNote();
            return true;
        }
        if (RemoteMenuKey.matches(key)) {
            if (RemoteMenuKey.isPress(event) && playing) togglePictureFill();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down && playing) moveVideo(key == KeyEvent.KEYCODE_DPAD_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void buildPlayer() {
        base = new FrameLayout(this);
        base.setBackgroundColor(Color.BLACK);
        video = new SurfaceView(this);
        base.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { attachSurface(holder); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) { detachSurface(); }
        });
        base.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) layoutVideo();
        });
        hint = text(this, "", 16, TEXT, true);
        hint.setPadding(dp(this, 16), dp(this, 9), dp(this, 16), dp(this, 9));
        hint.setBackground(box(this, SIDEBAR, CARD_RADIUS, BORDER));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        overlay.topMargin = dp(this, 21);
        base.addView(hint, overlay);
        setContentView(base);
    }

    private void fullScreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void attachSurface(SurfaceHolder holder) {
        Surface surface = holder.getSurface();
        if (receiver == null || !surface.isValid() || surface == attachedSurface) return;
        detachSurface();
        attachedSurface = surface;
        receiver.attachSurface(this, surface);
    }

    private void detachSurface() {
        if (receiver == null || attachedSurface == null) return;
        Surface surface = attachedSurface;
        attachedSurface = null;
        receiver.detachSurface(this, surface);
    }

    private void disconnect() {
        if (receiver != null) receiver.disconnect();
        else finish();
    }

    @TargetApi(33)
    private void registerBackCallback() {
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        OnBackInvokedCallback callback = this::disconnect;
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        releaseBack = () -> dispatcher.unregisterOnBackInvokedCallback(callback);
    }

    private void clearBackCallback() {
        if (releaseBack == null) return;
        releaseBack.run();
        releaseBack = null;
    }

    void showConnection(boolean allowRemote) {
        playing = false;
        remote = allowRemote;
        fillPicture = false;
        pictureScale = Wire.DEFAULT_SCALE;
        width = 0;
        height = 0;
        video.setLayoutParams(new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        notice("已连接 · 正在接收手机画面", 0);
    }

    void setVideoSize(int width, int height) {
        this.width = width;
        this.height = height;
        layoutVideo();
    }

    void setPictureFill(boolean enabled) {
        fillPicture = enabled;
        layoutVideo();
    }

    void setPictureScale(int scale) {
        pictureScale = scale;
        layoutVideo();
    }

    void firstFrame() {
        playing = true;
        liveNote();
    }

    void endConnection() {
        playing = false;
        remote = false;
        ui.removeCallbacks(hideHint);
        hint.setVisibility(View.GONE);
        finish();
    }

    private void togglePictureFill() {
        setPictureFill(!fillPicture);
        receiver.pictureFillChanged(fillPicture);
        String mode = "画面 · 完整显示";
        if (fillPicture) mode = isLandscapeVideo() ? "画面 · 等比铺满" : "等比铺满 · 横屏时生效";
        notice(mode, 1800);
    }

    private void moveVideo(boolean next) {
        if (!remote) notice("请在手机上为本次投屏开启遥控器控制", 2600);
        else if (receiver.remote(next ? Wire.NEXT_VIDEO : Wire.PREVIOUS_VIDEO)) {
            notice(next ? "↓   下一条" : "↑   上一条", 1050);
        }
    }

    private void liveNote() {
        notice((remote ? "↑ 上一条   ↓ 下一条   ·   " : "")
                + "菜单键切换画面   ·   返回键结束投屏", 4000);
    }

    private void notice(String message, long time) {
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideHint);
        if (time > 0) ui.postDelayed(hideHint, time);
    }

    private boolean isLandscapeVideo() { return width > height; }

    private void layoutVideo() {
        if (width <= 0 || height <= 0 || base.getWidth() <= 0 || base.getHeight() <= 0) return;
        double horizontal = (double) base.getWidth() / width;
        double vertical = (double) base.getHeight() / height;
        double scale = fillPicture && isLandscapeVideo()
                ? Math.max(horizontal, vertical) : Math.min(horizontal, vertical);
        scale *= (double) pictureScale / Wire.DEFAULT_SCALE;
        video.setLayoutParams(new FrameLayout.LayoutParams((int) Math.round(scale * width),
                (int) Math.round(scale * height), Gravity.CENTER));
    }
}
