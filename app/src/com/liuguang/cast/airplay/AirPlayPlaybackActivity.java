package com.liuguang.cast.airplay;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.window.OnBackInvokedDispatcher;

public final class AirPlayPlaybackActivity extends Activity implements SurfaceHolder.Callback {
    private AirPlayReceiverService receiver;
    private FrameLayout frame;
    private SurfaceView surface;
    private int videoWidth = 1920;
    private int videoHeight = 1080;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        receiver = AirPlayReceiverService.current();
        if (receiver == null || !receiver.attachPlayer(this)) {
            finish();
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        frame.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        frame.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> fitVideo());
        setContentView(frame);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::endPlayback);
        }
    }

    void setVideoSize(int width, int height) {
        videoWidth = width;
        videoHeight = height;
        fitVideo();
    }

    private void fitVideo() {
        if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) return;
        float scale = Math.min(frame.getWidth() / (float) videoWidth, frame.getHeight() / (float) videoHeight);
        int width = Math.round(scale * videoWidth);
        int height = Math.round(scale * videoHeight);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (params.width != width || params.height != height) {
            surface.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        receiver.playerSurface(this, holder.getSurface());
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        receiver.playerSurface(this, null);
    }

    @Override public void onBackPressed() {
        endPlayback();
    }

    private void endPlayback() {
        if (receiver == null) finish();
        else receiver.disconnect(this);
    }

    @Override protected void onDestroy() {
        if (receiver != null) receiver.detachPlayer(this);
        super.onDestroy();
    }
}
