package dev.dalong.tvhome;

import android.app.Activity;
import android.os.Bundle;

public final class LauncherEntry extends Activity {
    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        startActivity(MainActivity.homeIntent(this));
        finishAndRemoveTask();
    }
}
