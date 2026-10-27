package com.wgj.switchquery.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import com.wgj.switchquery.auth.Announcement;

import java.util.List;

/**
 * 公告栏：把公告常驻显示在页面内（不再弹窗）。
 * 本地认证模式或没有公告时，公告栏自动隐藏。
 */
public final class AnnouncementCenter {

    private static final String PREF = "switch_announcement_read";
    /** 同时展示的公告条数上限 */
    private static final int MAX_SHOW = 3;

    private AnnouncementCenter() {
    }

    /**
     * 渲染公告栏。
     *
     * @param box     公告栏容器（LinearLayout）
     * @param content 公告内容 TextView
     * @param items   公告列表
     */
    public static void render(View box, TextView content, Context ctx, List<Announcement> items) {
        if (box == null || content == null) return;
        if (items == null || items.isEmpty()) {
            box.setVisibility(View.GONE);
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Announcement a : items) {
            if (shown >= MAX_SHOW) break;
            boolean read = sp.getBoolean("read_" + a.id, false);
            if (sb.length() > 0) sb.append("\n\n");
            if (!read) sb.append("【新】");
            sb.append("【").append(a.title).append("】");
            if (a.content != null && !a.content.trim().isEmpty()) {
                sb.append("\n").append(a.content.trim());
            }
            editor.putBoolean("read_" + a.id, true);
            shown++;
        }
        editor.apply();
        content.setText(sb.toString());
        box.setVisibility(View.VISIBLE);
    }
}
