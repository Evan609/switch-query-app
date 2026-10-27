package com.wgj.switchquery.model;

/**
 * 端口描述符：把「发命令用的原始名」和「界面展示用的名字」分开。
 * rawName 原样保留给交换机命令行；displayName 用于界面展示（前缀缩写 + description）。
 */
public class PortDescriptor {

    /** 原始端口名，命令行永远用这个，例："TFGigabitEthernet 0/1" */
    public final String rawName;
    /** 界面展示名，例："[TFGE] 测试" 或 "[GE] 1/0/1" */
    public String displayName;
    /** 原始 description（已清洗，可空） */
    public String description;
    /** 物理状态："up" / "down" / 其他值（unknown） */
    public String status;
    /** 管理状态（shutdown/no shutdown），可空；H3C 可能没有 */
    public String adminStatus;
    /** 前缀缩写，例："GE"、"10GE"、"TFGE"、"100GE" */
    public final String prefixAbbr;
    /** 编号部分，例："1/0/1"、"0/1" */
    public final String portNumber;
    /** 完整前缀类型，例："TFGigabitEthernet"、"GigabitEthernet" */
    public final String prefixType;

    public PortDescriptor(String rawName, String prefixType, String prefixAbbr,
                          String portNumber, String description, String status, String adminStatus) {
        this.rawName = rawName;
        this.prefixType = prefixType;
        this.prefixAbbr = prefixAbbr;
        this.portNumber = portNumber;
        this.description = description;
        this.status = status == null ? "unknown" : status;
        this.adminStatus = adminStatus;
        // 生成 displayName
        if (description != null && !description.isEmpty()) {
            this.displayName = "[" + prefixAbbr + "] " + description;
        } else {
            this.displayName = "[" + prefixAbbr + "] " + portNumber;
        }
    }

    /** 状态颜色枚举（给界面用） */
    public enum StatusColor {
        GREEN,      // up
        DEFAULT,    // down
        RED         // 其他（unknown/shutdown 异常值）
    }

    public StatusColor getStatusColor() {
        if ("up".equalsIgnoreCase(status)) return StatusColor.GREEN;
        if ("down".equalsIgnoreCase(status)) return StatusColor.DEFAULT;
        return StatusColor.RED;
    }
}
