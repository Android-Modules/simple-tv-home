package com.liuguang.cast.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ApkInstallResult extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { ApkInstaller.result(intent); }
}
