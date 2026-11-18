package com.comic.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 安卓客户端在线升级与自动安装管理器
 * 遵循 Google Material Design 3 视觉设计与交互规范
 */
public class ApkUpdateManager {

    public static final int REQUEST_INSTALL_PERM_CODE = 4099;
    private static final String PREF_NAME = "comic_app_update_prefs";
    private static final String KEY_PENDING_APK = "pending_apk_path";

    private static String sPendingApkPath = null;
    private static AlertDialog sCurrentDialog = null;

    public static class UpdateInfo {
        public int versionCode;
        public String versionName;
        public long fileSize;
        public String changelog;
        public String downloadUrl;
        public boolean force;

        public static UpdateInfo fromJson(JSONObject obj) {
            if (obj == null) return null;
            UpdateInfo info = new UpdateInfo();
            info.versionCode = obj.optInt("versionCode", 0);
            info.versionName = obj.optString("versionName", "");
            info.fileSize = obj.optLong("fileSize", 0);
            info.changelog = obj.optString("changelog", "有新的版本可用，建议更新。");
            info.downloadUrl = obj.optString("downloadUrl", "");
            info.force = obj.optBoolean("force", false);
            return info;
        }
    }

    public static int getLocalVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Exception e) {
            return 1;
        }
    }

    public static String getLocalVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "1.0.0";
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    /**
     * 主动向 Windows 端查询是否有可用安卓端更新
     */
    public interface CheckUpdateCallback {
        void onResult(UpdateInfo info, boolean hasUpdate);
    }

    /**
     * 主动向局域网（或已连接电脑端）检查并拉取最新更新包。
     * 如果有最新的软件包（versionCode > 本地版本）则弹出更新对话框并支持一键下载安装；
     * 如果没有最新软件包，则提示当前已是最新版本，不执行更新。
     */
    public static void checkAndPullUpdate(
            Activity activity,
            LanTransferManager lanTransferManager,
            LanTransferManager.PeerInfo currentPeer,
            java.util.function.Consumer<Boolean> onFinished) {
        if (activity == null || activity.isFinishing()) return;

        Toast.makeText(activity, "🔍 正在检查电脑端是否有最新版本...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            int localCode = getLocalVersionCode(activity);
            String localName = getLocalVersionName(activity);

            // 搜集候选查询目标 (IP + Port)
            java.util.LinkedHashMap<String, Integer> targets = new java.util.LinkedHashMap<>();

            // 1. 优先使用当前连接的设备
            if (currentPeer != null && currentPeer.ip != null && !currentPeer.ip.isEmpty()) {
                int port = currentPeer.port > 0 ? currentPeer.port : LanTransferManager.DEFAULT_HTTP_PORT;
                targets.put(currentPeer.ip, port);
            }

            // 2. 其次查询已探测到的局域网可用设备
            if (lanTransferManager != null) {
                try {
                    java.util.List<LanTransferManager.PeerInfo> discovered = lanTransferManager.discoverPeers();
                    if (discovered != null) {
                        for (LanTransferManager.PeerInfo p : discovered) {
                            if (p != null && p.ip != null && !p.ip.isEmpty() && !lanTransferManager.isSelf(p) && !lanTransferManager.isSelfIp(p.ip)) {
                                int port = p.port > 0 ? p.port : LanTransferManager.DEFAULT_HTTP_PORT;
                                targets.put(p.ip, port);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 3. 兜底查询历史连接过的电脑 IP
            try {
                SharedPreferences sp = activity.getSharedPreferences("lan_settings", Context.MODE_PRIVATE);
                String lastIp = sp.getString("last_peer_ip", "");
                if (lastIp != null && !lastIp.isEmpty()) {
                    targets.put(lastIp, LanTransferManager.DEFAULT_HTTP_PORT);
                }
            } catch (Exception ignored) {}

            if (targets.isEmpty()) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(activity, "⚠️ 未在局域网内检测到电脑端，请确保电脑已启动 YY漫画阅读器 并连接同一 Wi-Fi", Toast.LENGTH_LONG).show();
                    if (onFinished != null) onFinished.accept(false);
                });
                return;
            }

            UpdateInfo foundNewer = null;
            boolean anyServerResponded = false;
            String latestServerVersion = "";

            for (java.util.Map.Entry<String, Integer> entry : targets.entrySet()) {
                String ip = entry.getKey();
                int port = entry.getValue();
                try {
                    URL url = new URL("http://" + ip + ":" + port + "/api/check-update");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(4000);
                    conn.setRequestMethod("GET");
                    if (conn.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();

                        JSONObject obj = new JSONObject(sb.toString());
                        UpdateInfo info = UpdateInfo.fromJson(obj);
                        anyServerResponded = true;
                        if (info != null) {
                            latestServerVersion = info.versionName;
                            // 检查是否有最新版本（versionCode 大于本地版本）
                            if (info.versionCode > localCode && info.downloadUrl != null && !info.downloadUrl.isEmpty()) {
                                foundNewer = info;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            final UpdateInfo newer = foundNewer;
            final boolean responded = anyServerResponded;
            final String serverVer = latestServerVersion;

            new Handler(Looper.getMainLooper()).post(() -> {
                if (newer != null) {
                    Toast.makeText(activity, "🎉 发现最新版本 v" + newer.versionName + "，准备更新！", Toast.LENGTH_SHORT).show();
                    showUpdateDialog(activity, newer);
                    if (onFinished != null) onFinished.accept(true);
                } else if (responded) {
                    Toast.makeText(activity, "当前已是最新版本 (v" + localName + " Build " + localCode + ")，无需更新", Toast.LENGTH_SHORT).show();
                    if (onFinished != null) onFinished.accept(false);
                } else {
                    Toast.makeText(activity, "⚠️ 未能连接到电脑端更新服务，请检查网络设置与电脑端服务状态", Toast.LENGTH_LONG).show();
                    if (onFinished != null) onFinished.accept(false);
                }
            });
        }).start();
    }

    public static void checkRemoteUpdateAsync(Context context, String remoteIp, int port, CheckUpdateCallback callback) {
        new Thread(() -> {
            try {
                URL url = new URL("http://" + remoteIp + ":" + port + "/api/check-update");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject obj = new JSONObject(sb.toString());
                    UpdateInfo info = UpdateInfo.fromJson(obj);
                    int localCode = getLocalVersionCode(context);
                    boolean hasUpdate = info != null && info.versionCode > localCode && info.downloadUrl != null && !info.downloadUrl.isEmpty();
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (callback != null) callback.onResult(info, hasUpdate);
                    });
                    return;
                }
            } catch (Exception ignored) {}
            new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) callback.onResult(null, false);
            });
        }).start();
    }

    /**
     * 弹出 Material Design 3 风格更新对话框
     */
    public static void showUpdateDialog(Activity activity, UpdateInfo info) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || info == null) return;

        int localCode = getLocalVersionCode(activity);
        // 核心要求：若非手动强制推送，当本地版本已是最新或更高时，静默退出
        if (info.versionCode <= localCode && !info.force) {
            return;
        }

        // 如果已有弹窗在展示，若新推送来临，先安全 dismiss 旧弹窗以保证显示最新推送
        if (sCurrentDialog != null && sCurrentDialog.isShowing()) {
            try { sCurrentDialog.dismiss(); } catch (Exception ignored) {}
        }

        String localName = getLocalVersionName(activity);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(activity, 20);
        root.setPadding(pad, pad, pad, pad);

        // M3 深色弹窗背景与轻微圆角
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1A1D26"));
        bg.setCornerRadius(dp2px(activity, 20));
        bg.setStroke(dp2px(activity, 1), Color.parseColor("#2A2F42"));
        root.setBackground(bg);

        // 标题行
        TextView tvTitle = new TextView(activity);
        if (info.versionCode <= localCode) {
            tvTitle.setText("🚀 收到安装包重新安装推送");
        } else {
            tvTitle.setText("🚀 发现安卓端新版本");
        }
        tvTitle.setTextColor(Color.parseColor("#F8FAFC"));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(tvTitle);

        // 版本信息芯片/副标题
        TextView tvSub = new TextView(activity);
        String sizeStr = formatFileSize(info.fileSize);
        if (info.versionCode <= localCode) {
            tvSub.setText("目标版本: v" + info.versionName + " (当前已是: v" + localName + ") · " + sizeStr);
        } else {
            tvSub.setText("最新版本: v" + info.versionName + " (当前: v" + localName + ") · " + sizeStr);
        }
        tvSub.setTextColor(Color.parseColor("#818CF8"));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvSub.setPadding(0, dp2px(activity, 6), 0, dp2px(activity, 12));
        root.addView(tvSub);

        // 更新日志卡片
        ScrollView sv = new ScrollView(activity);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 130)));
        GradientDrawable changelogBg = new GradientDrawable();
        changelogBg.setColor(Color.parseColor("#121318"));
        changelogBg.setCornerRadius(dp2px(activity, 12));
        changelogBg.setStroke(dp2px(activity, 1), Color.parseColor("#232736"));
        sv.setBackground(changelogBg);
        sv.setPadding(dp2px(activity, 12), dp2px(activity, 10), dp2px(activity, 12), dp2px(activity, 10));

        TextView tvChangelog = new TextView(activity);
        tvChangelog.setText(info.changelog != null && !info.changelog.trim().isEmpty() ? info.changelog : "优化性能与局域网互传体验。");
        tvChangelog.setTextColor(Color.parseColor("#CBD5E1"));
        tvChangelog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvChangelog.setLineSpacing(dp2px(activity, 4), 1.0f);
        sv.addView(tvChangelog);
        root.addView(sv);

        // 进度区域 (初始隐藏)
        LinearLayout progressLayout = new LinearLayout(activity);
        progressLayout.setOrientation(LinearLayout.VERTICAL);
        progressLayout.setVisibility(View.GONE);
        progressLayout.setPadding(0, dp2px(activity, 14), 0, dp2px(activity, 4));

        TextView tvProgressStatus = new TextView(activity);
        tvProgressStatus.setText("正在极速下载安装包...");
        tvProgressStatus.setTextColor(Color.parseColor("#94A3B8"));
        tvProgressStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        progressLayout.addView(tvProgressStatus);

        ProgressBar progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(activity, 8)));
        progressLayout.addView(progressBar);

        root.addView(progressLayout);

        // 按钮栏
        LinearLayout btnBar = new LinearLayout(activity);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);
        btnBar.setPadding(0, dp2px(activity, 16), 0, 0);

        Button btnCancel = new Button(activity);
        btnCancel.setText("稍后");
        btnCancel.setTextColor(Color.parseColor("#94A3B8"));
        btnCancel.setBackgroundColor(Color.TRANSPARENT);
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        Button btnUpdate = new Button(activity);
        btnUpdate.setText(info.versionCode <= localCode ? "覆盖安装" : "立即在线更新");
        btnUpdate.setTextColor(Color.WHITE);
        btnUpdate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnUpdate.setTypeface(null, android.graphics.Typeface.BOLD);
        GradientDrawable updateBtnBg = new GradientDrawable();
        updateBtnBg.setColor(Color.parseColor("#6366F1"));
        updateBtnBg.setCornerRadius(dp2px(activity, 999));
        btnUpdate.setBackground(updateBtnBg);
        int padH = dp2px(activity, 18);
        btnUpdate.setPadding(padH, 0, padH, 0);

        btnBar.addView(btnCancel);
        View spacer = new View(activity);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp2px(activity, 10), 1));
        btnBar.addView(spacer);
        btnBar.addView(btnUpdate);
        root.addView(btnBar);

        builder.setView(root);
        builder.setCancelable(true);
        AlertDialog dialog = builder.create();
        sCurrentDialog = dialog;

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnUpdate.setOnClickListener(v -> {
            btnCancel.setVisibility(View.GONE);
            btnUpdate.setEnabled(false);
            btnUpdate.setText("正在下载...");
            GradientDrawable disabledBg = new GradientDrawable();
            disabledBg.setColor(Color.parseColor("#3730A3"));
            disabledBg.setCornerRadius(dp2px(activity, 999));
            btnUpdate.setBackground(disabledBg);
            progressLayout.setVisibility(View.VISIBLE);

            startDownloadAndInstall(activity, info, new DownloadCallback() {
                @Override
                public void onProgress(int percent, long currentBytes, long totalBytes) {
                    activity.runOnUiThread(() -> {
                        progressBar.setProgress(percent);
                        tvProgressStatus.setText("正在下载: " + percent + "% (" + formatFileSize(currentBytes) + " / " + formatFileSize(totalBytes) + ")");
                    });
                }

                @Override
                public void onSuccess(File apkFile) {
                    activity.runOnUiThread(() -> {
                        progressBar.setProgress(100);
                        tvProgressStatus.setText("下载完成，正在调起系统安装器...");
                        dialog.dismiss();
                        installApk(activity, apkFile);
                    });
                }

                @Override
                public void onError(String error) {
                    activity.runOnUiThread(() -> {
                        progressLayout.setVisibility(View.GONE);
                        btnCancel.setVisibility(View.VISIBLE);
                        btnUpdate.setEnabled(true);
                        btnUpdate.setText("重试下载更新");
                        btnUpdate.setBackground(updateBtnBg);
                        Toast.makeText(activity, "下载更新失败: " + error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        dialog.show();
    }

    public interface DownloadCallback {
        void onProgress(int percent, long currentBytes, long totalBytes);
        void onSuccess(File apkFile);
        void onError(String error);
    }

    /**
     * 后台下载更新 APK
     */
    public static void startDownloadAndInstall(Activity activity, UpdateInfo info, DownloadCallback callback) {
        new Thread(() -> {
            File destFile = null;
            try {
                URL url = new URL(info.downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.connect();

                if (conn.getResponseCode() != 200) {
                    if (callback != null) callback.onError("HTTP " + conn.getResponseCode());
                    return;
                }

                long total = conn.getContentLength();
                if (total <= 0) total = info.fileSize;

                File cacheDir = activity.getExternalCacheDir();
                if (cacheDir == null) cacheDir = activity.getCacheDir();
                destFile = new File(cacheDir, "YYComicReader_v" + info.versionName + ".apk");
                if (destFile.exists()) {
                    destFile.delete();
                }

                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(destFile);

                byte[] buf = new byte[16384];
                long downloaded = 0;
                int len;
                long lastNotifyTime = 0;

                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                    downloaded += len;

                    long now = System.currentTimeMillis();
                    if (now - lastNotifyTime > 100 || downloaded == total) {
                        lastNotifyTime = now;
                        int pct = total > 0 ? (int) ((downloaded * 100) / total) : 0;
                        if (pct > 100) pct = 100;
                        if (callback != null) {
                            callback.onProgress(pct, downloaded, total);
                        }
                    }
                }

                out.flush();
                out.close();
                in.close();

                if (callback != null) {
                    callback.onSuccess(destFile);
                }
            } catch (Exception e) {
                if (destFile != null && destFile.exists()) {
                    destFile.delete();
                }
                if (callback != null) {
                    callback.onError(e.getMessage() != null ? e.getMessage() : "网络异常");
                }
            }
        }).start();
    }

    /**
     * 调起系统 PackageInstaller 安装或覆盖安装 APK
     */
    public static void installApk(Activity activity, File apkFile) {
        if (activity == null || apkFile == null || !apkFile.exists()) {
            if (activity != null) Toast.makeText(activity, "安装包文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查 Android 8.0+ 未知来源应用安装权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean canInstall = activity.getPackageManager().canRequestPackageInstalls();
            if (!canInstall) {
                sPendingApkPath = apkFile.getAbsolutePath();
                savePendingApk(activity, sPendingApkPath);
                Toast.makeText(activity, "请允许本应用安装应用权限以完成升级", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                try {
                    activity.startActivityForResult(intent, REQUEST_INSTALL_PERM_CODE);
                } catch (Exception e) {
                    // 部分定制系统可能不支持精确包名 URI
                    activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES), REQUEST_INSTALL_PERM_CODE);
                }
                return;
            }
        }

        // 拥有权限，即将调起系统安装器：必须立即清除 pending 记录，绝不在 onResume 中死循环调用！
        clearPendingApk(activity);

        // 调起系统安装程序
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri apkUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "调起系统安装器失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 当从系统授权页面返回时，如果已经授权则继续执行安装
     */
    public static void checkPendingInstallOnResume(Activity activity) {
        if (activity == null) return;
        String pendingPath = sPendingApkPath != null ? sPendingApkPath : getPendingApk(activity);
        if (pendingPath == null) return;

        File apkFile = new File(pendingPath);
        if (!apkFile.exists()) {
            clearPendingApk(activity);
            return;
        }

        // 检查当前应用版本：如果当前应用已安装该版本或更新版本，彻底清除并删除缓存安装包！
        try {
            PackageInfo pInfo = activity.getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (pInfo != null) {
                int apkCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? (int) pInfo.getLongVersionCode() : pInfo.versionCode;
                int currentCode = getLocalVersionCode(activity);
                if (currentCode >= apkCode) {
                    clearPendingApk(activity);
                    try { apkFile.delete(); } catch (Exception ignored) {}
                    return;
                }
            }
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (activity.getPackageManager().canRequestPackageInstalls()) {
                clearPendingApk(activity);
                installApk(activity, apkFile);
            }
        } else {
            clearPendingApk(activity);
            installApk(activity, apkFile);
        }
    }

    private static void savePendingApk(Context context, String path) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_PENDING_APK, path).apply();
        } catch (Exception ignored) {}
    }

    private static String getPendingApk(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            return sp.getString(KEY_PENDING_APK, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void clearPendingApk(Context context) {
        sPendingApkPath = null;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().remove(KEY_PENDING_APK).apply();
        } catch (Exception ignored) {}
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.getDefault(), "%.2f MB", mb);
    }

    private static int dp2px(Context context, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }
}
