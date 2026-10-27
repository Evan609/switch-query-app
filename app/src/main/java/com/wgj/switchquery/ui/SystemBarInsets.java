package com.wgj.switchquery.ui;

import android.os.Build;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 系统栏避让工具。
 *
 * Android 15（API 35）起，targetSdk 35 的应用强制全屏边到边绘制，
 * 页面内容会延伸到状态栏和导航栏下方。顶部由布局里的 fitsSystemWindows 处理，
 * 底部（虚拟返回键/手势条）在这里按实际高度自动留出空间：
 *   - 三键导航：留出三个按键的高度
 *   - 手势导航：只留出手势条的高度
 * 键盘弹出时同样避让，避免输入框被遮挡。
 */
public final class SystemBarInsets {

    private SystemBarInsets() {
    }

    public static void applyBottom(View content) {
        if (content == null) return;
        // Android 15 以下不会绘制到系统栏下方，无需额外处理
        if (Build.VERSION.SDK_INT < 35) return;
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
