package com.wgj.switchquery.switchadapter;

import com.wgj.switchquery.model.SwitchType;

import java.util.ArrayList;
import java.util.List;

/**
 * 适配器工厂与型号识别器
 */
public class SwitchAdapterFactory {

    private static final List<SwitchAdapter> ADAPTERS = new ArrayList<>();

    static {
        ADAPTERS.add(new HuaweiAdapter());
        ADAPTERS.add(new H3cAdapter());
        ADAPTERS.add(new RuijieAdapter());
    }

    /**
     * 根据版本输出自动识别交换机型号，返回对应适配器。
     * 策略：先用 display version 尝试，匹配不到再用 show version。
     */
    public static SwitchAdapter detect(String versionOutput) {
        if (versionOutput == null) return null;
        for (SwitchAdapter adapter : ADAPTERS) {
            if (adapter.matches(versionOutput)) {
                return adapter;
            }
        }
        return null;
    }

    public static SwitchAdapter getByType(SwitchType type) {
        for (SwitchAdapter adapter : ADAPTERS) {
            if (adapter.getType() == type) return adapter;
        }
        return null;
    }

    /**
     * 获取探测命令列表。连接后按顺序执行直到成功识别。
     */
    public static List<String> getDetectCommands() {
        List<String> cmds = new ArrayList<>();
        // 华为/H3C 通用
        cmds.add("display version");
        // 锐捷
        cmds.add("show version");
        return cmds;
    }
}
