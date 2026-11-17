package dev.dalong.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import com.liuguang.cast.tv.RemoteMenuKey;

public final class HomeService extends AccessibilityService {
    static final String SHOW_HOME = "dev.dalong.tvhome.SHOW_HOME";
    static final String STOCK_PACKAGE = "com.mitv.tvhome";
    static final String STOCK_MAIN = STOCK_PACKAGE + ".MainActivityUserMode";
    private static final String TAG = "SimpleTvHome";
    private static final long HOME_HOLD = Math.max(500, ViewConfiguration.getLongPressTimeout());

    private static HomeService connected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openRecents = this::showRecents;
    private long homeDownTime = -1;
    private boolean heldHome;
    private boolean systemHomeAfterRecents;
    private long lastShow;
    private boolean screenReceiverRegistered;
    private boolean wentToSleep;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                systemHomeAfterRecents = false;
                cancelHomePress();
                wentToSleep = true;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && wentToSleep) {
                wentToSleep = false;
                handler.postDelayed(() -> showHome("wake"), 450);
            }
        }
    };

    static boolean isConnected() {
        return connected != null;
    }

    static boolean openRecentTasks() {
        return connected != null && connected.showSystemRecents();
    }

    @Override
    protected void onServiceConnected() {
        connected = this;
        if (!screenReceiverRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
            screenReceiverRegistered = true;
        }
        SharedPreferences bootState = getSharedPreferences("home-service", MODE_PRIVATE);
        int bootCount = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (bootCount >= 0 && bootCount != bootState.getInt("boot_count", -1)) {
            bootState.edit().putInt("boot_count", bootCount).apply();
            handler.postDelayed(() -> showHome("boot"), 1200);
        }
        Log.i(TAG, "Home service connected");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (RemoteMenuKey.matches(event.getKeyCode())) {
            return MainActivity.routeMenuKey(event);
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                cancelHomePress();
                homeDownTime = event.getDownTime();
                handler.postAtTime(openRecents, homeDownTime + HOME_HOLD);
            } else if (homeDownTime == event.getDownTime() && event.isLongPress()) {
                showRecents();
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && homeDownTime == event.getDownTime()) {
            if (!event.isCanceled() && !heldHome) {
                if (event.getEventTime() - homeDownTime >= HOME_HOLD) {
                    showRecents();
                } else {
                    onHomeTap();
                }
            }
            cancelHomePress();
        }
        return true;
    }

    private void cancelHomePress() {
        handler.removeCallbacks(openRecents);
        homeDownTime = -1;
        heldHome = false;
    }

    private void closeSystemPanel() {
        if (MainActivity.isSystemPanelInFront()) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private void onHomeTap() {
        if (systemHomeAfterRecents) {
            systemHomeAfterRecents = false;
            long requestedAt = SystemClock.uptimeMillis();
            // On Mi TV, the system Home action dismisses the recent tasks overlay.
            if (performGlobalAction(GLOBAL_ACTION_HOME)) {
                handler.postDelayed(() -> {
                    if (lastShow < requestedAt) {
                        showHome("home after recents");
                    }
                }, 350);
                return;
            }
        }
        closeSystemPanel();
        showHome("key");
    }

    private void showRecents() {
        if (homeDownTime < 0 || heldHome) {
            return;
        }
        heldHome = true;
        if (!showSystemRecents()) {
            Toast.makeText(this, "此系统暂无法打开最近任务", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "System recents action unavailable");
        } else {
            Log.i(TAG, "Show recent tasks from long Home press");
        }
    }

    private boolean showSystemRecents() {
        closeSystemPanel();
        systemHomeAfterRecents = performGlobalAction(GLOBAL_ACTION_RECENTS);
        return systemHomeAfterRecents;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || !TextUtils.equals(STOCK_PACKAGE, event.getPackageName())
                || !TextUtils.equals(STOCK_MAIN, event.getClassName())) {
            return;
        }
        if (SystemClock.uptimeMillis() - lastShow > 350) {
            showHome("stock window");
        }
    }

    private void showHome(String cause) {
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) {
            return;
        }
        lastShow = SystemClock.uptimeMillis();
        try {
            startActivity(MainActivity.homeIntent(this));
            Log.i(TAG, "Show home from " + cause);
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w(TAG, "Could not show home", error);
        }
    }

    @Override
    public void onInterrupt() {
        cancelHomePress();
    }

    @Override
    public void onDestroy() {
        if (connected == this) {
            connected = null;
        }
        handler.removeCallbacksAndMessages(null);
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver);
        }
        super.onDestroy();
    }
}
