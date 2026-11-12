package com.niofirefly.standalone;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** App picker shown on scrcpy's independent car display. */
public final class CarLauncherActivity extends Activity {
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
        heading.setText("车机应用");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(27);
        heading.setPadding(dp(24), dp(18), dp(24), dp(14));
        root.addView(heading);
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
                startActivity(launch, options.toBundle());
            } catch (Exception e) {
                Toast.makeText(this, "无法在车机屏幕打开：" + app.loadLabel(packages), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
