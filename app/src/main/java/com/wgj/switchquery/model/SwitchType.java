package com.wgj.switchquery.model;

/**
 * 交换机厂商/型号类型
 */
public enum SwitchType {
    HUAWEI("华为"),
    H3C("H3C"),
    RUIJIE("锐捷"),
    UNKNOWN("未知");

    private final String label;

    SwitchType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
