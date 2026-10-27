package com.wgj.switchquery.switchadapter;

import com.wgj.switchquery.model.SwitchType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * H3C 交换机适配器
 */
public class H3cAdapter implements SwitchAdapter {

    @Override
    public SwitchType getType() {
        return SwitchType.H3C;
    }

    @Override
    public String getDetectCommand() {
        return "display version";
    }

    @Override
    public boolean matches(String versionOutput) {
        if (versionOutput == null) return false;
        String lower = versionOutput.toLowerCase();
        return lower.contains("h3c") || lower.contains("comware")
                || lower.contains("new h3c") || lower.contains("hangzhou h3c");
    }

    @Override
    public String extractModel(String output) {
        if (output == null) return "未知";
        // 优先匹配 "XXX uptime is" 形式（型号紧跟在 uptime 前）
        Matcher m = Pattern.compile("(?im)^\\s*([A-Za-z][A-Za-z0-9\\-]*)\\s+uptime\\s+is").matcher(output);
        if (m.find()) {
            String model = m.group(1).trim();
            if (!model.equalsIgnoreCase("H3C")) return model;
        }
        // "H3C S6520X-54XG-EI-G" 形式：跳过 Comware、Technologies 等公司名，要求含数字
        m = Pattern.compile("(?i)H3C\\s+(?!Comware)(?!Technologies)([A-Za-z0-9][A-Za-z0-9\\-]*\\d[A-Za-z0-9\\-]*)").matcher(output);
        if (m.find()) return m.group(1).trim();
        // 兜底：H3C 后第一个非公司名词
        m = Pattern.compile("(?i)H3C\\s+(?!Comware)(?!Technologies)(?!Co)(?!Ltd)([A-Za-z0-9][A-Za-z0-9\\-]*)").matcher(output);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("(?i)Model\\s+Number\\s*[:：]\\s*(\\S+)").matcher(output);
        if (m.find()) return m.group(1).trim();
        return "H3C交换机";
    }

    @Override
    public String extractVersion(String output) {
        if (output == null) return "未知";
        Matcher m = Pattern.compile("(?i)Version\\s+(\\S+)").matcher(output);
        if (m.find()) return m.group(1).replaceAll(",$", "").trim();
        return "未知版本";
    }

    @Override
    public String getPortListCommand() {
        return "display interface brief";
    }

    @Override
    public String getVersionCommand() {
        return "display version";
    }

    @Override
    public String getAllVlansCommand() {
        return "display vlan";
    }

    @Override
    public String getVlanIpCommand(int vlanId) {
        return "display interface Vlan-interface " + vlanId;
    }

    @Override
    public String getPortUpCommand(String port) {
        return "display interface " + port;
    }

    @Override
    public String getPortVlanCommand(String port) {
        // H3C 的 display port vlan 各版本支持不一致，统一用 display interface，
        // 输出含 PVID / tagged / untagged VLAN 行，解析器可识别
        return "display interface " + port;
    }

    @Override
    public String getPortOpticalCommand(String port) {
        // display transceiver interface 只显示模块基本信息；
        // 当前 RX/TX 光功率在 diagnosis 子命令里
        return "display transceiver diagnosis interface " + port;
    }

    @Override
    public String getPortErrorsCommand(String port) {
        return "display interface " + port;
    }

    @Override
    public String getAllConfigCommand() {
        return "display current-configuration";
    }

    @Override
    public String getExitCommand() {
        return "quit";
    }
}
