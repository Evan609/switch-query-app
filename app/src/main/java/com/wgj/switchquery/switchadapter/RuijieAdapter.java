package com.wgj.switchquery.switchadapter;

import com.wgj.switchquery.model.SwitchType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 锐捷交换机适配器
 */
public class RuijieAdapter implements SwitchAdapter {

    @Override
    public SwitchType getType() {
        return SwitchType.RUIJIE;
    }

    @Override
    public String getDetectCommand() {
        // 锐捷使用 show 系列命令
        return "show version";
    }

    @Override
    public boolean matches(String versionOutput) {
        if (versionOutput == null) return false;
        String lower = versionOutput.toLowerCase();
        return lower.contains("ruijie") || lower.contains("rgos")
                || lower.contains("star-os") || lower.contains("red-giant");
    }

    @Override
    public String extractModel(String output) {
        if (output == null) return "未知";
        // 锐捷描述行典型：Ruijie Full Gigabit ... Switch (S2928G-24P) By Ruijie Networks
        // 真正型号在括号内（"Ruijie" 后直接取词会误取到 Full 等形容词）
        Matcher m = Pattern.compile("\\(([A-Za-z]{1,4}\\d[A-Za-z0-9\\-]*)\\)").matcher(output);
        if (m.find()) return m.group(1).trim();
        // 兜底：Ruijie 之后第一个"字母开头且含数字"的词（型号形态，跳过 Full/Gigabit 等）
        m = Pattern.compile("(?i)Ruijie\\s+[^\\n]*?\\b([A-Za-z]{1,4}\\d{2,}[A-Za-z0-9\\-]*)\\b")
                .matcher(output);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("(?i)System\\s+name\\s*[:：]\\s*(\\S+)").matcher(output);
        if (m.find()) return m.group(1).trim();
        return "锐捷交换机";
    }

    @Override
    public String extractVersion(String output) {
        if (output == null) return "未知";
        // 锐捷行：Software version : S6500-X86_RGOS 11.0(5)B9P128
        // 版本号含空格，取冒号后整行（不能用 \S+，会截断为空格前部分）
        Matcher m = Pattern.compile("(?im)software\\s+version\\s*[:：]\\s*(.+)$").matcher(output);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("(?im)version\\s*[:：]\\s*(.+)$").matcher(output);
        if (m.find()) return m.group(1).trim();
        return "未知版本";
    }

    @Override
    public String getPortListCommand() {
        // show interfaces description 一次返回：端口名 + 物理 up/down + 管理状态 + description
        return "show interfaces description";
    }

    @Override
    public String getVersionCommand() {
        return "show version";
    }

    @Override
    public String getAllVlansCommand() {
        return "show vlan";
    }

    @Override
    public String getVlanIpCommand(int vlanId) {
        return "show interface vlan " + vlanId;
    }

    @Override
    public String getPortUpCommand(String port) {
        return "show interfaces " + port;
    }

    @Override
    public String getPortVlanCommand(String port) {
        return "show interfaces " + port + " switchport";
    }

    @Override
    public String getPortOpticalCommand(String port) {
        return "show interfaces " + port + " transceiver";
    }

    @Override
    public String getPortErrorsCommand(String port) {
        return "show interfaces " + port;
    }

    @Override
    public String getAllConfigCommand() {
        return "show running-config";
    }

    @Override
    public String getExitCommand() {
        return "exit";
    }
}
