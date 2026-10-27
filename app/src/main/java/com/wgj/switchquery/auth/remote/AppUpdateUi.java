package com.wgj.switchquery.auth.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.wgj.switchquery.auth.AuthConfig;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用内升级界面：检查更新 → 弹窗确认 → 下载（显示进度）→ 拉起系统安装。
 *
 * 全程手动触发，不自动检查、不自动下载。位于 remote 包内，
 * 删除该包时升级入口自动失效，APP 回到纯本地模式。
 */
public final class AppUpdateUi {

    /** 检查更新并提示（手动按钮触发）。 */
    public static void check(final Activity activity) {
        if (!AuthConfig.isReady()) {
            Toast.makeText(activity, "未配置服务器地址，无法检查更新", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            AppUpdateClient.Result r = new AppUpdateClient(activity).check();
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (!r.ok()) {
                    Toast.makeText(activity, r.error, Toast.LENGTH_LONG).show();
                    return;
                }
                AppRelease rel = r.release;
                if (rel == null || !rel.isAvailable()
                        || rel.versionCode <= currentVersionCode(activity)) {
                    Toast.makeText(activity,
                            "当前已是最新版本（" + currentVersionName(activity) + "）",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showUpdateDialog(activity, rel);
            });
        }).start();
    }

    private static void showUpdateDialog(final Activity activity, final AppRelease rel) {
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：").append(currentVersionName(activity)).append("\n");
        msg.append("最新版本：").append(rel.versionName);
        if (rel.size > 0) {
            msg.append("（").append(formatSize(rel.size)).append("）");
        }
        if (rel.changelog != null && !rel.changelog.trim().isEmpty()) {
            msg.append("\n\n更新说明：\n").append(rel.changelog.trim());
        }
        new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + rel.versionName)
                .setMessage(msg.toString())
                .setPositiveButton("立即更新", (d, w) -> startDownload(activity, rel))
                .setNegativeButton("稍后", null)
                .show();
    }

    private static void startDownload(final Activity activity, final AppRelease rel) {
        File dir = activity.getExternalFilesDir("update");
        if (dir == null) dir = new File(activity.getFilesDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(activity, "无法创建下载目录", Toast.LENGTH_LONG).show();
            return;
        }
        final File target = new File(dir, "app-" + rel.versionCode + ".apk");

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView tv = new TextView(activity);
        tv.setText("正在下载 0%");
        box.addView(tv);

        final ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(bar, lp);

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle("下载中")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> {
                    cancelled.set(true);
                    d.dismiss();
                })
                .create();
        dlg.show();

        new Thread(() -> {
            String err = new AppUpdateClient(activity).download(rel, target, (done, total) -> {
                if (cancelled.get()) return false;
                final int pct = total > 0 ? (int) (done * 1000 / total) : -1;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) return;
                    if (pct >= 0) {
                        bar.setProgress(pct);
                        tv.setText("正在下载 " + (pct / 10) + "%（"
                                + formatSize(done) + " / " + formatSize(total) + "）");
                    } else {
                        tv.setText("正在下载 " + formatSize(done));
                    }
                });
                return true;
            });
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (dlg.isShowing()) dlg.dismiss();
                if (err == null) {
                    install(activity, target);
                } else if (!cancelled.get()) {
                    Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static void install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity,
                    "请允许本应用安装未知应用，然后再次点击「APP 更新」",
                    Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "未找到系统安装程序", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity,
                    e.getMessage() == null ? "打开安装界面失败" : e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static long currentVersionCode(Activity activity) {
        try {
            PackageInfo pi = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private static String currentVersionName(Activity activity) {
        try {
            PackageInfo pi = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return pi.versionName == null ? "未知" : pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "未知";
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format("%.1f MB", mb);
    }

    private AppUpdateUi() {
    }
}
