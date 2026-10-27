package com.wgj.switchquery.ui;

import android.app.Activity;
import android.widget.Toast;

/**
 * 「APP 更新」入口。真正的实现在 auth.remote 包内，通过反射调用，
 * 这样删除服务端认证模块时不需要改动界面代码。
 */
public final class AppUpdateEntry {

    private static final String IMPL = "com.wgj.switchquery.auth.remote.AppUpdateUi";

    public static boolean supported() {
        try {
            Class.forName(IMPL);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void check(Activity activity) {
        try {
            Class.forName(IMPL).getMethod("check", Activity.class).invoke(null, activity);
        } catch (Throwable t) {
            Toast.makeText(activity, "当前版本不支持在线更新", Toast.LENGTH_SHORT).show();
        }
    }

    private AppUpdateEntry() {
    }
}
