package com.liuguang.cast.tv;

import static com.liuguang.cast.shared.Theme.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.liuguang.cast.shared.Pairing;
import com.liuguang.cast.shared.TvTools;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class TvReceiverPanel extends FrameLayout {
    private static final int PAIR = 0, FILES = 1, INFO = 2;
    private static final String FILE_HINT = "按确定键打开文件，按菜单键或长按确定键管理。";
    private static final ExecutorService QR_ENCODER = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "tv-pairing-qr");
        worker.setDaemon(true);
        return worker;
    });
    private final Activity host;
    private final boolean compact;
    private final FrameLayout body;
    private final TextView[] tabs = new TextView[3];
    private final TextView[] digits = new TextView[Pairing.SHORT_CODE_LENGTH];
    private final LinearLayout[] pages = new LinearLayout[3];
    private final Runnable updateReceiver = this::renderReceiver;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, address, refresh, unpair, overlayAccess, overlayDescription;
    private TextView fileStatus, manageFiles;
    private ImageView qr;
    private LinearLayout qrPlaceholder;
    private ProgressBar qrProgress;
    private TextView qrMessage;
    private Future<?> qrTask;
    private String qrPayload;
    private int qrRequest;
    private LinearLayout fileRows, infoRows;
    private ScrollView infoScroll;
    private FilePreview preview;
    private File selectedFile;
    private File[] visibleFiles;
    private String lastFileStatus, lanAddress;
    private int page, sidebarFocusId = View.NO_ID;

    public TvReceiverPanel(Activity host) {
        super(host);
        this.host = host;
        compact = getResources().getConfiguration().screenWidthDp < 875
                || getResources().getConfiguration().screenHeightDp < 520;
        LinearLayout layout = column(host);
        addView(layout, new FrameLayout.LayoutParams(-1, -1));
        buildTabs(layout);
        body = new FrameLayout(host);
        LinearLayout.LayoutParams remaining = new LinearLayout.LayoutParams(-1, 0, 1);
        remaining.topMargin = d(12);
        layout.addView(body, remaining);
        for (int index = 0; index < pages.length; index++) {
            pages[index] = card(host, compact ? 13 : 17);
            body.addView(pages[index], new FrameLayout.LayoutParams(-1, -1));
        }
        buildPairing();
        buildFiles();
        buildInfo();
        showPage(PAIR);
    }

    public View entryFocus() { return tabs[page]; }

    public void setSidebarFocusId(int id) {
        sidebarFocusId = id;
        tabs[0].setNextFocusLeftId(id);
        refresh.setNextFocusLeftId(id);
        overlayAccess.setNextFocusLeftId(id);
        infoScroll.setNextFocusLeftId(id);
        for (int index = 0; index < fileRows.getChildCount(); index++) {
            fileRows.getChildAt(index).setNextFocusLeftId(id);
        }
    }

    public boolean handleKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (preview != null && (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) closePreview(true);
            return true;
        }
        if (page != FILES || preview != null || !RemoteMenuKey.matches(key)) return false;
        if (RemoteMenuKey.isPress(event)) {
            showFileMenu();
        }
        return true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TvReceiverService.addListener(updateReceiver);
        renderReceiver();
        updateOverlayAccess();
    }

    @Override protected void onDetachedFromWindow() {
        TvReceiverService.removeListener(updateReceiver);
        cancelQr();
        showQrPlaceholder("等待接收端启动", false);
        closePreview(false);
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && isShown()) updateOverlayAccess();
    }

    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility);
        if (visibility != View.VISIBLE) closePreview(false);
        else if (isAttachedToWindow()) updateOverlayAccess();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE) closePreview(false);
    }

    private void buildTabs(LinearLayout layout) {
        LinearLayout controls = row(host);
        String[] titles = {"投屏配对", "接收文件", "设备信息"};
        for (int index = 0; index < titles.length; index++) {
            int destination = index;
            TextView tab = tab(host, titles[index]);
            tab.setId(View.generateViewId());
            tab.setMinWidth(d(compact ? 76 : 103));
            tab.setOnClickListener(view -> showPage(destination));
            tab.setOnFocusChangeListener((view, focused) -> {
                if (focused && destination != page) showPage(destination);
            });
            LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-2, d(40));
            if (index > 0) position.leftMargin = d(6);
            controls.addView(tab, position);
            tabs[index] = tab;
        }
        for (int index = 0; index < tabs.length; index++) {
            tabs[index].setNextFocusUpId(tabs[index].getId());
            tabs[index].setNextFocusRightId(tabs[Math.min(tabs.length - 1, index + 1)].getId());
            if (index > 0) tabs[index].setNextFocusLeftId(tabs[index - 1].getId());
        }
        FrameLayout trailing = new FrameLayout(host);
        LinearLayout.LayoutParams end = new LinearLayout.LayoutParams(0, -2, 1);
        end.leftMargin = d(10);
        controls.addView(trailing, end);
        status = tag(host, "正在启动接收端", ACCENT, ACCENT_SURFACE);
        status.setSingleLine();
        status.setEllipsize(TextUtils.TruncateAt.END);
        trailing.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        add(layout, controls, 0);
    }

    private void buildPairing() {
        LinearLayout pairing = column(host);
        attachScroll(pages[PAIR], pairing, false);
        add(pairing, text(host, "在手机流光投屏中扫码，或选择这台电视后输入投屏码。",
                compact ? 13 : 15, MUTED, false), 0);
        LinearLayout codeRow = row(host);
        int qrSize = d(compact ? 124 : 154);
        codeRow.addView(buildQr(), new LinearLayout.LayoutParams(qrSize, qrSize));

        LinearLayout codeInfo = column(host);
        codeInfo.setBackground(box(host, CANVAS, CARD_RADIUS, 0));
        int pad = d(compact ? 10 : 14);
        codeInfo.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, -2, 1);
        grow.leftMargin = d(compact ? 12 : 18);
        codeRow.addView(codeInfo, grow);
        add(codeInfo, text(host, "四位投屏码", 15, TEXT, true), 0);
        LinearLayout codeCells = row(host);
        int cellSize = d(compact ? 33 : 42);
        for (int index = 0; index < digits.length; index++) {
            TextView digit = text(host, "–", compact ? 22 : 28, TEXT, true);
            digit.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            digit.setGravity(Gravity.CENTER);
            digit.setBackground(box(host, ACCENT_SURFACE, 9, 0));
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(cellSize, cellSize);
            if (index > 0) cell.leftMargin = d(6);
            codeCells.addView(digit, cell);
            digits[index] = digit;
        }
        add(codeInfo, codeCells, 5);
        add(codeInfo, text(host, "局域网地址", 12, MUTED, false), 8);
        address = text(host, "正在获取…", 16, TEXT, true);
        address.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        add(codeInfo, address, 0);
        add(pairing, codeRow, 9);

        LinearLayout actions = row(host);
        refresh = action(host, "刷新投屏码", true);
        refresh.setId(View.generateViewId());
        refresh.setOnClickListener(view -> {
            TvReceiverService receiver = TvReceiverService.get();
            if (receiver != null) receiver.refreshCode();
        });
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        unpair = action(host, "解除所有配对", false);
        unpair.setId(View.generateViewId());
        unpair.setOnClickListener(view -> confirmUnpair());
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.leftMargin = d(9);
        actions.addView(unpair, second);
        add(pairing, actions, 9);
        line(pairing, 11);
        buildOverlayAccess(pairing);

        tabs[PAIR].setNextFocusDownId(refresh.getId());
        refresh.setNextFocusUpId(tabs[PAIR].getId());
        refresh.setNextFocusRightId(unpair.getId());
        refresh.setNextFocusDownId(overlayAccess.getId());
        unpair.setNextFocusLeftId(refresh.getId());
        unpair.setNextFocusUpId(tabs[PAIR].getId());
        unpair.setNextFocusDownId(overlayAccess.getId());
        overlayAccess.setNextFocusUpId(unpair.getId());
    }

    private FrameLayout buildQr() {
        FrameLayout frame = new FrameLayout(host);
        frame.setBackground(box(host, CANVAS, CARD_RADIUS, BORDER));
        qrPlaceholder = column(host);
        qrPlaceholder.setGravity(Gravity.CENTER);
        qrPlaceholder.setPadding(d(9), d(9), d(9), d(9));
        qrProgress = new ProgressBar(host, null, android.R.attr.progressBarStyleSmall);
        qrProgress.setIndeterminate(true);
        qrProgress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        qrProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        qrPlaceholder.addView(qrProgress, new LinearLayout.LayoutParams(d(23), d(23)));
        qrMessage = text(host, "", 12, MUTED, false);
        qrMessage.setGravity(Gravity.CENTER);
        qrMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        qrMessage.setPadding(0, d(6), 0, 0);
        qrPlaceholder.addView(qrMessage, new LinearLayout.LayoutParams(-1, -2));
        frame.addView(qrPlaceholder, new FrameLayout.LayoutParams(-1, -1));

        qr = new ImageView(host);
        qr.setContentDescription("电视配对二维码");
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setBackground(box(host, Color.WHITE, CARD_RADIUS, 0));
        qr.setPadding(d(5), d(5), d(5), d(5));
        frame.addView(qr, new FrameLayout.LayoutParams(-1, -1));
        showQrPlaceholder("等待接收端启动", false);
        return frame;
    }

    private void confirmUnpair() {
        new AlertDialog.Builder(host).setTitle("解除所有手机配对？")
                .setMessage("已保存这台电视的手机需要重新配对。")
                .setNegativeButton("取消", null).setPositiveButton("解除配对", (dialog, which) -> {
                    TvReceiverService receiver = TvReceiverService.get();
                    if (receiver != null) receiver.clearPairings();
                }).show();
    }

    private void buildOverlayAccess(LinearLayout pairing) {
        LinearLayout line = row(host);
        overlayDescription = text(host, "", 13, MUTED, false);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.rightMargin = d(10);
        line.addView(overlayDescription, left);
        overlayAccess = action(host, "允许在其他应用上显示", false);
        overlayAccess.setId(View.generateViewId());
        overlayAccess.setOnClickListener(view -> openOverlaySettings());
        line.addView(overlayAccess);
        add(pairing, line, 8);
        updateOverlayAccess();
    }

    private void updateOverlayAccess() {
        boolean allowed = Settings.canDrawOverlays(host);
        overlayAccess.setText(allowed ? "显示权限 · 已允许" : "允许在其他应用上显示");
        overlayDescription.setText(allowed ? "使用其他应用时也能自动打开投屏画面。"
                : "允许后，使用其他应用时也能自动打开投屏画面。");
    }

    private void openOverlaySettings() {
        Intent settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        if (Build.VERSION.SDK_INT < 30) {
            settings.setData(Uri.fromParts("package", host.getPackageName(), null));
        }
        try {
            host.startActivity(settings);
            if (Build.VERSION.SDK_INT >= 30) {
                Toast.makeText(host, "在系统列表中选择「简洁桌面」", Toast.LENGTH_SHORT).show();
            }
        } catch (ActivityNotFoundException | SecurityException error) {
            new AlertDialog.Builder(host).setTitle("这台电视没有提供显示权限设置")
                    .setMessage("打开简洁桌面后，仍可从手机开始投屏。")
                    .setPositiveButton("知道了", null).show();
        }
    }

    private void buildFiles() {
        LinearLayout page = pages[FILES];
        LinearLayout header = row(host);
        header.addView(text(host, "收到的文件", compact ? 20 : 24, TEXT, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        manageFiles = action(host, "管理文件", false);
        manageFiles.setId(View.generateViewId());
        manageFiles.setNextFocusUpId(tabs[FILES].getId());
        manageFiles.setNextFocusLeftId(tabs[FILES].getId());
        manageFiles.setOnClickListener(view -> showFileMenu());
        header.addView(manageFiles);
        add(page, header, 0);
        fileStatus = text(host, FILE_HINT, 14, MUTED, false);
        fileStatus.setMaxLines(2);
        fileStatus.setEllipsize(TextUtils.TruncateAt.END);
        add(page, fileStatus, 6);
        line(page, 10);
        fileRows = column(host);
        attachScroll(page, fileRows, false);
    }

    private void buildInfo() {
        infoRows = column(host);
        infoScroll = attachScroll(pages[INFO], infoRows, true);
        infoScroll.setId(View.generateViewId());
        tabs[INFO].setNextFocusDownId(infoScroll.getId());
    }

    private ScrollView attachScroll(LinearLayout panel, LinearLayout contents, boolean focusable) {
        ScrollView scroll = new ScrollView(host);
        scroll.setClipToPadding(false);
        scroll.setFocusable(focusable);
        scroll.addView(contents, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return scroll;
    }

    private void showPage(int next) {
        closePreview(false);
        page = next;
        for (int index = 0; index < tabs.length; index++) {
            boolean selected = index == next;
            tabs[index].setSelected(selected);
            pages[index].setVisibility(selected ? View.VISIBLE : View.GONE);
        }
        if (next == FILES) reloadFiles();
        if (next == INFO) renderInfo();
    }

    private void renderReceiver() {
        TvReceiverService receiver = TvReceiverService.get();
        String message = receiver == null ? "正在启动接收端" : receiver.statusText();
        boolean ok = receiver != null && receiver.statusOk();
        status.setText(message);
        status.setTextColor(receiver == null ? ACCENT : ok ? GREEN : WARNING);
        status.setBackground(box(host, receiver == null ? ACCENT_SURFACE
                : ok ? GREEN_SURFACE : WARNING_SURFACE, 10, 0));
        refresh.setEnabled(receiver != null);
        TvServer.PairCode code = receiver == null ? null : receiver.pairCode();
        unpair.setEnabled(code != null);
        String received = receiver == null ? null : receiver.fileStatusText();
        if (!Objects.equals(lastFileStatus, received)) {
            lastFileStatus = received;
            fileStatus.setText(TextUtils.isEmpty(received) ? FILE_HINT : received);
            if (page == FILES) reloadFiles();
        }
        String ipv4 = receiver == null ? null : receiver.address();
        String qrNote = receiver == null ? "等待接收端启动"
                : ipv4 == null ? "电视未连接局域网" : ok ? "等待生成投屏码" : "二维码暂不可用";
        renderPairing(code, ipv4, qrNote);
    }

    private void renderPairing(TvServer.PairCode code, String ipv4, String qrNote) {
        for (int index = 0; index < digits.length; index++) {
            digits[index].setText(code == null ? "–" : code.digits().substring(index, index + 1));
        }
        if (!Objects.equals(lanAddress, ipv4)) {
            lanAddress = ipv4;
            if (page == INFO) renderInfo();
        }
        address.setText(ipv4 == null ? "未连接局域网" : ipv4);
        String value = code == null || ipv4 == null ? null
                : Pairing.qr(ipv4, code.fingerprint() + code.qrSecret());
        updateQr(value, qrNote);
    }

    private void updateQr(String payload, String unavailable) {
        if (payload == null) {
            if (qrPayload != null) cancelQr();
            showQrPlaceholder(unavailable, false);
            return;
        }
        if (payload.equals(qrPayload)) return;
        cancelQr();
        qrPayload = payload;
        int request = qrRequest;
        showQrPlaceholder("正在生成二维码…", true);
        qrTask = QR_ENCODER.submit(() -> {
            Bitmap bitmap = qrBitmap(payload);
            ui.post(() -> {
                if (request != qrRequest || !isAttachedToWindow()) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                qrTask = null;
                if (bitmap == null) {
                    showQrPlaceholder("生成失败，请刷新投屏码", false);
                } else {
                    qr.setImageBitmap(bitmap);
                    qr.setVisibility(View.VISIBLE);
                    qrPlaceholder.setVisibility(View.GONE);
                }
            });
        });
    }

    private void cancelQr() {
        qrRequest++;
        qrPayload = null;
        if (qrTask != null) {
            qrTask.cancel(false);
            qrTask = null;
        }
    }

    private void showQrPlaceholder(String message, boolean loading) {
        qr.setVisibility(View.GONE);
        qr.setImageDrawable(null);
        qrMessage.setText(message);
        qrProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        qrPlaceholder.setVisibility(View.VISIBLE);
    }

    private static Bitmap qrBitmap(String payload) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 340, 340);
            int width = matrix.getWidth(), height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (WriterException | IllegalArgumentException error) {
            return null;
        }
    }

    private void renderInfo() {
        TvTools.Info info = TvDetails.info(host);
        infoRows.removeAllViews();
        addFact("型号", info.model());
        addFact("系统", "Android " + info.android() + "  /  API " + info.api());
        addFact("分辨率", info.width() + " × " + info.height());
        addFact("局域网地址", lanAddress == null ? "未连接" : lanAddress);
        addFact("可用 / 总存储", TvTools.size(info.available()) + " / " + TvTools.size(info.total()));
        addFact("接收端版本", info.version());
    }

    private void addFact(String label, String value) {
        LinearLayout fact = row(host);
        fact.addView(text(host, label, 14, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        fact.addView(text(host, value, 15, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1.3f));
        add(infoRows, fact, 11);
        line(infoRows, 10);
    }

    private void reloadFiles() {
        File[] files = TvFiles.list(host);
        if (Arrays.equals(visibleFiles, files)) return;
        visibleFiles = files;
        boolean restoreFocus = fileRows.hasFocus();
        File previous = selectedFile;
        fileRows.removeAllViews();
        manageFiles.setEnabled(files.length > 0);
        if (files.length == 0) {
            selectedFile = null;
            tabs[FILES].setNextFocusDownId(tabs[FILES].getId());
            add(fileRows, text(host, "还没有收到文件", 15, MUTED, false), 12);
            if (restoreFocus) tabs[FILES].requestFocus();
            return;
        }
        tabs[FILES].setNextFocusDownId(manageFiles.getId());
        View selected = null;
        for (File file : files) {
            TextView item = action(host, file.getName() + "    ·    " + TvTools.size(file.length()), false);
            item.setId(View.generateViewId());
            item.setNextFocusLeftId(sidebarFocusId);
            if (fileRows.getChildCount() == 0) {
                manageFiles.setNextFocusDownId(item.getId());
                item.setNextFocusUpId(manageFiles.getId());
            }
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setMaxLines(2);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setOnFocusChangeListener((view, focused) -> { if (focused) selectedFile = file; });
            item.setOnClickListener(view -> openFile(file));
            item.setOnLongClickListener(view -> {
                selectedFile = file;
                showFileMenu();
                return true;
            });
            if (file.equals(previous)) selected = item;
            add(fileRows, item, 7);
        }
        if (restoreFocus) (selected == null ? manageFiles : selected).requestFocus();
    }

    private void openFile(File file) {
        if (TvFiles.isApk(file)) {
            TvReceiverService receiver = TvReceiverService.get();
            if (receiver != null) receiver.install(file);
            return;
        }
        String type = TvFiles.mime(file);
        if (FilePreview.supported(type)) {
            selectedFile = file;
            preview = new FilePreview(host, file, () -> closePreview(true));
            body.addView(preview, new FrameLayout.LayoutParams(-1, -1));
            pages[FILES].setVisibility(View.GONE);
            return;
        }
        Uri uri = TvFiles.contentUri(host, file);
        Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        open.setClipData(ClipData.newRawUri(file.getName(), uri));
        try {
            host.startActivity(open);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(host, "电视上没有能打开此格式的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void closePreview(boolean focus) {
        if (preview == null) return;
        preview.release();
        body.removeView(preview);
        preview = null;
        pages[FILES].setVisibility(page == FILES ? View.VISIBLE : View.GONE);
        if (focus) tabs[FILES].requestFocus();
    }

    private void showFileMenu() {
        File[] received = TvFiles.list(host);
        if (received.length == 0) return;
        File selection = selectedFile == null ? null : TvFiles.resolve(host, selectedFile.getName());
        String[] choices = selection == null ? new String[] {"清空收到的文件"}
                : new String[] {"删除 " + selection.getName(), "清空收到的文件"};
        new AlertDialog.Builder(host).setTitle("文件管理").setItems(choices, (dialog, index) -> {
            boolean all = index == choices.length - 1;
            confirmDelete(all ? received : new File[] {selection}, all);
        }).setNegativeButton("取消", null).show();
    }

    private void confirmDelete(File[] files, boolean all) {
        new AlertDialog.Builder(host)
                .setTitle(all ? "清空收到的文件？" : "删除 " + files[0].getName() + "？")
                .setMessage(all ? "将从电视删除当前接收列表中的 " + files.length + " 个文件。"
                        : "将从电视删除这个文件。")
                .setNegativeButton("取消", null).setPositiveButton(all ? "清空" : "删除", (dialog, choice) -> {
                    int deleted = 0;
                    for (File file : files) {
                        File saved = TvFiles.resolve(host, file.getName());
                        if (saved != null && saved.delete()) deleted++;
                    }
                    selectedFile = null;
                    reloadFiles();
                    fileStatus.setText(deleted == files.length
                            ? (all ? "已清空接收列表" : "已删除文件")
                            : "已删除 " + deleted + " 个，部分文件未能删除");
                    (manageFiles.isEnabled() ? manageFiles : tabs[FILES]).requestFocus();
                }).show();
    }

    private void add(LinearLayout target, View view, int gap) {
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, -2);
        position.topMargin = d(gap);
        target.addView(view, position);
    }

    private int d(float value) { return dp(host, value); }
}
