package com.wgj.switchquery.model;

/**
 * 交换机设备（核心或节点）
 */
public class SwitchDevice {

    /** 设备在项目中的唯一标识 */
    private String id;
    /** 显示名称，如"江南10G节点" */
    private String name;
    /** 管理 IP */
    private String ip;
    /** 端口（核心为 SSH 端口，节点为 telnet 端口，默认 23） */
    private int port;
    /** 用户名（加密存储前明文） */
    private String username;
    /** 密码（加密存储前明文） */
    private String password;
    /** 是否为核心交换机 */
    private boolean core;

    public SwitchDevice() {
    }

    public SwitchDevice(String id, String name, String ip, int port,
                        String username, String password, boolean core) {
        this.id = id;
        this.name = name;
        this.ip = ip;
        this.port = port;
        this.username = username;
        this.password = password;
        this.core = core;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isCore() { return core; }
    public void setCore(boolean core) { this.core = core; }
}
