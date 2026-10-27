package com.wgj.switchquery.switchadapter;

import com.wgj.switchquery.model.SwitchType;

/**
 * 交换机命令适配器。每个厂商实现此接口，提供对应的查询命令。
 * 所有命令均为只读查询，不包含任何配置修改命令。
 */
public interface SwitchAdapter {

    /** 返回该适配器对应的交换机型号 */
    SwitchType getType();

    /** 用于型号识别的命令（连接后首先执行） */
    String getDetectCommand();

    /** 判断给定的版本输出是否匹配此适配器 */
    boolean matches(String versionOutput);

    /** 从版本输出中提取交换机型号名称 */
    String extractModel(String versionOutput);

    /** 从版本输出中提取系统版本号 */
    String extractVersion(String versionOutput);

    // ---------------- 查询命令 ----------------

    /** 获取端口列表（只读，用于进入交换机后自动识别端口） */
    String getPortListCommand();

    /** 查看交换机型号与系统版本 */
    String getVersionCommand();

    /** 查看所有 VLAN */
    String getAllVlansCommand();

    /** 查看指定 VLAN 的 IP（VLAN 接口地址） */
    String getVlanIpCommand(int vlanId);

    /** 查看指定端口的 UP/down 状态 */
    String getPortUpCommand(String port);

    /** 查看指定端口所属 VLAN */
    String getPortVlanCommand(String port);

    /** 查看指定端口的光模块光值信息 */
    String getPortOpticalCommand(String port);

    /** 查看指定端口的错误包统计 */
    String getPortErrorsCommand(String port);

    /** 查看当前交换机全部配置 */
    String getAllConfigCommand();

    /**
     * 退出当前登录的交换机（用户视图退出）。
     * 用于从节点交换机退回到核心交换机。
     */
    String getExitCommand();
}
