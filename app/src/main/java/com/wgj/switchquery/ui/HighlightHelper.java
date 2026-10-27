package com.wgj.switchquery.ui;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结果信息高亮：按信息类别着色，重点信息醒目显示。
 * 仅在显示层处理，不影响解析逻辑。
 */
public class HighlightHelper {

    private static final int COLOR_OK = Color.parseColor("#1B8A2F");     // 绿：正常/UP
    private static final int COLOR_BAD = Color.parseColor("#D32F2F");    // 红：异常/DOWN/错误
    private static final int COLOR_VALUE = Color.parseColor("#1565C0");  // 蓝：数值/地址
    private static final int COLOR_LABEL = Color.parseColor("#5D4037");  // 棕：项目名标签

    private static final Pattern P_OK = Pattern.compile(
            "UP|正常|OK|已连接|成功|connected", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_BAD = Pattern.compile(
            "DOWN|管理性关闭|错误|失败|未获取|异常|disconnected", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_VALUE = Pattern.compile(
            "-?\\d+\\.?\\d*\\s*dBm"                          // 光功率
            + "|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"              // IP 地址
            + "|\\b\\d{1,3}(\\.\\d{1,3}){3}\\s*/\\s*\\d{1,2}\\b" // 前缀形式
            + "|(?<=VLAN[：:\\s])\\d{1,4}\\b"                // VLAN ID
            + "|(?<=Native\\s)\\d{1,4}\\b");
    private static final Pattern P_LABEL = Pattern.compile(
            "^(端口状态|端口VLAN|RX 光功率|TX 光功率|错误包总数|IP|掩码|型号|版本|补丁"
            + "|运行时间|光功率（共 \\d+ 通道）|通道\\d+|放行|Native|VLAN 列表|共 \\d+ 个 VLAN)"
            + "[：:]?", Pattern.MULTILINE);

    /** 为结果文本设置高亮并填入 TextView。 */
    public static void applyTo(TextView tv, String text) {
        if (text == null) text = "";
        SpannableString ss = new SpannableString(text);
        // 先标值（数字类），再标状态词，最后标标签；后标的不覆盖先标的重叠区域
        span(ss, P_VALUE, COLOR_VALUE, true);
        span(ss, P_OK, COLOR_OK, true);
        span(ss, P_BAD, COLOR_BAD, true);
        span(ss, P_LABEL, COLOR_LABEL, true);
        tv.setText(ss);
    }

    private static void span(SpannableString ss, Pattern p, int color, boolean bold) {
        Matcher m = p.matcher(ss);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (start >= end) continue;
            // 已被着色（前景色）的区域跳过，避免互相覆盖
            ForegroundColorSpan[] existing = ss.getSpans(start, end, ForegroundColorSpan.class);
            if (existing.length > 0) continue;
            ss.setSpan(new ForegroundColorSpan(color), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) {
                ss.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }
}
