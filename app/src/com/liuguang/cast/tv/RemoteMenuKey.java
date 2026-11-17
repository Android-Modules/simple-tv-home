package com.liuguang.cast.tv;

import android.os.Build;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

public final class RemoteMenuKey {
    // Xiaomi TV intercepts Menu and replays it from the virtual keyboard with these flags.
    private static final int XIAOMI_MENU_REPLAY = 0x7531;

    private RemoteMenuKey() {}

    public static boolean matches(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_TV_CONTENTS_MENU
                || keyCode == KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU;
    }

    public static boolean isPress(KeyEvent event) {
        return matches(event.getKeyCode()) && event.getAction() == KeyEvent.ACTION_UP
                && (!event.isCanceled() || isXiaomiMenuReplay(event));
    }

    private static boolean isXiaomiMenuReplay(KeyEvent event) {
        return event.getKeyCode() == KeyEvent.KEYCODE_MENU
                && event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD
                && event.getFlags() == XIAOMI_MENU_REPLAY
                && "xiaomi".equalsIgnoreCase(Build.MANUFACTURER);
    }
}
