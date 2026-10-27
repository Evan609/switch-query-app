package com.wgj.switchquery.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络项目：包含一个核心交换机和若干节点交换机
 */
public class Project {

    private String id;
    private String name;
    private SwitchDevice core;
    private List<SwitchDevice> nodes = new ArrayList<>();

    public Project() {
    }

    public Project(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public SwitchDevice getCore() { return core; }
    public void setCore(SwitchDevice core) { this.core = core; }

    public List<SwitchDevice> getNodes() { return nodes; }
    public void setNodes(List<SwitchDevice> nodes) { this.nodes = nodes; }

    public void addNode(SwitchDevice node) {
        if (node != null) nodes.add(node);
    }
}
