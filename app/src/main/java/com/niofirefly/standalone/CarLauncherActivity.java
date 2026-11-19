package com.niofirefly.standalone;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import java.util.ArrayList;
import java.util.List;

/** App picker shown on scrcpy's independent car display. */
public final class CarLauncherActivity extends Activity {
    private int windowMode = 0;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PackageManager packages = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(packages.queryIntentActivities(query, 0));
        found.removeIf(info -> getPackageName().equals(info.activityInfo.packageName));
        found.sort((a, b) -> String.valueOf(a.loadLabel(packages)).compareToIgnoreCase(String.valueOf(b.loadLabel(packages))));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 25, 32));
        TextView heading = new TextView(this);
        heading.setText("车机桌面 · 选择应用");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(27);
        heading.setPadding(dp(24), dp(18), dp(24), dp(14));
        root.addView(heading);
        LinearLayout modeRow = new LinearLayout(this);
        TextView modeLabel = new TextView(this);
        modeLabel.setTextColor(Color.WHITE);
        modeLabel.setTextSize(16);
        modeLabel.setText("打开方式：全屏");
        Button fullscreen = new Button(this); fullscreen.setText("全屏");
        Button left = new Button(this); left.setText("左侧窗口");
        Button right = new Button(this); right.setText("右侧窗口");
        fullscreen.setOnClickListener(v -> { windowMode = 0; modeLabel.setText("打开方式：全屏"); });
        left.setOnClickListener(v -> { windowMode = 1; modeLabel.setText("打开方式：左侧窗口（实验）"); });
        right.setOnClickListener(v -> { windowMode = 2; modeLabel.setText("打开方式：右侧窗口（实验）"); });
        modeRow.addView(fullscreen); modeRow.addView(left); modeRow.addView(right);
        root.addView(modeRow);
        root.addView(modeLabel);
        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(10));
        grid.setHorizontalSpacing(dp(10));
        grid.setPadding(dp(18), dp(6), dp(18), dp(18));
        grid.setClipToPadding(false);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return found.size(); }
            @Override public Object getItem(int position) { return found.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                ResolveInfo info = found.get(position);
                LinearLayout item = new LinearLayout(CarLauncherActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(android.view.Gravity.CENTER);
                item.setPadding(dp(6), dp(10), dp(6), dp(10));
                ImageView icon = new ImageView(CarLauncherActivity.this);
                icon.setImageDrawable(info.loadIcon(packages));
                item.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
                TextView label = new TextView(CarLauncherActivity.this);
                label.setText(info.loadLabel(packages));
                label.setTextColor(Color.WHITE);
                label.setTextSize(16);
                label.setGravity(android.view.Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(28));
                params.topMargin = dp(8);
                item.addView(label, params);
                return item;
            }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            ResolveInfo app = found.get(position);
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setComponent(new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(getDisplay().getDisplayId());
                Bundle bundle;
                if (windowMode != 0) {
                    Point size = new Point();
                    getDisplay().getRealSize(size);
                    int half = size.x / 2;
                    options.setLaunchBounds(windowMode == 1
                            ? new Rect(0, 0, half, size.y) : new Rect(half, 0, size.x, size.y));
                    bundle = options.toBundle();
                    if (bundle == null) bundle = new Bundle();
                    // Android may reject or ignore freeform on displays that do not support it.
                    bundle.putInt("android.activity.windowingMode", 5);
                } else bundle = options.toBundle();
                startActivity(launch, bundle);
            } catch (Exception e) {
                Toast.makeText(this, "无法在车机屏幕打开：" + app.loadLabel(packages), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
