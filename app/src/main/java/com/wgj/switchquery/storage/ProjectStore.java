package com.wgj.switchquery.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.wgj.switchquery.crypto.CryptoHelper;
import com.wgj.switchquery.model.Project;
import com.wgj.switchquery.model.SwitchDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目数据存储。所有用户名/密码字段在持久化时使用 AES 加密。
 */
public class ProjectStore {

    private static final String PREF_NAME = "wgj_switch_projects";
    private static final String KEY_PROJECTS = "projects";
    private static final String KEY_BUILTIN_VERSION = "builtin_version";
    /** 内置项目数据版本号：内置数据变更时递增，触发老数据自动刷新 */
    private static final int BUILTIN_VERSION = 4;

    private final SharedPreferences prefs;

    public ProjectStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 获取所有项目。如果没有则初始化默认项目；
     * 内置数据版本升级时自动重建默认数据。
     */
    public List<Project> getProjects() {
        String json = prefs.getString(KEY_PROJECTS, null);
        int savedVersion = prefs.getInt(KEY_BUILTIN_VERSION, 0);
        if (json == null || json.isEmpty() || savedVersion < BUILTIN_VERSION) {
            List<Project> defaults = createDefaultProjects();
            saveProjects(defaults);
            prefs.edit().putInt(KEY_BUILTIN_VERSION, BUILTIN_VERSION).apply();
            return defaults;
        }
        try {
            return parseProjects(new JSONArray(json));
        } catch (Exception e) {
            // 解析失败，重建默认数据
            List<Project> defaults = createDefaultProjects();
            saveProjects(defaults);
            prefs.edit().putInt(KEY_BUILTIN_VERSION, BUILTIN_VERSION).apply();
            return defaults;
        }
    }

    public void saveProjects(List<Project> projects) {
        try {
            JSONArray arr = new JSONArray();
            for (Project p : projects) {
                arr.put(serializeProject(p));
            }
            prefs.edit().putString(KEY_PROJECTS, arr.toString()).apply();
        } catch (Exception e) {
            throw new RuntimeException("Failed to save projects", e);
        }
    }

    public Project getProjectById(String id) {
        for (Project p : getProjects()) {
            if (p.getId().equals(id)) return p;
        }
        return null;
    }

    // ---------------- 序列化 ----------------

    private JSONObject serializeProject(Project p) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("id", p.getId());
        obj.put("name", p.getName());
        obj.put("core", serializeDevice(p.getCore()));
        JSONArray nodes = new JSONArray();
        for (SwitchDevice d : p.getNodes()) {
            nodes.put(serializeDevice(d));
        }
        obj.put("nodes", nodes);
        return obj;
    }

    private JSONObject serializeDevice(SwitchDevice d) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("id", d.getId());
        obj.put("name", d.getName());
        obj.put("ip", d.getIp());
        obj.put("port", d.getPort());
        obj.put("core", d.isCore());
        // 凭证加密存储
        obj.put("userEnc", CryptoHelper.encrypt(d.getUsername()));
        obj.put("passEnc", CryptoHelper.encrypt(d.getPassword()));
        return obj;
    }

    // ---------------- 反序列化 ----------------

    private List<Project> parseProjects(JSONArray arr) throws Exception {
        List<Project> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Project p = new Project(o.getString("id"), o.getString("name"));
            p.setCore(parseDevice(o.getJSONObject("core")));
            JSONArray nodes = o.getJSONArray("nodes");
            for (int j = 0; j < nodes.length(); j++) {
                p.addNode(parseDevice(nodes.getJSONObject(j)));
            }
            list.add(p);
        }
        return list;
    }

    private SwitchDevice parseDevice(JSONObject o) throws Exception {
        SwitchDevice d = new SwitchDevice();
        d.setId(o.getString("id"));
        d.setName(o.getString("name"));
        d.setIp(o.getString("ip"));
        d.setPort(o.getInt("port"));
        d.setCore(o.optBoolean("core", false));
        d.setUsername(CryptoHelper.decrypt(o.optString("userEnc", "")));
        d.setPassword(CryptoHelper.decrypt(o.optString("passEnc", "")));
        return d;
    }

    // ---------------- 默认数据 ----------------

    private List<Project> createDefaultProjects() {
        List<Project> list = new ArrayList<>();

        Project jl = new Project("jilin", "吉林市");
        jl.setCore(new SwitchDevice("jl-core", "吉林市核心交换机",
                "139.214.132.254", 52222, "wgjapp", "app68888978@", true));

        String[][] nodes = {
                {"江南10G节点", "10.10.17.253"},
                {"江北10G节点", "10.10.18.253"},
                {"站前10G节点", "10.10.19.253"},
                {"河南街10G节点", "10.10.20.253"},
                {"松江10G节点", "10.10.21.253"},
                {"平山10G节点", "10.10.22.253"},
                {"虹园10G节点", "10.10.24.253"},
                {"永吉10G节点", "10.10.120.253"},
        };
        for (int i = 0; i < nodes.length; i++) {
            jl.addNode(new SwitchDevice("jl-node-" + i, nodes[i][0],
                    nodes[i][1], 23, "app", "app68888978@", false));
        }

        list.add(jl);
        return list;
    }
}
