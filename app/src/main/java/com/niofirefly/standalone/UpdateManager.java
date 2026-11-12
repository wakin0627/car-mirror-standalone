package com.niofirefly.standalone;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/** Downloads a newer GitHub release, verifies it, then hands it to Android's installer. */
final class UpdateManager {
    private static final String RELEASES = "https://api.github.com/repos/wakin0627/car-mirror-standalone/releases?per_page=20";
    private final Activity activity;
    private final TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private volatile boolean waitingForPermission;

    UpdateManager(Activity activity, TextView status) { this.activity = activity; this.status = status; }

    void check() {
        if (busy) return;
        if (MirrorService.active) { show("请先断开投屏，再安装更新"); return; }
        busy = true;
        show("正在检查 GitHub 更新…");
        new Thread(() -> {
            try { checkAndDownload(); }
            catch (Exception e) { show("更新失败：" + e.getMessage()); }
            finally { busy = false; }
        }, "car-mirror-update").start();
    }

    void onResume() {
        if (waitingForPermission && activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForPermission = false;
            install();
        }
    }

    private void checkAndDownload() throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInfo current = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        JSONArray releases = new JSONArray(readUrl(RELEASES));
        JSONObject bestAsset = null;
        long bestCode = current.getLongVersionCode();
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft")) continue;
            String tag = release.optString("tag_name");
            if (!tag.matches("v0\\.1\\.[0-9]+-test")) continue;
            long code = Long.parseLong(tag.substring(5, tag.indexOf("-test"))) + 1;
            if (code <= bestCode) continue;
            JSONArray assets = release.getJSONArray("assets");
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                if (asset.optString("name").endsWith(".apk") && asset.optString("digest").startsWith("sha256:")) {
                    bestCode = code; bestAsset = asset; break;
                }
            }
        }
        if (bestAsset == null) { show("已是最新版本（" + current.versionName + "）"); return; }
        String url = bestAsset.getString("browser_download_url");
        if (!url.startsWith("https://github.com/wakin0627/car-mirror-standalone/releases/download/"))
            throw new Exception("更新地址无效");
        show("正在下载更新…");
        File temp = new File(activity.getCacheDir(), "car-mirror-update.download.apk");
        File apk = new File(activity.getCacheDir(), UpdateFileProvider.FILE_NAME);
        if (temp.exists()) temp.delete();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection connection = open(url);
        long count = 0;
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) {
                count += n;
                if (count > 150_000_000) throw new Exception("安装包过大");
                digest.update(buffer, 0, n); out.write(buffer, 0, n);
            }
        } finally { connection.disconnect(); }
        if (count != bestAsset.getLong("size")) throw new Exception("下载大小不符");
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
        if (!bestAsset.getString("digest").equalsIgnoreCase("sha256:" + hash)) throw new Exception("安装包校验失败");
        PackageInfo update = pm.getPackageArchiveInfo(temp.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (update == null || !activity.getPackageName().equals(update.packageName) || update.getLongVersionCode() <= current.getLongVersionCode())
            throw new Exception("安装包不是本 App 的新版本");
        if (update.signingInfo == null || current.signingInfo == null ||
                !Arrays.equals(update.signingInfo.getApkContentsSigners()[0].toByteArray(), current.signingInfo.getApkContentsSigners()[0].toByteArray()))
            throw new Exception("安装包签名不匹配");
        if (apk.exists()) apk.delete();
        if (!temp.renameTo(apk)) throw new Exception("无法保存安装包");
        show("已下载 " + update.versionName + "，请确认 Android 安装提示");
        main.post(this::install);
    }

    private void install() {
        if (MirrorService.active) { show("请先断开投屏，再安装更新"); return; }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForPermission = true;
            show("请允许此 App 安装更新，返回后会继续");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return;
        }
        Uri uri = new Uri.Builder().scheme("content").authority(activity.getPackageName() + ".updates").path("apk").build();
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(uri, UpdateFileProvider.MIME);
        intent.setClipData(ClipData.newUri(activity.getContentResolver(), "CarMirror update", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivity(intent); }
        catch (Exception e) { show("无法打开安装器：" + e.getMessage()); }
    }

    private String readUrl(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            byte[] data = new byte[1_000_000]; int size = 0, n;
            while ((n = in.read(data, size, data.length - size)) > 0) size += n;
            if (size == data.length) throw new Exception("更新列表过大");
            return new String(data, 0, size, java.nio.charset.StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "CarMirrorStandalone-Android");
        if (c.getResponseCode() != 200) { int code = c.getResponseCode(); c.disconnect(); throw new Exception("HTTP " + code); }
        return c;
    }

    private void show(String message) { main.post(() -> status.setText(message)); }
}
