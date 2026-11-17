package com.liuguang.cast.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.liuguang.cast.shared.DiscoveryPacket;
import com.liuguang.cast.shared.LanIpv4;
import com.liuguang.cast.shared.TvTools;

import java.io.ByteArrayOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class TvDetails {
    static String name() {
        return DiscoveryPacket.RECEIVER_NAME;
    }

    static TvTools.Info info(Context context) {
        DisplayMetrics display = new DisplayMetrics();
        context.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(display);
        StatFs storage = new StatFs(context.getFilesDir().getAbsolutePath());
        PackageManager pm = context.getPackageManager();
        return new TvTools.Info(name(), Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT, display.widthPixels, display.heightPixels,
                storage.getAvailableBytes(), storage.getTotalBytes(), version(pm, context.getPackageName()));
    }

    static List<TvTools.App> apps(Context context, boolean withIcons) {
        PackageManager pm = context.getPackageManager();
        LinkedHashMap<String, ApplicationInfo> found = launcherApps(pm);
        List<TvTools.App> result = new ArrayList<>();
        for (ApplicationInfo app : found.values()) {
            CharSequence name = app.loadLabel(pm);
            result.add(new TvTools.App(name == null ? app.packageName : limit(name.toString()),
                    app.packageName, version(pm, app.packageName), null));
        }
        Collator order = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        Collections.sort(result, (a, b) -> order.compare(a.label(), b.label()));
        List<TvTools.App> shown = result.subList(0, Math.min(result.size(), TvTools.MAX_APPS));
        if (withIcons) for (int i = 0; i < shown.size(); i++) {
            TvTools.App app = shown.get(i);
            shown.set(i, new TvTools.App(app.label(), app.packageName(), app.version(),
                    icon(found.get(app.packageName()), pm)));
        }
        return shown;
    }

    static LinkedHashMap<String, ApplicationInfo> launcherApps(PackageManager pm) {
        LinkedHashMap<String, ApplicationInfo> found = new LinkedHashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo entry : pm.queryIntentActivities(launcher, 0)) {
                if (entry.activityInfo == null) continue;
                ApplicationInfo app = entry.activityInfo.applicationInfo;
                if (app != null) found.put(app.packageName, app);
            }
        }
        return found;
    }

    private static byte[] icon(ApplicationInfo app, PackageManager pm) {
        Bitmap image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        try {
            Drawable drawable = app.loadIcon(pm);
            if (drawable == null) return null;
            drawable.setBounds(0, 0, 64, 64);
            drawable.draw(new Canvas(image));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
            return image.compress(Bitmap.CompressFormat.PNG, 100, bytes) ? bytes.toByteArray() : null;
        } catch (RuntimeException e) {
            return null;
        } finally { image.recycle(); }
    }

    static String limit(String text) { return text.length() > 200 ? text.substring(0, 200) : text; }

    static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String version(PackageManager pm, String id) {
        try {
            String version = pm.getPackageInfo(id, 0).versionName;
            return version == null ? "" : limit(version);
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static String address(Context context) {
        List<LanIpv4> links = LanIpv4.links(context);
        return links.isEmpty() ? null : links.get(0).address().getHostAddress();
    }
}
