package dev.dalong.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int MINE = 0;
    private static final int INPUTS = 1;
    private static final int APPS = 2;
    private static final String[] PAGE_NAMES = {"我的", "信号源", "应用"};
    private static final int APP_COLUMNS = 6;

    private static final int BACKGROUND = 0xff191a1d;
    private static final int SIDEBAR = 0xff151619;
    private static final int CARD = 0xff27292e;
    private static final int WHITE = 0xfff5f5f6;
    private static final int MUTED = 0xffb0b2ba;
    private static final int ON_LIGHT = 0xff18191c;
    private static final int NAV_SELECTED = 0xff2e3036;
    private static final int MOVE_ACCENT = 0xff8ac6ff;

    private static final String STOCK_PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String TV_SETTINGS = "com.xiaomi.mitv.settings";
    private static final String SMART_PANEL = "com.xiaomi.mitv.smartpanel";
    private static final String SMART_HOME = "com.xiaomi.smarthome.tv";
    private static final String DEFAULT_HOME_PAGE = "default_home_page";
    private static MainActivity foreground;

    private final NavItem[] navigation = new NavItem[PAGE_NAMES.length];
    private final List<View> pageCards = new ArrayList<>();
    private final List<AppTile> appTiles = new ArrayList<>();
    private List<InstalledApp> appOrder = new ArrayList<>();
    private List<InstalledApp> orderBeforeMove;
    private LinearLayout body;
    private ScrollView scroll;
    private TextView title;
    private TextView sortMessage;
    private HomeStatus status;
    private Dialog quickMenu;
    private AlertDialog desktopSettings;
    private boolean systemPanelOpened;
    private int currentPage = -1;
    private int movingApp = -1;
    private boolean sorting;

    static Intent homeIntent(Context context) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setContentView(createLayout());
        int defaultPage = defaultHomePage();
        focusPage(savedState == null ? defaultPage : savedState.getInt("page", defaultPage));
        if (getIntent().hasCategory(Intent.CATEGORY_HOME)) {
            removeLegacyLauncherTask();
        }
    }

    private void removeLegacyLauncherTask() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        ComponentName launcher = getComponentName();
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                Intent launch = task.getTaskInfo().baseIntent;
                if (launch != null && launcher.equals(launch.getComponent())
                        && !launch.hasCategory(Intent.CATEGORY_HOME)) {
                    task.finishAndRemoveTask();
                }
            } catch (IllegalArgumentException ignored) {
                // The task may have closed while the list was being read.
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isHomeIntent(intent)) {
            closeQuickMenu();
            closeDesktopSettings();
            status.dismissCityDialog();
            leaveSort(false);
            focusPage(defaultHomePage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = this;
        status.start();
    }

    @Override
    protected void onPause() {
        if (foreground == this) {
            foreground = null;
        }
        systemPanelOpened = false;
        status.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeQuickMenu();
        closeDesktopSettings();
        status.close();
        super.onDestroy();
    }

    static boolean routeMenuKey(KeyEvent event) {
        MainActivity activity = foreground;
        return activity != null && (activity.hasWindowFocus() || activity.isQuickMenuShowing())
                && activity.handleMenuKey(event);
    }

    static boolean isSystemPanelInFront() {
        MainActivity activity = foreground;
        return activity != null && activity.systemPanelOpened && !activity.hasWindowFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            systemPanelOpened = false;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", currentPage);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (sorting) {
            if (movingApp >= 0) {
                cancelMove();
            } else {
                leaveSort(false);
            }
            return;
        }
        View focused = getCurrentFocus();
        if (focused != navigation[currentPage]) {
            navigation[currentPage].requestFocus();
        } else if (currentPage != MINE) {
            navigation[MINE].requestFocus();
        } else if (!isHomeIntent(getIntent()) && !HomeService.isConnected()) {
            finish();
        }
    }

    private boolean isHomeIntent(Intent intent) {
        return intent.hasCategory(Intent.CATEGORY_HOME) || HomeService.SHOW_HOME.equals(intent.getAction());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && handleMenuKey(event)) {
            return true;
        }
        if (sorting) {
            int target = sortTarget(event.getKeyCode());
            if (target != -2) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && target >= 0) {
                    if (movingApp >= 0) {
                        moveApp(target);
                    } else {
                        appTiles.get(target).requestFocus();
                    }
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleMenuKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            if (isQuickMenuShowing()) {
                closeQuickMenu();
            } else if (currentPage == APPS) {
                if (sorting) {
                    leaveSort(true);
                } else if (!appOrder.isEmpty()) {
                    startSort();
                }
            } else if (!showSystemPanel()) {
                showQuickMenu();
            }
        }
        return true;
    }

    private boolean showSystemPanel() {
        Intent panel = new Intent(SMART_PANEL + ".action.SHOW_PANEL")
                .setClassName(SMART_PANEL, SMART_PANEL + ".SmartPanelService");
        try {
            systemPanelOpened = startService(panel) != null;
            return systemPanelOpened;
        } catch (SecurityException | IllegalStateException unavailable) {
            return false;
        }
    }

    private boolean isQuickMenuShowing() {
        return quickMenu != null && quickMenu.isShowing();
    }

    private void closeQuickMenu() {
        if (quickMenu != null) {
            quickMenu.dismiss();
            quickMenu = null;
        }
    }

    private void showQuickMenu() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = vertical();
        panel.setPadding(dp(15), dp(15), dp(15), dp(15));
        panel.setBackground(shape(0xff202226, 16, false));
        TextView heading = label("快捷菜单", 17, MUTED, true);
        heading.setPadding(dp(5), 0, 0, dp(10));
        panel.addView(heading);

        View inputs = addMenuItem(panel, "信号源", IconView.Glyph.INPUT, () -> {
            selectPage(INPUTS);
            pageCards.get(0).requestFocus();
        });
        View control = addMenuItem(panel, "控制", IconView.Glyph.SETTINGS, this::openControl);
        View smartHome = addMenuItem(panel, "米家", IconView.Glyph.SMART_HOME, this::openSmartHome);
        inputs.setNextFocusDownId(control.getId());
        control.setNextFocusUpId(inputs.getId());
        control.setNextFocusDownId(smartHome.getId());
        smartHome.setNextFocusUpId(control.getId());

        dialog.setContentView(panel);
        dialog.setOnKeyListener((window, code, event) -> code == KeyEvent.KEYCODE_MENU && handleMenuKey(event));
        quickMenu = dialog;
        dialog.show();
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(dp(230), -2);
        window.setGravity(Gravity.TOP | Gravity.RIGHT);
        WindowManager.LayoutParams position = window.getAttributes();
        position.x = dp(29);
        position.y = dp(78);
        position.dimAmount = 0.35f;
        window.setAttributes(position);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        inputs.requestFocus();
    }

    private View addMenuItem(LinearLayout panel, String name, IconView.Glyph glyph, Runnable action) {
        FrameLayout item = cardFrame(name, () -> {
            closeQuickMenu();
            action.run();
        });
        LinearLayout content = horizontal();
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(14), 0, dp(12), 0);
        content.addView(new IconView(this, glyph, WHITE), new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout.LayoutParams description = new LinearLayout.LayoutParams(-2, -2);
        description.leftMargin = dp(10);
        content.addView(label(name, 19, WHITE, true), description);
        item.addView(content, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, dp(46));
        itemParams.topMargin = dp(5);
        panel.addView(item, itemParams);
        return item;
    }

    private View createLayout() {
        LinearLayout root = horizontal();
        root.setBackgroundColor(BACKGROUND);

        LinearLayout sidebar = vertical();
        sidebar.setPadding(dp(22), dp(27), dp(0), 0);
        sidebar.setBackgroundColor(SIDEBAR);
        root.addView(sidebar, new LinearLayout.LayoutParams(dp(113), -1));

        IconView.Glyph[] glyphs = {IconView.Glyph.PERSON, IconView.Glyph.INPUT, IconView.Glyph.APPS};
        for (int index = 0; index < PAGE_NAMES.length; index++) {
            final int page = index;
            NavItem item = new NavItem(PAGE_NAMES[index], glyphs[index]);
            item.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    selectPage(page);
                }
                refreshNavigation();
            });
            item.setOnClickListener(view -> {
                selectPage(page);
                if (!pageCards.isEmpty()) {
                    pageCards.get(0).requestFocus();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(36));
            sidebar.addView(item, params);
            navigation[index] = item;
        }

        for (int index = 0; index < navigation.length; index++) {
            int up = Math.max(0, index - 1);
            int down = Math.min(navigation.length - 1, index + 1);
            navigation[index].setNextFocusUpId(navigation[up].getId());
            navigation[index].setNextFocusDownId(navigation[down].getId());
            navigation[index].setNextFocusLeftId(navigation[index].getId());
        }

        LinearLayout panel = vertical();
        panel.setPadding(dp(24), dp(34), dp(29), dp(21));
        root.addView(panel, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = label("", 31, WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        status = new HomeStatus(this);
        header.addView(status, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(50)));

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        body = vertical();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private int defaultHomePage() {
        int page = getPreferences(MODE_PRIVATE).getInt(DEFAULT_HOME_PAGE, MINE);
        return page >= 0 && page < PAGE_NAMES.length ? page : MINE;
    }

    private void focusPage(int page) {
        selectPage(page);
        navigation[page].requestFocus();
    }

    private void selectPage(int page) {
        if (currentPage == page) {
            return;
        }
        leaveSort(false);
        currentPage = page;
        refreshNavigation();
        body.removeAllViews();
        pageCards.clear();
        appTiles.clear();
        sortMessage = null;
        scroll.scrollTo(0, 0);
        title.setText(PAGE_NAMES[page]);
        if (page == MINE) {
            buildMine();
        } else if (page == INPUTS) {
            buildInputs();
        } else {
            buildApps();
        }
        navigation[page].setNextFocusRightId(
                pageCards.isEmpty() ? navigation[page].getId() : pageCards.get(0).getId());
    }

    private void refreshNavigation() {
        for (int index = 0; index < navigation.length; index++) {
            NavItem item = navigation[index];
            if (item != null) {
                item.paintState(index == currentPage);
            }
        }
    }

    private void buildMine() {
        LinearLayout accountRow = addRow();
        addCard(accountRow, tile("个人中心", "小米账号与安全", IconView.Glyph.PERSON, 0xff405b91,
                this::openAccount), true);
        addSection("系统管理");
        LinearLayout row = addRow();
        addCard(row, tile("设置", "显示、声音与系统", IconView.Glyph.SETTINGS, 0xff405b91,
                () -> openSettings(Settings.ACTION_SETTINGS)), true);
        addCard(row, tile("网络", "Wi-Fi 和以太网", IconView.Glyph.WIFI, 0xff2f657c,
                () -> openSettings(Settings.ACTION_WIFI_SETTINGS)), true);
        addCard(row, tile("蓝牙", "遥控器与配件", IconView.Glyph.BLUETOOTH, 0xff514779,
                () -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)), true);
        addCard(row, tile("天气城市", "设置状态栏的天气", IconView.Glyph.WEATHER, 0xff365d64,
                status::chooseCity), true);

        LinearLayout shortcuts = addRow();
        addCard(shortcuts, tile("最近任务", "切换或关闭应用", IconView.Glyph.RECENTS,
                0xff256e67, this::openRecentTasks), true);
        addCard(shortcuts, tile("应用管理", "查看和管理已安装应用", IconView.Glyph.MANAGE_APPS,
                0xff805335, this::openAppManagement), true);
        addCard(shortcuts, tile("桌面设置", "选择进入桌面时打开的页面", IconView.Glyph.DESKTOP_SETTINGS,
                0xff4b598f, this::showDesktopSettings), true);

        connectMineRow(accountRow, null, row);
        connectMineRow(row, accountRow, shortcuts);
        connectMineRow(shortcuts, row, null);
        accountRow.getChildAt(0).setNextFocusDownId(row.getChildAt(0).getId());
    }

    private void connectMineRow(LinearLayout row, LinearLayout above, LinearLayout below) {
        int columns = row.getChildCount();
        for (int column = 0; column < columns; column++) {
            View card = row.getChildAt(column);
            card.setNextFocusLeftId(column == 0 ? navigation[MINE].getId()
                    : row.getChildAt(column - 1).getId());
            card.setNextFocusRightId(row.getChildAt(Math.min(column + 1, columns - 1)).getId());
            card.setNextFocusUpId(above == null ? card.getId()
                    : above.getChildAt((2 * column + 1) * above.getChildCount() / (2 * columns)).getId());
            card.setNextFocusDownId(below == null ? card.getId()
                    : below.getChildAt((2 * column + 1) * below.getChildCount() / (2 * columns)).getId());
        }
    }

    private void buildInputs() {
        LinearLayout hdmi = addRow();
        for (int port = 1; port <= 3; port++) {
            final int source = port + 22;
            String name = "HDMI " + port;
            addCard(hdmi, tile(name, "HDMI 输入", IconView.Glyph.HDMI, 0xff365a84,
                    () -> openInput(name, "EXTSRC_PLAY", source)), true);
        }
        LinearLayout television = addRow();
        addCard(television, tile("TV", "模拟电视", IconView.Glyph.TV, 0xff435479,
                () -> openInput("TV", "ATV_PLAY", 1)), true);
        addCard(television, tile("AV", "复合视频输入", IconView.Glyph.AV, 0xff704e65,
                () -> openInput("AV", "EXTSRC_PLAY", 2)), true);
        addCard(television, tile("DTMB", "地面数字电视", IconView.Glyph.DTMB, 0xff3f6358,
                () -> openInput("DTMB", "DTMB_PLAY", 28)), true);
        StorageManager storage = getSystemService(StorageManager.class);
        if (storage != null) {
            for (StorageVolume volume : storage.getStorageVolumes()) {
                if (volume.isRemovable() && volume.getState().startsWith("mounted")) {
                    addCard(addRow(), tile("USB", "打开外接存储", IconView.Glyph.USB, 0xff326b63,
                            () -> open(new Intent("duokan.mediaexplorer.intent.action.BROWSE_WITH_PATH")
                                    .setPackage("com.xiaomi.mitv.mediaexplorer").putExtra("from", "input"),
                                    "无法打开 USB")), true);
                    break;
                }
            }
        }
        connectGrid(3);
    }

    private void buildApps() {
        appOrder = installedApps();
        if (appOrder.isEmpty()) {
            addSection("暂无可打开的应用");
            return;
        }
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(0, dp(9), 0, dp(14));
        heading.addView(label("已安装应用", 18, WHITE, true), new LinearLayout.LayoutParams(0, -2, 1f));
        sortMessage = label("", 14, MUTED, false);
        heading.addView(sortMessage);
        body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        updateSortMessage();

        LinearLayout row = null;
        for (int index = 0; index < appOrder.size(); index++) {
            if (index % APP_COLUMNS == 0) {
                row = addRow();
            }
            AppTile card = new AppTile(index);
            card.bind(appOrder.get(index), false);
            addCard(row, card, false);
            appTiles.add(card);
        }
        for (int index = appOrder.size() % APP_COLUMNS; index > 0 && index < APP_COLUMNS; index++) {
            View spacer = new View(this);
            spacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(spacer, cellParams(dp(115)));
        }
        connectGrid(APP_COLUMNS);
    }

    private void startSort() {
        sorting = true;
        updateSortMessage();
        if (focusedApp() < 0) {
            appTiles.get(0).requestFocus();
        }
    }

    private int focusedApp() {
        return appTiles.indexOf(getCurrentFocus());
    }

    private void selectApp(int index) {
        if (!sorting) {
            openApp(appOrder.get(index));
            return;
        }
        if (movingApp >= 0) {
            saveMove();
        } else {
            orderBeforeMove = new ArrayList<>(appOrder);
            movingApp = index;
            appTiles.get(index).bind(appOrder.get(index), true);
        }
        updateSortMessage();
    }

    private int sortTarget(int keyCode) {
        int index = focusedApp();
        if (index < 0) {
            return -2;
        }
        int column = index % APP_COLUMNS;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return column == 0 ? -1 : index - 1;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return column == APP_COLUMNS - 1 || index + 1 == appOrder.size() ? -1 : index + 1;
            case KeyEvent.KEYCODE_DPAD_UP:
                return index < APP_COLUMNS ? -1 : index - APP_COLUMNS;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return index + APP_COLUMNS < appOrder.size() ? index + APP_COLUMNS : -1;
            default:
                return -2;
        }
    }

    private void moveApp(int target) {
        int previous = movingApp;
        Collections.swap(appOrder, previous, target);
        movingApp = target;
        appTiles.get(previous).bind(appOrder.get(previous), false);
        AppTile selected = appTiles.get(target);
        selected.bind(appOrder.get(target), true);
        selected.requestFocus();
    }

    private void saveMove() {
        List<String> packages = new ArrayList<>();
        for (InstalledApp app : appOrder) {
            packages.add(app.component.getPackageName());
        }
        getPreferences(MODE_PRIVATE).edit().putString("app_order", TextUtils.join("\n", packages)).apply();
        appTiles.get(movingApp).bind(appOrder.get(movingApp), false);
        movingApp = -1;
        orderBeforeMove = null;
    }

    private void cancelMove() {
        InstalledApp selected = appOrder.get(movingApp);
        appOrder = orderBeforeMove;
        movingApp = -1;
        orderBeforeMove = null;
        for (int index = 0; index < appTiles.size(); index++) {
            appTiles.get(index).bind(appOrder.get(index), false);
        }
        appTiles.get(appOrder.indexOf(selected)).requestFocus();
        updateSortMessage();
    }

    private void leaveSort(boolean save) {
        if (!sorting) {
            return;
        }
        if (movingApp >= 0) {
            if (save) {
                saveMove();
            } else {
                cancelMove();
            }
        }
        sorting = false;
        updateSortMessage();
    }

    private void updateSortMessage() {
        if (sortMessage != null) {
            String message = "菜单键 排序";
            if (sorting) {
                message = movingApp >= 0 ? "方向键移动　确定键放下　返回键取消"
                        : "确定键选中应用　菜单键退出排序";
            }
            sortMessage.setText(message);
        }
    }

    private void addSection(String name) {
        TextView section = label(name, 17, MUTED, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(9);
        params.bottomMargin = dp(5);
        body.addView(section, params);
    }

    private LinearLayout addRow() {
        LinearLayout row = horizontal();
        row.setClipChildren(false);
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private LinearLayout.LayoutParams cellParams(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, 1f);
        params.rightMargin = dp(10);
        params.bottomMargin = dp(10);
        params.topMargin = dp(5);
        return params;
    }

    private void addCard(LinearLayout row, FrameLayout card, boolean shortcut) {
        row.addView(card, cellParams(dp(shortcut ? 119 : 115)));
        pageCards.add(card);
    }

    private void connectGrid(int columns) {
        int size = pageCards.size();
        for (int index = 0; index < size; index++) {
            View card = pageCards.get(index);
            int column = index % columns;
            int rowEnd = Math.min(size - 1, index - column + columns - 1);
            card.setNextFocusLeftId(column == 0 ? navigation[currentPage].getId()
                    : pageCards.get(index - 1).getId());
            card.setNextFocusRightId(pageCards.get(Math.min(rowEnd, index + 1)).getId());
            card.setNextFocusUpId(pageCards.get(index < columns ? index : index - columns).getId());
            int down = index - column + columns >= size ? index : Math.min(size - 1, index + columns);
            card.setNextFocusDownId(pageCards.get(down).getId());
        }
    }

    private FrameLayout tile(String name, String detail, IconView.Glyph glyph,
            int accent, Runnable onClick) {
        FrameLayout card = cardFrame(name + "，" + detail, onClick);
        FrameLayout iconBackground = new FrameLayout(this);
        iconBackground.setBackground(shape(accent, 10, false));
        FrameLayout.LayoutParams iconBox = new FrameLayout.LayoutParams(dp(40), dp(40),
                Gravity.TOP | Gravity.RIGHT);
        iconBox.setMargins(0, dp(16), dp(16), 0);
        card.addView(iconBackground, iconBox);
        IconView icon = new IconView(this, glyph, WHITE);
        iconBackground.addView(icon, new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));

        LinearLayout description = vertical();
        description.addView(label(name, 21, WHITE, true));
        TextView caption = label(detail, 13, MUTED, false);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-2, -2);
        captionParams.topMargin = dp(2);
        description.addView(caption, captionParams);
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(-1, -2,
                Gravity.BOTTOM | Gravity.LEFT);
        placement.setMargins(dp(17), 0, dp(13), dp(15));
        card.addView(description, placement);
        return card;
    }

    private FrameLayout cardFrame(String description, Runnable onClick) {
        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setDefaultFocusHighlightEnabled(false);
        card.setContentDescription(description);
        card.setBackground(shape(CARD, 13, false));
        card.setOnFocusChangeListener((view, focused) ->
                card.setBackground(shape(focused ? 0xff373a42 : CARD, 13, focused)));
        card.setOnClickListener(view -> onClick.run());
        return card;
    }

    private List<InstalledApp> installedApps() {
        Map<String, InstalledApp> unique = new LinkedHashMap<>();
        addApps(unique, Intent.CATEGORY_LEANBACK_LAUNCHER);
        addApps(unique, Intent.CATEGORY_LAUNCHER);
        List<InstalledApp> apps = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        apps.sort((first, second) -> {
            int compare = collator.compare(first.label, second.label);
            return compare != 0 ? compare : first.component.compareTo(second.component);
        });
        List<InstalledApp> ordered = new ArrayList<>();
        for (String packageName : getPreferences(MODE_PRIVATE).getString("app_order", "").split("\n")) {
            InstalledApp app = unique.remove(packageName);
            if (app != null) {
                ordered.add(app);
            }
        }
        for (InstalledApp app : apps) {
            if (unique.containsKey(app.component.getPackageName())) {
                ordered.add(app);
            }
        }
        return ordered;
    }

    private void addApps(Map<String, InstalledApp> unique, String category) {
        PackageManager packages = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo info : packages.queryIntentActivities(query, 0)) {
            if (info.activityInfo == null || !info.activityInfo.exported) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName) || HomeService.STOCK_PACKAGE.equals(packageName)
                    || unique.containsKey(packageName)) {
                continue;
            }
            ComponentName component = new ComponentName(packageName, info.activityInfo.name);
            CharSequence appLabel = info.loadLabel(packages);
            String name = TextUtils.isEmpty(appLabel) ? packageName : appLabel.toString();
            unique.put(packageName, new InstalledApp(component, category, name, info));
        }
    }

    private void openApp(InstalledApp app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(app.category)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        open(intent, "无法打开「" + app.label + "」");
    }

    private void openSettings(String action) {
        Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        open(intent, "无法打开这项设置");
    }

    private void openRecentTasks() {
        if (!HomeService.openRecentTasks()) {
            Toast.makeText(this, "此系统暂无法打开最近任务", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppManagement() {
        if (!tryOpen(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                && !tryOpen(new Intent(Settings.ACTION_APPLICATION_SETTINGS))) {
            openSettings(Settings.ACTION_SETTINGS);
        }
    }

    private void showDesktopSettings() {
        desktopSettings = new AlertDialog.Builder(this)
                .setTitle("默认首页")
                .setSingleChoiceItems(PAGE_NAMES, defaultHomePage(), (dialog, page) -> {
                    getPreferences(MODE_PRIVATE).edit().putInt(DEFAULT_HOME_PAGE, page).apply();
                    dialog.dismiss();
                    focusPage(page);
                })
                .setNegativeButton("取消", null)
                .create();
        desktopSettings.setOnDismissListener(dialog -> desktopSettings = null);
        desktopSettings.show();
    }

    private void closeDesktopSettings() {
        if (desktopSettings != null) {
            desktopSettings.dismiss();
        }
    }

    private Intent securitySettingsIntent() {
        return new Intent(TV_SETTINGS + ".SECURITY_SETTINGS")
                .setPackage(TV_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void openAccount() {
        if (!tryOpen(securitySettingsIntent()) && !tryOpen(new Intent(Settings.ACTION_SYNC_SETTINGS))) {
            openSettings(Settings.ACTION_SETTINGS);
        }
    }

    private void openControl() {
        sendBroadcast(new Intent("com.xiaomi.intent.action.show.globalsetting")
                .setClassName(TV_SETTINGS, TV_SETTINGS + ".SettingsBroadcastReceiver"));
    }

    private void openSmartHome() {
        Intent controlPage = new Intent(SMART_HOME + ".action.CONTROL_PAGE").setPackage(SMART_HOME);
        if (tryOpen(controlPage)) {
            return;
        }
        PackageManager packages = getPackageManager();
        Intent launch = packages.getLeanbackLaunchIntentForPackage(SMART_HOME);
        if (launch == null) {
            launch = packages.getLaunchIntentForPackage(SMART_HOME);
        }
        if (launch == null || !tryOpen(launch)) {
            Toast.makeText(this, "电视上暂时无法打开米家", Toast.LENGTH_SHORT).show();
        }
    }

    private void openInput(String name, String action, int source) {
        open(new Intent(STOCK_PLAYER + "." + action)
                .setPackage(STOCK_PLAYER)
                .putExtra("input", source), "无法打开 " + name);
    }

    private void open(Intent intent, String errorMessage) {
        if (!tryOpen(intent)) {
            Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean tryOpen(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            return false;
        }
    }

    private GradientDrawable shape(int fill, int radius, boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (focused) {
            drawable.setStroke(dp(2), WHITE);
        }
        return drawable;
    }

    private LinearLayout horizontal() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView label(CharSequence value, int size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (medium) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class InstalledApp {
        private final ComponentName component;
        private final String category;
        private final String label;
        private final ResolveInfo info;

        private InstalledApp(ComponentName component, String category, String label, ResolveInfo info) {
            this.component = component;
            this.category = category;
            this.label = label;
            this.info = info;
        }
    }

    private final class AppTile extends FrameLayout {
        private final FrameLayout artwork;
        private final ImageView icon;
        private final TextView name;
        private boolean picked;

        private AppTile(int index) {
            super(MainActivity.this);
            setId(View.generateViewId());
            setFocusable(true);
            setClickable(true);
            setDefaultFocusHighlightEnabled(false);
            setClipChildren(false);
            setOnClickListener(view -> selectApp(index));
            setOnLongClickListener(view -> {
                if (!sorting) {
                    startSort();
                    selectApp(index);
                }
                return true;
            });
            setOnFocusChangeListener((view, focused) -> paint());

            LinearLayout content = vertical();
            content.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            content.setClipChildren(false);
            content.setClipToPadding(false);
            addView(content, new FrameLayout.LayoutParams(-1, -1));

            artwork = new FrameLayout(MainActivity.this);
            artwork.setBackground(shape(CARD, 18, false));
            artwork.setClipToOutline(true);
            content.addView(artwork, new LinearLayout.LayoutParams(dp(88), dp(88)));
            icon = new ImageView(MainActivity.this);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            artwork.addView(icon, new FrameLayout.LayoutParams(-1, -1));

            name = label("", 14, MUTED, false);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-1, -2);
            textParams.topMargin = dp(5);
            content.addView(name, textParams);
        }

        private void bind(InstalledApp app, boolean picked) {
            this.picked = picked;
            Drawable drawable = app.info.loadIcon(getPackageManager());
            icon.setImageDrawable(drawable);
            name.setText(app.label);
            setContentDescription(picked ? "移动 " + app.label : app.label);
            paint();
        }

        private void paint() {
            boolean focused = hasFocus();
            if (picked || focused) {
                int tint = picked ? MOVE_ACCENT : WHITE;
                GradientDrawable ring = shape(Color.TRANSPARENT, 18, false);
                ring.setStroke(dp(4), tint);
                artwork.setForeground(ring);
                name.setBackground(shape(tint, 6, false));
            } else {
                artwork.setForeground(null);
                name.setBackground(null);
            }
            artwork.setScaleX(focused ? 1.07f : 1f);
            artwork.setScaleY(focused ? 1.07f : 1f);
            name.setTextColor(picked || focused ? ON_LIGHT : MUTED);
            name.setTypeface(Typeface.create("sans-serif", picked || focused ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    private final class NavItem extends LinearLayout {
        private final IconView icon;
        private final TextView text;

        private NavItem(String name, IconView.Glyph glyph) {
            super(MainActivity.this);
            setId(View.generateViewId());
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(10), 0, dp(7), 0);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setDefaultFocusHighlightEnabled(false);
            setContentDescription(name);

            icon = new IconView(MainActivity.this, glyph, MUTED);
            addView(icon, new LinearLayout.LayoutParams(dp(17), dp(17)));

            text = label(name, 15, MUTED, true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.leftMargin = dp(8);
            addView(text, params);
        }

        private void paintState(boolean selected) {
            int tint = hasFocus() ? ON_LIGHT : selected ? WHITE : MUTED;
            setBackground(shape(hasFocus() ? WHITE : selected ? NAV_SELECTED : Color.TRANSPARENT,
                    9, false));
            icon.setTint(tint);
            text.setTextColor(tint);
            setSelected(selected);
        }
    }
}
