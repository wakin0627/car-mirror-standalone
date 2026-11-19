package com.niofirefly.standalone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ScrollView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView address;
    private TextView updateStatus;
    private UpdateManager updates;
    private Spinner quality;
    private Spinner carRatio;
    private CheckBox flip;
    private CheckBox tabletMode;
    private CheckBox virtualMode;
    private CheckBox windowDecorations;
    private CheckBox splitMode;
    private Spinner leftApp, rightApp;
    private List<ResolveInfo> launchableApps;
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            if (status != null) status.setText(MirrorService.state + (SplitMirrorService.active && !SplitMirrorService.pairCode.isEmpty() ? "\n车机配对码：" + SplitMirrorService.pairCode : ""));
            if (address != null) address.setText(currentUrl() == null ? "请先连接车机 Wi-Fi" : currentUrl());
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(247, 248, 250));
        root.addView(label("车机桌面（测试版）", 28, Color.rgb(24, 32, 45)));
        root.addView(label("手机连接车机 Wi-Fi 后，点连接；车机浏览器打开下方网址。", 16, Color.DKGRAY));
        status = label("未连接", 20, Color.rgb(38, 90, 160)); root.addView(status);
        address = label("", 18, Color.BLACK); root.addView(address);
        button(root, "连接车机", v -> {
            if (currentUrl() == null) {
                status.setText("请先连接车机 Wi-Fi");
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                return;
            }
            String size = quality.getSelectedItemPosition() == 0 ? "1200x1920" :
                    quality.getSelectedItemPosition() == 1 ? "800x1280" : "1600x2560";
            if (MirrorService.active || SplitMirrorService.active) {
                status.setText("请先断开当前连接"); return;
            }
            Intent start = new Intent(this, splitMode.isChecked() ? SplitMirrorService.class : MirrorService.class).setAction(MirrorService.ACTION_START);
            start.putExtra("size", size);
            start.putExtra("orientation", flip.isChecked() ? "@90" : "@270");
            start.putExtra("tabletMode", tabletMode.isChecked());
            start.putExtra("virtualMode", virtualMode.isChecked());
            start.putExtra("windowDecorations", windowDecorations.isChecked());
            start.putExtra("carRatio", carRatio.getSelectedItemPosition());
            startForegroundService(start);
            status.setText("正在启动");
        });
        button(root, "断开，恢复手机声音", v -> {
            startService(new Intent(this, SplitMirrorService.active ? SplitMirrorService.class : MirrorService.class)
                    .setAction(MirrorService.ACTION_STOP));
            status.setText("正在断开");
        });
        button(root, "复制车机网址", v -> {
            String url = currentUrl();
            if (url != null) {
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("车机投屏", url));
                status.setText("网址已复制");
            }
        });
        button(root, "打开 Wi-Fi 设置", v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        button(root, "设置后台运行", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        button(root, "检查更新", v -> updates.check());
        updateStatus = label("", 14, Color.rgb(38, 90, 160)); root.addView(updateStatus);
        updates = new UpdateManager(this, updateStatus);
        splitMode = new CheckBox(this);
        splitMode.setText("双应用桌面（实验）");
        root.addView(splitMode);
        LinearLayout splitOptions = new LinearLayout(this);
        splitOptions.setOrientation(LinearLayout.VERTICAL);
        splitOptions.setVisibility(View.GONE);
        splitOptions.addView(label("在车机桌面选择应用，最多同时打开两个窗口。窗口缩放暂时只改变画面大小。", 14, Color.GRAY));
        root.addView(splitOptions);
        root.addView(label("画质", 16, Color.DKGRAY));
        quality = new Spinner(this);
        String[] modes = {"标准：宽 1920", "低延迟：宽 1280", "高清：宽 2560"};
        ArrayAdapter<String> modesAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes);
        quality.setAdapter(modesAdapter); root.addView(quality);
        splitMode.setOnCheckedChangeListener((button, checked) -> {
            splitOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) quality.setSelection(1);
        });
        root.addView(label("车机屏幕比例", 16, Color.DKGRAY));
        carRatio = new Spinner(this);
        carRatio.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"16:10（推荐）", "16:9", "2:1"}));
        root.addView(carRatio);
        virtualMode = new CheckBox(this);
        virtualMode.setText("车机独立屏幕（16:10，推荐）");
        virtualMode.setChecked(true);
        root.addView(virtualMode);
        windowDecorations = new CheckBox(this);
        windowDecorations.setText("尝试系统窗口边框（拖动和缩放，需系统支持）");
        windowDecorations.setChecked(true);
        root.addView(windowDecorations);
        flip = new CheckBox(this); flip.setText("画面倒置时使用此方向"); root.addView(flip);
        tabletMode = new CheckBox(this);
        tabletMode.setText("强制平板比例（可能导致手机触屏失准）");
        tabletMode.setChecked(false);
        root.addView(tabletMode);
        virtualMode.setOnCheckedChangeListener((button, checked) -> {
            flip.setEnabled(!checked);
            tabletMode.setEnabled(!checked);
            carRatio.setEnabled(checked);
            windowDecorations.setEnabled(checked);
            if (checked) tabletMode.setChecked(false);
        });
        flip.setEnabled(false);
        tabletMode.setEnabled(false);
        root.addView(label("车机网页点“应用列表”，选择左侧或右侧窗口再选应用。拖动和缩放由手机系统提供；若黑屏或窗口不正常，关闭系统窗口边框后重连。网页点“开启车机声音”。", 14, Color.GRAY));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refreshTask); if (updates != null) updates.onResume(); }
    @Override protected void onPause() { handler.removeCallbacks(refreshTask); super.onPause(); }
    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(sp); t.setTextColor(color);
        t.setPadding(0, 0, 0, dp(16)); return t;
    }
    private void button(LinearLayout parent, String title, View.OnClickListener click) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(17);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.bottomMargin = dp(8); parent.addView(b, p); b.setOnClickListener(click);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private String component(ResolveInfo app) {
        return new android.content.ComponentName(app.activityInfo.packageName, app.activityInfo.name).flattenToString();
    }
    private String currentUrl() {
        WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null && info.getIpAddress() != 0) {
                int ip = info.getIpAddress();
                return "https://" + (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255) + ":8443/";
            }
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.getName().startsWith("wlan")) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress())
                        return "https://" + address.getHostAddress() + ":8443/";
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
