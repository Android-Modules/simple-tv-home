package dev.dalong.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.icu.util.Calendar;
import android.icu.util.ChineseCalendar;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HomeStatus extends LinearLayout {
    private static final long WEATHER_RETRY = 60_000L;
    private static final long LOCATION_INTERVAL = 6 * 60 * 60_000L;
    private static final long MAX_WEATHER_AGE = 12 * 60 * 60_000L;
    private static final int MUTED = 0xffb0b2ba;
    private static final String[] LUNAR_MONTHS = {
            "正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月"
    };
    private static final String[] LUNAR_DAY_DIGITS = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};

    private final Activity activity;
    private final SharedPreferences settings;
    private final ConnectivityManager connectivity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refreshWeather;
    private final TextView weather;
    private final TextView lunarDate;
    private final IconView wifi;
    private final IconView ethernet;
    private final IconView offline;
    private final BroadcastReceiver dateChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateLunarDate();
        }
    };
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    ui.post(HomeStatus.this::updateNetwork);
                }

                @Override
                public void onLost(Network network) {
                    ui.post(HomeStatus.this::updateNetwork);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    ui.post(HomeStatus.this::updateNetwork);
                }
            };
    private Weather.City city;
    private long nextRefresh;
    private long nextLocation;
    private Network activeNetwork;
    private boolean manualCity;
    private int generation;
    private boolean loading;
    private boolean visible;
    private boolean registered;
    private boolean closed;
    private AlertDialog cityDialog;

    HomeStatus(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        settings = activity.getSharedPreferences("weather", Context.MODE_PRIVATE);
        manualCity = settings.getBoolean("manual_city", false);
        if (settings.contains("city")) {
            city = new Weather.City(settings.getString("city", ""), "",
                    Double.longBitsToDouble(settings.getLong("latitude", 0)),
                    Double.longBitsToDouble(settings.getLong("longitude", 0)));
            long remaining = settings.contains("manual_city")
                    ? settings.getLong("next", 0) - System.currentTimeMillis() : 0;
            nextRefresh = SystemClock.elapsedRealtime() + Math.max(0, remaining);
        }
        connectivity = activity.getSystemService(ConnectivityManager.class);

        addView(new IconView(activity, IconView.Glyph.WEATHER, MUTED), new LayoutParams(dp(19), dp(19)));
        weather = new TextView(activity);
        weather.setTextColor(MUTED);
        weather.setTextSize(14);
        weather.setSingleLine(true);
        weather.setEllipsize(TextUtils.TruncateAt.START);
        weather.setMaxWidth(dp(200));
        addStatusView(weather, 5);

        FrameLayout network = new FrameLayout(activity);
        LayoutParams networkParams = new LayoutParams(dp(19), dp(19));
        networkParams.leftMargin = dp(14);
        addView(network, networkParams);
        wifi = addNetworkIcon(network, IconView.Glyph.WIFI, "Wi-Fi");
        ethernet = addNetworkIcon(network, IconView.Glyph.ETHERNET, "以太网");
        offline = addNetworkIcon(network, IconView.Glyph.OFFLINE, "未连接网络");

        addStatusView(clock("M月d日 EEE", 14), 14);
        lunarDate = new TextView(activity);
        lunarDate.setTextColor(MUTED);
        lunarDate.setTextSize(14);
        lunarDate.setSingleLine(true);
        addStatusView(lunarDate, 10);
        addStatusView(clock("HH:mm", 20), 10);
        updateLunarDate();
        updateWeatherText();
        updateNetwork();
    }

    private void addStatusView(View view, int gap) {
        LayoutParams params = new LayoutParams(-2, -2);
        params.leftMargin = dp(gap);
        addView(view, params);
    }

    private TextClock clock(String format, int size) {
        TextClock clock = new TextClock(activity);
        clock.setFormat12Hour(format);
        clock.setFormat24Hour(format);
        clock.setTextColor(MUTED);
        clock.setTextSize(size);
        clock.setSingleLine(true);
        return clock;
    }

    private void updateLunarDate() {
        ChineseCalendar date = new ChineseCalendar();
        String month = LUNAR_MONTHS[date.get(Calendar.MONTH)];
        String leap = date.get(Calendar.IS_LEAP_MONTH) == 1 ? "闰" : "";
        lunarDate.setText("农历" + leap + month + lunarDay(date.get(Calendar.DAY_OF_MONTH)));
    }

    private static String lunarDay(int day) {
        switch (day) {
            case 10: return "初十";
            case 20: return "二十";
            case 30: return "三十";
            default:
                String prefix = day < 10 ? "初" : day < 20 ? "十" : "廿";
                return prefix + LUNAR_DAY_DIGITS[day % 10 - 1];
        }
    }

    private IconView addNetworkIcon(FrameLayout parent, IconView.Glyph glyph, String description) {
        IconView icon = new IconView(activity, glyph, MUTED);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        icon.setContentDescription(description);
        parent.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        return icon;
    }

    private void updateNetwork() {
        if (closed) {
            return;
        }
        Network active = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
        boolean wired = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        boolean wireless = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        wifi.setVisibility(wireless && !wired ? VISIBLE : GONE);
        ethernet.setVisibility(wired ? VISIBLE : GONE);
        offline.setVisibility(capabilities == null ? VISIBLE : GONE);
        if (!Objects.equals(activeNetwork, active)) {
            activeNetwork = active;
            generation++;
            nextLocation = 0;
            if (settings.getLong("next", 0) <= System.currentTimeMillis()) {
                nextRefresh = 0;
            }
        }
        refreshWeather();
    }

    void start() {
        if (!visible) {
            IntentFilter changes = new IntentFilter(Intent.ACTION_DATE_CHANGED);
            changes.addAction(Intent.ACTION_TIME_CHANGED);
            changes.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(dateChanges, changes, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(dateChanges, changes);
            }
        }
        visible = true;
        if (connectivity != null && !registered) {
            connectivity.registerDefaultNetworkCallback(networkCallback);
            registered = true;
        }
        updateLunarDate();
        updateNetwork();
    }

    void stop() {
        if (visible) {
            activity.unregisterReceiver(dateChanges);
        }
        visible = false;
        if (registered) {
            connectivity.unregisterNetworkCallback(networkCallback);
            registered = false;
        }
        ui.removeCallbacks(refresh);
    }

    void dismissCityDialog() {
        if (cityDialog != null) {
            cityDialog.dismiss();
            cityDialog = null;
        }
    }

    void close() {
        stop();
        closed = true;
        dismissCityDialog();
        ui.removeCallbacksAndMessages(null);
        requests.shutdownNow();
    }

    private void scheduleRefresh() {
        ui.removeCallbacks(refresh);
        if (visible && activeNetwork != null) {
            long when = city == null ? Long.MAX_VALUE : nextRefresh;
            if (!manualCity) {
                when = Math.min(when, nextLocation);
            }
            if (when != Long.MAX_VALUE) {
                ui.postDelayed(refresh, Math.max(0, when - SystemClock.elapsedRealtime()));
            }
        }
    }

    private void refreshWeather() {
        if (!visible || loading || activeNetwork == null) {
            updateWeatherText();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean locate = !manualCity && now >= nextLocation;
        boolean forecast = now >= nextRefresh;
        if (!locate && (!forecast || city == null)) {
            scheduleRefresh();
            return;
        }
        Weather.City selected = city;
        int requestGeneration = generation;
        loading = true;
        updateWeatherText();
        requests.execute(() -> {
            Weather.City detected = null;
            if (locate) {
                detected = Weather.tvCity(activity.getContentResolver());
                if (detected == null) {
                    try {
                        detected = Weather.networkCity();
                    } catch (IOException | JSONException error) {
                        Log.d("SimpleTvHome", "Automatic city lookup unavailable: "
                                + error.getClass().getSimpleName());
                    }
                }
            }
            Weather.City target = detected == null ? selected : detected;
            boolean fetch = target != null && (forecast || !target.matches(selected));
            Weather.Reading reading = null;
            if (fetch) {
                try {
                    reading = Weather.current(target);
                } catch (IOException | JSONException error) {
                    reading = Weather.tvWeather(activity.getContentResolver(), target);
                    if (reading == null) {
                        Log.d("SimpleTvHome", "Weather services unavailable: "
                                + error.getClass().getSimpleName());
                    }
                }
            }
            Weather.City found = detected;
            Weather.Reading result = reading;
            ui.post(() -> finishRefresh(requestGeneration, locate, found, fetch, result));
        });
    }

    private void finishRefresh(int requestGeneration, boolean located, Weather.City found,
            boolean forecasted, Weather.Reading result) {
        if (closed) {
            return;
        }
        loading = false;
        if (generation != requestGeneration) {
            refreshWeather();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (located) {
            nextLocation = now + (found == null ? WEATHER_RETRY : LOCATION_INTERVAL);
            if (found != null && !found.matches(city)) {
                saveCity(found);
            }
        }
        if (forecasted) {
            long interval = result == null ? WEATHER_RETRY : result.refreshAfter;
            nextRefresh = now + interval;
            if (result != null) {
                long wallTime = System.currentTimeMillis();
                settings.edit().putString("reading", result.text).putLong("observed", wallTime)
                        .putLong("next", wallTime + interval).apply();
            }
        }
        updateWeatherText();
        scheduleRefresh();
    }

    private void updateWeatherText() {
        if (city == null) {
            weather.setText(activeNetwork == null ? "联网后显示天气" : loading ? "正在识别城市" : "城市暂未识别");
            return;
        }
        long observed = settings.getLong("observed", 0);
        long now = System.currentTimeMillis();
        long age = now - observed;
        String reading = age >= 0 && age < MAX_WEATHER_AGE ? settings.getString("reading", "") : "";
        if (reading.isEmpty()) {
            weather.setText(city.name + (loading ? " · 更新天气中" : " · 天气暂不可用"));
        } else {
            weather.setText(city.name + " " + reading + (now > settings.getLong("next", 0) ? " · 缓存" : ""));
        }
    }

    void chooseCity() {
        FrameLayout form = new FrameLayout(activity);
        form.setPadding(dp(18), 0, dp(18), 0);
        EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        search.setHint("城市名，例如南京");
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        form.addView(search, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("天气城市")
                .setMessage("自动模式优先使用电视识别的城市，也可搜索城市手动指定。\n"
                        + "定位备用 ipwho.is，天气 Open-Meteo / MET Norway。\n"
                        + "https://creativecommons.org/licenses/by/4.0/")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索", null);
        builder.setNeutralButton("自动识别", (window, button) -> useAutomaticCity());
        AlertDialog dialog = builder.create();
        cityDialog = dialog;
        dialog.show();
        TextView attribution = dialog.findViewById(android.R.id.message);
        if (attribution != null) {
            Linkify.addLinks(attribution, Linkify.WEB_URLS);
            attribution.setFocusable(false);
        }
        Runnable submit = () -> search(dialog, search);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> submit.run());
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            submit.run();
            return true;
        });
        search.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void search(AlertDialog dialog, EditText search) {
        View submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (!submit.isEnabled()) {
            return;
        }
        String name = search.getText().toString().trim();
        if (name.length() < 2) {
            search.setError("请输入至少两个字符");
            return;
        }
        submit.setEnabled(false);
        dialog.setMessage("正在查找城市…");
        requests.execute(() -> {
            List<Weather.City> cities = null;
            try {
                cities = Weather.search(name);
            } catch (IOException | JSONException ignored) {
                // Report the network failure in the active city picker.
            }
            List<Weather.City> result = cities;
            ui.post(() -> showCities(dialog, result));
        });
    }

    private void showCities(AlertDialog dialog, List<Weather.City> cities) {
        if (closed || dialog != cityDialog || !dialog.isShowing()) {
            return;
        }
        if (cities == null || cities.isEmpty()) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            dialog.setMessage(cities == null ? "暂时无法连接 Open-Meteo，可以重试"
                    : "没有找到城市，可以试试其他写法");
            return;
        }
        List<String> names = new ArrayList<>();
        for (Weather.City item : cities) {
            names.add(item.displayName());
        }
        dialog.dismiss();
        cityDialog = new AlertDialog.Builder(activity)
                .setTitle("选择天气城市")
                .setItems(names.toArray(new String[0]), (window, index) -> selectCity(cities.get(index)))
                .setNegativeButton("返回搜索", (window, button) -> chooseCity())
                .show();
    }

    private void selectCity(Weather.City selected) {
        generation++;
        manualCity = true;
        if (selected.matches(city)) {
            settings.edit().putBoolean("manual_city", true).apply();
        } else {
            saveCity(selected);
        }
        nextRefresh = 0;
        refreshWeather();
    }

    private void saveCity(Weather.City selected) {
        city = selected;
        settings.edit().putString("city", selected.name)
                .putBoolean("manual_city", manualCity)
                .putLong("latitude", Double.doubleToLongBits(selected.latitude))
                .putLong("longitude", Double.doubleToLongBits(selected.longitude))
                .remove("reading").remove("observed").remove("next").apply();
    }

    private void useAutomaticCity() {
        generation++;
        manualCity = false;
        settings.edit().putBoolean("manual_city", false).apply();
        nextLocation = 0;
        nextRefresh = 0;
        refreshWeather();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
