package com.wgj.switchquery.switchadapter;

import com.wgj.switchquery.model.SwitchType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 华为交换机适配器
 */
public class HuaweiAdapter implements SwitchAdapter {

    @Override
    public SwitchType getType() {
        return SwitchType.HUAWEI;
    }

    @Override
    public String getDetectCommand() {
        return "display version";
    }

    @Override
    public boolean matches(String versionOutput) {
        if (versionOutput == null) return false;
        String lower = versionOutput.toLowerCase();
        return lower.contains("huawei") || lower.contains("vrp")
                || lower.contains("huawei technologies");
    }

    @Override
    public String extractModel(String output) {
        if (output == null) return "未知";
        // 华为版本输出中典型行：HUAWEI S5700-28P-LI-AC ...
        Matcher m = Pattern.compile("(?i)HUAWEI\\s+([A-Za-z0-9\\-]+)").matcher(output);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("(?i)Model\\s+Number\\s*[:：]\\s*(\\S+)").matcher(output);
        if (m.find()) return m.group(1).trim();
        return "华为交换机";
    }

    @Override
    public String extractVersion(String output) {
        if (output == null) return "未知";
        Matcher m = Pattern.compile("(?i)Version\\s+(\\S+)").matcher(output);
        if (m.find()) return m.group(1).trim();
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
        return "display interface Vlanif " + vlanId;
    }

    @Override
    public String getPortUpCommand(String port) {
        return "display interface " + port;
    }

    @Override
    public String getPortVlanCommand(String port) {
        return "display port vlan " + port;
    }

    @Override
    public String getPortOpticalCommand(String port) {
        return "display transceiver interface " + port;
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
