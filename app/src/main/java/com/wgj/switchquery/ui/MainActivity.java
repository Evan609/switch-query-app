package com.wgj.switchquery.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wgj.switchquery.App;
import com.wgj.switchquery.R;
import com.wgj.switchquery.auth.AuthManager;
import com.wgj.switchquery.auth.AuthResult;
import com.wgj.switchquery.connection.ConnectionManager;
import com.wgj.switchquery.model.PortDescriptor;
import com.wgj.switchquery.model.Project;
import com.wgj.switchquery.model.SwitchDevice;
import com.wgj.switchquery.model.SwitchType;
import com.wgj.switchquery.storage.ProjectStore;
import com.wgj.switchquery.switchadapter.SwitchAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 主功能界面（三级菜单）：
 * 二级菜单：交换机列表（核心+节点）+ 核心信息摘要
 * 三级菜单：节点查询界面（结果在上、功能按钮在下）
 * 实时命令行默认隐藏，可手动打开。
 */
public class MainActivity extends AppCompatActivity implements ConnectionManager.OutputListener {

    private ConnectionManager cm;
    private Project project;

    private TextView tvProjectName, tvStatus, tvCurrentDevice, tvModel;
    private TextView tvCoreModel, tvCoreVersion, tvCorePatch, tvCoreUptime;
    private LinearLayout coreCard;
    private Button btnBackToProjects;
    private RecyclerView rvDevices;
    private DeviceAdapter deviceAdapter;
    private ScrollView resultScroll;
    private TextView tvResult;
    private LinearLayout terminalPanel, functionButtons;
    private TextView tvTerminal;
    private ScrollView terminalScroll;
    private Button btnBack, btnToggleTerminal, btnClearTerminal;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    /** 是否正在检测交换机在线状态 */
    private volatile boolean statusChecking = false;
    /** 当前是否在三级菜单（节点/核心查询界面） */
    private boolean inNodeLevel = false;
    /** 当前三级菜单进入的设备（节点或核心） */
    private SwitchDevice currentNode;

    /** 无操作超时：5分钟 */
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000;
    private final Runnable idleTimeout = this::onIdleTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SystemBarInsets.applyBottom(findViewById(android.R.id.content));

        cm = App.get().getConnectionManager();
        cm.setOutputListener(this);

        String projectId = getIntent().getStringExtra("project_id");
        project = new ProjectStore(this).getProjectById(projectId);
        if (project == null) {
            Toast.makeText(this, "项目不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        checkAuthorizationOnEnter();

        initViews();
        showLevel2();
        refreshStatus();
        // 恢复已有节点会话（如从后台返回）
        if (cm.isConnected() && cm.getCurrentLevel() > 1) {
            String nid = cm.getNodeId();
            SwitchDevice node = nid == null ? null : findNodeById(nid);
            if (node != null) enterLevel3View(node);
        }
        // 进入项目后自动连接核心交换机（在线状态由用户手动点击按钮检测）
        if (!cm.isConnected() && project.getCore() != null) {
            onCoreClicked(project.getCore());
        }
        resetIdleTimer();
    }

    /**
     * 进入二级页面时校验授权。本地认证模式直接跳过，不产生网络请求。
     * 服务端明确拒绝时退回登录页（账号被停用/删除立即生效）。
     */
    private void checkAuthorizationOnEnter() {
        if (!AuthManager.get().isRemote()) return;
        new Thread(() -> {
            AuthResult r = AuthManager.get().verify();
            if (r.ok) return;
            uiHandler.post(() -> {
                if (isFinishing()) return;
                if (!r.isDenied()) {
                    // 网络或服务器异常：仅提示，不退出登录、不影响当前操作
                    Toast.makeText(this,
                            r.message.isEmpty() ? "网络异常，授权校验失败，请稍后重试" : r.message,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this,
                        r.message.isEmpty() ? "账号已不可用，请重新登录" : r.message,
                        Toast.LENGTH_LONG).show();
                AuthManager.get().logout();
                Intent intent = new Intent(this, LoginActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }).start();
    }

    // ---------------- 无操作超时（5分钟断开全部连接） ----------------

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        resetIdleTimer();
        return super.dispatchTouchEvent(ev);
    }

    private void resetIdleTimer() {
        uiHandler.removeCallbacks(idleTimeout);
        uiHandler.postDelayed(idleTimeout, IDLE_TIMEOUT_MS);
    }

    private void onIdleTimeout() {
        if (!cm.isConnected()) return;
        appendTerminal("\n===== 超时5分钟无操作，自动断开全部连接 =====\n");
        new Thread(() -> {
            cm.disconnectAll();
            uiHandler.post(() -> {
                Toast.makeText(this, "超时5分钟无操作，已断开全部连接", Toast.LENGTH_LONG).show();
                showLevel2();
                refreshStatus();
            });
        }).start();
    }

    private void initViews() {
        tvProjectName = findViewById(R.id.tvProjectName);
        tvStatus = findViewById(R.id.tvStatus);
        tvCurrentDevice = findViewById(R.id.tvCurrentDevice);
        tvModel = findViewById(R.id.tvModel);
        tvCoreModel = findViewById(R.id.tvCoreModel);
        tvCoreVersion = findViewById(R.id.tvCoreVersion);
        tvCorePatch = findViewById(R.id.tvCorePatch);
        tvCoreUptime = findViewById(R.id.tvCoreUptime);
        coreCard = findViewById(R.id.coreCard);
        btnBackToProjects = findViewById(R.id.btnBackToProjects);
        rvDevices = findViewById(R.id.rvDevices);
        resultScroll = findViewById(R.id.resultScroll);
        tvResult = findViewById(R.id.tvResult);
        terminalPanel = findViewById(R.id.terminalPanel);
        functionButtons = findViewById(R.id.functionButtons);
        tvTerminal = findViewById(R.id.tvTerminal);
        terminalScroll = findViewById(R.id.terminalScroll);
        btnBack = findViewById(R.id.btnBack);
        btnToggleTerminal = findViewById(R.id.btnToggleTerminal);
        btnClearTerminal = findViewById(R.id.btnClearTerminal);

        tvProjectName.setText("项目：" + project.getName());
        tvTerminal.setMovementMethod(ScrollingMovementMethod.getInstance());

        rvDevices.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 2));
        // 核心（第0项）独占一行，节点一行两个
        ((androidx.recyclerview.widget.GridLayoutManager) rvDevices.getLayoutManager())
                .setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                    @Override
                    public int getSpanSize(int position) {
                        return position == 0 ? 2 : 1;
                    }
                });
        deviceAdapter = new DeviceAdapter();
        rvDevices.setAdapter(deviceAdapter);

        btnBack.setOnClickListener(v -> onBackPressed());
        btnBackToProjects.setOnClickListener(v -> startStatusCheck());
        btnToggleTerminal.setOnClickListener(v -> toggleTerminal());
        btnClearTerminal.setOnClickListener(v -> tvTerminal.setText(""));

        setupFunctionButtons();
    }

    // ---------------- 菜单层级 ----------------

    /** 二级菜单：交换机列表 + 核心信息 */
    private void showLevel2() {
        inNodeLevel = false;
        currentNode = null;
        coreCard.setVisibility(View.GONE);
        btnBackToProjects.setVisibility(View.VISIBLE);
        rvDevices.setVisibility(View.VISIBLE);
        resultScroll.setVisibility(View.GONE);
        functionButtons.setVisibility(View.GONE);
        refreshStatus();
    }

    /** 三级菜单：节点/核心查询（结果在上、按钮在下） */
    private void enterLevel3View(SwitchDevice node) {
        inNodeLevel = true;
        currentNode = node;
        coreCard.setVisibility(View.GONE);
        btnBackToProjects.setVisibility(View.GONE);
        rvDevices.setVisibility(View.GONE);
        resultScroll.setVisibility(View.VISIBLE);
        functionButtons.setVisibility(View.VISIBLE);
        if (node.isCore()) {
            showCoreInfoInResult();
        } else {
            HighlightHelper.applyTo(tvResult,
                    "已选择节点：" + node.getName() + "（" + node.getIp() + "）\n");
        }
        refreshStatus();
    }

    /** 三级页（核心模式）结果区显示核心信息摘要 */
    private void showCoreInfoInResult() {
        SwitchDevice core = project.getCore();
        HighlightHelper.applyTo(tvResult,
                "核心交换机：" + core.getName() + "（" + core.getIp() + "）\n"
                + "型号：" + (cm.getCurrentModel().isEmpty() ? "未知" : cm.getCurrentModel()) + "\n"
                + "版本：" + (cm.getCurrentVersion().isEmpty() ? "未知" : cm.getCurrentVersion()) + "\n"
                + "运行时间：" + (cm.getCurrentUptime().isEmpty() ? "-" : cm.getCurrentUptime()));
    }

    private void toggleTerminal() {
        boolean show = terminalPanel.getVisibility() != View.VISIBLE;
        terminalPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        btnToggleTerminal.setText(show ? "收起命令行" : "命令行");
    }

    private SwitchDevice findNodeById(String id) {
        for (SwitchDevice d : project.getNodes()) {
            if (d.getId().equals(id)) return d;
        }
        return null;
    }

    // ---------------- 设备列表 ----------------

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {

        private final List<SwitchDevice> devices = new ArrayList<>();
        /** 在线状态缓存：1=检测中 2=在线 3=离线（无记录则不显示） */
        private final java.util.Map<String, Integer> statusMap = new java.util.HashMap<>();

        // 状态常量
        private static final int STATE_CHECKING = 1;
        private static final int STATE_ONLINE = 2;
        private static final int STATE_OFFLINE = 3;
        private static final int STATE_UNKNOWN = 4;

        /** 全部标记为检测中 */
        void setAllChecking() {
            statusMap.clear();
            for (SwitchDevice d : devices) statusMap.put(d.getId(), STATE_CHECKING);
            notifyDataSetChanged();
        }

        /** 更新单个设备状态 */
        void setStatus(String deviceId, int state) {
            statusMap.put(deviceId, state);
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).getId().equals(deviceId)) {
                    notifyItemChanged(i);
                    break;
                }
            }
        }

        DeviceAdapter() {
            if (project.getCore() != null) devices.add(project.getCore());
            devices.addAll(project.getNodes());
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            SwitchDevice d = devices.get(position);
            String typeLabel = d.isCore() ? getString(R.string.core_switch) : getString(R.string.node_switch);
            h.tvName.setText(d.getName());
            h.tvIp.setText(d.getIp() + (d.isCore() ? ":" + d.getPort() : "") + " · " + typeLabel);
            // 当前所在设备显示"● 当前"标记
            h.tvCurrent.setVisibility(isDeviceInStack(d) && cm.isConnected() ? View.VISIBLE : View.GONE);
            // 在线状态标记（未检测时显示"待检测"）
            Integer st = statusMap.get(d.getId());
            h.tvStatus.setVisibility(View.VISIBLE);
            if (st == null) {
                h.tvStatus.setText("● " + getString(R.string.status_pending));
                h.tvStatus.setTextColor(getColor(R.color.text_secondary));
            } else if (st == STATE_ONLINE) {
                h.tvStatus.setText("● " + getString(R.string.status_online));
                h.tvStatus.setTextColor(getColor(R.color.success));
            } else if (st == STATE_OFFLINE) {
                h.tvStatus.setText("● " + getString(R.string.status_offline));
                h.tvStatus.setTextColor(getColor(R.color.error));
            } else if (st == STATE_UNKNOWN) {
                h.tvStatus.setText("● " + getString(R.string.status_unknown));
                h.tvStatus.setTextColor(getColor(R.color.text_secondary));
            } else {
                h.tvStatus.setText("● " + getString(R.string.status_checking));
                h.tvStatus.setTextColor(getColor(R.color.text_secondary));
            }
            h.itemView.setOnClickListener(v -> {
                if (d.isCore()) onCoreClicked(d); else onNodeClicked(d);
            });
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvIp, tvCurrent, tvStatus;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_dev_name);
                tvIp = v.findViewById(R.id.tv_dev_ip);
                tvCurrent = v.findViewById(R.id.tv_current);
                tvStatus = v.findViewById(R.id.tv_dev_status);
            }
        }
    }

    private boolean isDeviceInStack(SwitchDevice d) {
        if (d.isCore()) return cm.isConnected();
        return d.getId().equals(cm.getNodeId());
    }

    // ---------------- 核心操作 ----------------

    private void onCoreClicked(SwitchDevice core) {
        if (running) return;
        if (cm.isConnected()) {
            // 已连接：进入核心查询页（三级菜单，全部查询功能可用）
            enterLevel3View(core);
            return;
        }
        running = true;
        appendTerminal("\n===== 连接核心交换机 " + core.getIp() + " =====\n");
        new Thread(() -> {
            Exception err = null;
            try {
                cm.connectCore(core);
                cm.connectCoreAndLoadInfo();
            } catch (Exception e) {
                err = e;
            }
            final Exception ferr = err;
            uiHandler.post(() -> {
                running = false;
                if (ferr != null) {
                    Toast.makeText(this, "连接失败：" + ferr.getMessage(), Toast.LENGTH_LONG).show();
                } else {
                    showCoreInfo();
                    Toast.makeText(this, "核心连接成功", Toast.LENGTH_SHORT).show();
                    // 连接成功即说明核心在线，直接置为在线（不做额外检测）
                    deviceAdapter.setStatus(core.getId(), DeviceAdapter.STATE_ONLINE);
                }
                refreshStatus();
            });
        }).start();
    }

    /** 已连接状态下点击核心：进入核心查询页（见 onCoreClicked）。 */

    /** 显示核心信息摘要（二级卡片，不显示补丁） */
    private void showCoreInfo() {
        tvCoreModel.setText("型号：" + (cm.getCurrentModel().isEmpty() ? "未知" : cm.getCurrentModel()));
        tvCoreVersion.setText("版本：" + (cm.getCurrentVersion().isEmpty() ? "未知" : cm.getCurrentVersion()));
        tvCoreUptime.setText("运行时间：" + (cm.getCurrentUptime().isEmpty() ? "-" : cm.getCurrentUptime()));
    }

    // ---------------- 交换机在线状态检测（只读） ----------------

    /**
     * 检测全部交换机在线状态：
     * 核心用 TCP 探测端口，节点在核心命令行里 ping（均为只读操作，不做任何配置更改）。
     */
    private void startStatusCheck() {
        if (statusChecking || running) return;
        if (!cm.isConnected()) {
            Toast.makeText(this, "请先点击核心交换机进行连接", Toast.LENGTH_SHORT).show();
            return;
        }
        statusChecking = true;
        running = true;
        btnBackToProjects.setEnabled(false);
        btnBackToProjects.setText(R.string.checking_status);
        deviceAdapter.setAllChecking();
        appendTerminal("\n===== 检测交换机在线状态 =====\n");
        final SwitchDevice core = project.getCore();
        final java.util.List<SwitchDevice> nodes = project.getNodes();
        new Thread(() -> {
            boolean coreOnline = ConnectionManager.isPortReachable(core.getIp(), core.getPort(), 3000);
            uiHandler.post(() -> deviceAdapter.setStatus(core.getId(), coreOnline ? 2 : 3));
            for (SwitchDevice n : nodes) {
                boolean online = false;
                boolean unknown = false;
                if (cm.isConnected()) {
                    int r = cm.pingHost(n.getIp());
                    if (r == 1) {
                        online = true;
                    } else if (r == -1) {
                        // 当前设备不支持带参数的 ping，无法检测（不做 telnet 探测）
                        unknown = true;
                    }
                }
                final boolean fOnline = online;
                final boolean fUnknown = unknown;
                uiHandler.post(() -> {
                    if (fUnknown) {
                        deviceAdapter.setStatus(n.getId(), 4);
                    } else {
                        deviceAdapter.setStatus(n.getId(), fOnline ? 2 : 3);
                    }
                });
            }
            uiHandler.post(() -> {
                statusChecking = false;
                running = false;
                btnBackToProjects.setEnabled(true);
                btnBackToProjects.setText(R.string.check_switch_status);
                appendTerminal("在线状态检测完成。\n");
                refreshStatus();
            });
        }, "status-check").start();
    }

    // ---------------- 节点操作 ----------------

    private void onNodeClicked(SwitchDevice node) {
        if (running) return;
        if (!cm.isConnected()) {
            Toast.makeText(this, "请先点击核心交换机进行连接", Toast.LENGTH_SHORT).show();
            return;
        }
        // 先跳转到三级页面，并在结果区显示连接进度
        enterLevel3View(node);
        HighlightHelper.applyTo(tvResult,
                "正在连接节点 " + node.getName() + "（" + node.getIp() + "）...\n请稍候");
        running = true;
        appendTerminal("\n===== 进入节点 " + node.getName() + " =====\n");
        new Thread(() -> {
            Exception err = null;
            try {
                cm.telnetToNode(node);
            } catch (Exception e) {
                err = e;
            }
            final Exception ferr = err;
            uiHandler.post(() -> {
                running = false;
                if (ferr != null) {
                    Toast.makeText(this, "进入节点失败：" + ferr.getMessage(), Toast.LENGTH_LONG).show();
                    // 尝试回退到核心后返回二级菜单
                    new Thread(() -> {
                        try {
                            if (cm.getCurrentLevel() > 1) cm.exitToPrevious();
                        } catch (Exception ignored) {}
                        uiHandler.post(() -> {
                            showLevel2();
                            refreshStatus();
                        });
                    }).start();
                } else {
                    HighlightHelper.applyTo(tvResult,
                            "已连接节点：" + node.getName() + "（" + node.getIp() + "）\n"
                            + "型号：" + (cm.getCurrentModel().isEmpty() ? "未知" : cm.getCurrentModel()) + "\n"
                            + "版本：" + (cm.getCurrentVersion().isEmpty() ? "未知" : cm.getCurrentVersion()));
                }
                refreshStatus();
            });
        }).start();
    }

    /** 三级菜单返回：节点退到核心后回二级；核心（第一层）直接回二级 */
    private void exitNodeAndBack() {
        if (running) return;
        if (currentNode != null && currentNode.isCore()) {
            showLevel2();
            return;
        }
        running = true;
        new Thread(() -> {
            try {
                cm.exitToPrevious();
            } catch (Exception ignored) {}
            uiHandler.post(() -> {
                running = false;
                showLevel2();
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (inNodeLevel) {
            exitNodeAndBack();
        } else {
            confirmDisconnectAndFinish();
        }
    }

    private void confirmDisconnectAndFinish() {
        if (cm.isConnected()) {
            new AlertDialog.Builder(this)
                    .setTitle("断开连接")
                    .setMessage("将断开与核心的连接，确定返回吗？")
                    .setPositiveButton(R.string.confirm, (d, w) -> {
                        new Thread(() -> cm.disconnectAll()).start();
                        finish();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            finish();
        }
    }

    // ---------------- 查询功能 ----------------

    private void setupFunctionButtons() {
        String[][] funcs = {
                {"端口UP状态", "port_up"},
                {"端口VLAN", "port_vlan"},
                {"端口光值", "port_optical"},
                {"端口错误包", "port_errors"},
                {"查看所有VLAN", "all_vlans"},
                {"查看VLAN的IP", "vlan_ip"},
                {"查看全部配置", "all_config"},
        };
        LinearLayout row = null;
        for (int i = 0; i < funcs.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                functionButtons.addView(row);
            }
            final String type = funcs[i][1];
            Button btn = new Button(this);
            btn.setText(funcs[i][0]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(4, 4, 4, 4);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> onFunctionClicked(type));
            row.addView(btn);
        }
    }

    private void onFunctionClicked(String type) {
        if (!checkNodeReady()) return;
        if (type.startsWith("port_")) {
            pickPortAndRun(type);
        } else if ("vlan_ip".equals(type)) {
            inputVlanAndRun();
        } else {
            runQuery(type, null);
        }
    }

    private boolean checkNodeReady() {
        if (running) {
            Toast.makeText(this, "正在执行，请稍候", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!inNodeLevel || currentNode == null) {
            Toast.makeText(this, "请先选择交换机", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (currentNode.isCore()) {
            if (!cm.isConnected()) {
                Toast.makeText(this, "核心未连接", Toast.LENGTH_SHORT).show();
                return false;
            }
        } else if (cm.getCurrentLevel() < 2) {
            Toast.makeText(this, "请先进入节点交换机", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void pickPortAndRun(String type) {
        List<PortDescriptor> ports = cm.getPortList();
        if (ports.isEmpty()) {
            running = true;
            Toast.makeText(this, "正在识别端口...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                cm.refreshPorts();
                List<PortDescriptor> p = cm.getPortList();
                uiHandler.post(() -> {
                    running = false;
                    if (p.isEmpty()) {
                        Toast.makeText(this, "未获取到端口列表，请打开命令行查看", Toast.LENGTH_LONG).show();
                    } else {
                        showPortPicker(type, p);
                    }
                });
            }).start();
            return;
        }
        showPortPicker(type, ports);
    }

    private void showPortPicker(String type, List<PortDescriptor> ports) {
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择端口（共 " + ports.size() + " 个）")
                .setNegativeButton(R.string.cancel, null)
                .create();
        // 用 RecyclerView + GridLayoutManager 替代 GridLayout
        // GridLayout 在某些设备上仍会因 baseline 对齐 / measure 顺序出现奇偶行错位，
        // RecyclerView 是 Android 官方网格方案，对齐由 LayoutManager 严格控制
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        rv.setOverScrollMode(RecyclerView.OVER_SCROLL_NEVER);

        // 按钮尺寸：高度固定 56dp，宽度由 GridLayoutManager 均分
        int btnHeight = dpToPx(56);
        int margin = dpToPx(4);

        final List<PortDescriptor> portList = ports;
        final AlertDialog dialog = dlg;
        final String qType = type;

        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                Button b = new Button(parent.getContext());
                // 清掉 Button 默认 minWidth/minHeight，让 LayoutManager 完全控制宽度
                b.setMinWidth(0);
                b.setMinimumWidth(0);
                b.setMinHeight(0);
                b.setMinimumHeight(0);
                b.setAllCaps(false);
                b.setSingleLine(false);
                b.setMaxLines(2);
                b.setEllipsize(android.text.TextUtils.TruncateAt.END);
                // 文字自动缩放：6sp ~ 11sp，步长 1sp
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        b, 6, 11, 1, TypedValue.COMPLEX_UNIT_SP);
                b.setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
                // 宽度 MATCH_PARENT 由 GridLayoutManager 均分列宽，高度固定
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, btnHeight);
                lp.setMargins(margin, margin, margin, margin);
                b.setLayoutParams(lp);
                return new RecyclerView.ViewHolder(b) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                PortDescriptor port = portList.get(position);
                Button b = (Button) holder.itemView;

                // 两行文字：第一行 "[TFGE] 0/47"（端口号），第二行 description（小字浅灰）
                String line1 = "[" + port.prefixAbbr + "] " + port.portNumber;
                String line2 = (port.description == null || port.description.isEmpty())
                        ? "" : port.description;
                SpannableString ss = new SpannableString(line1 + "\n" + line2);
                int descStart = line1.length() + 1;
                if (!line2.isEmpty()) {
                    ss.setSpan(new RelativeSizeSpan(0.85f), descStart, ss.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ss.setSpan(new ForegroundColorSpan(Color.parseColor("#888888")),
                            descStart, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                b.setText(ss);

                // 状态颜色：up=绿 / down=默认灰 / 其他=红（影响第一行端口号）
                PortDescriptor.StatusColor sc = port.getStatusColor();
                if (sc == PortDescriptor.StatusColor.GREEN) {
                    b.setTextColor(Color.parseColor("#1B8A3C"));
                } else if (sc == PortDescriptor.StatusColor.RED) {
                    b.setTextColor(Color.parseColor("#C0392B"));
                } else {
                    b.setTextColor(getResources().getColor(R.color.text_primary));
                }

                b.setOnClickListener(v -> {
                    dialog.dismiss();
                    runQuery(qType, port.rawName);
                });
            }

            @Override
            public int getItemCount() {
                return portList.size();
            }
        });

        dlg.setView(rv);
        dlg.show();
    }

    /** dp 转 px */
    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    private void inputVlanAndRun() {
        final EditText input = new EditText(this);
        input.setHint("如：100");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle("输入VLAN编号")
                .setView(input)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    String vlan = input.getText().toString().trim();
                    if (vlan.isEmpty()) {
                        Toast.makeText(this, "VLAN编号不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runQuery("vlan_ip", vlan);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 统一查询执行：原始命令行始终完整进入终端区（可手动打开查看），
     * 结果区只显示精简后的有效信息。
     */
    private void runQuery(String type, String param) {
        if (running) return;
        SwitchAdapter adapter = cm.getCurrentAdapter();
        String cmd = buildCommand(adapter, type, param);
        if (cmd == null) {
            Toast.makeText(this, "当前型号不支持该功能", Toast.LENGTH_SHORT).show();
            return;
        }
        running = true;
        final String fcmd = cmd;
        final String ftype = type;
        appendTerminal("\n# " + (currentNode != null ? currentNode.getName() : "") + "\n");
        HighlightHelper.applyTo(tvResult, "正在查询，请稍候…"
                + ("port_optical".equals(ftype) ? "\n（100G/40G 端口光值读取较慢，通常需要数秒）" : ""));
        new Thread(() -> {
            String raw = null;
            Exception err = null;
            try {
                if ("all_config".equals(ftype)) {
                    raw = cm.executeRaw(fcmd);
                } else {
                    raw = cm.executeQuery(fcmd);
                }
            } catch (Exception e) {
                err = e;
            }
            final String fraw = raw;
            final Exception ferr = err;
            uiHandler.post(() -> {
                running = false;
                if (ferr != null) {
                    HighlightHelper.applyTo(tvResult, "[错误] 查询失败：" + ferr.getMessage()
                            + "\n\n可点击下方\"命令行\"按钮查看全部原始输出");
                } else if (fraw == null || fraw.trim().isEmpty()) {
                    HighlightHelper.applyTo(tvResult, "（无返回内容）");
                } else if ("all_config".equals(ftype)) {
                    // 全部配置功能直接展示原始返回
                    HighlightHelper.applyTo(tvResult, fraw.trim());
                } else {
                    // 结果区只显示精简信息；完整命令行在终端区
                    HighlightHelper.applyTo(tvResult, ResultRefiner.refine(ftype, fraw));
                }
                refreshStatus();
            });
        }).start();
    }

    /** 根据适配器与功能类型拼接命令（全部只读）。 */
    private String buildCommand(SwitchAdapter adapter, String type, String param) {
        if (adapter == null) return null;
        switch (type) {
            case "port_up": return adapter.getPortUpCommand(param);
            case "port_vlan": return adapter.getPortVlanCommand(param);
            case "port_optical": return adapter.getPortOpticalCommand(param);
            case "port_errors": return adapter.getPortErrorsCommand(param);
            case "all_vlans": return adapter.getAllVlansCommand();
            case "vlan_ip":
                try {
                    return adapter.getVlanIpCommand(Integer.parseInt(param));
                } catch (NumberFormatException e) {
                    return null;
                }
            case "all_config": return adapter.getAllConfigCommand();
            default: return null;
        }
    }

    // ---------------- 状态与终端 ----------------

    private void refreshStatus() {
        tvStatus.setText(cm.isConnected() ? R.string.connected : R.string.disconnected);
        tvStatus.setTextColor(cm.isConnected() ? 0xFF1B8A2F : 0xFFD32F2F);
        if (cm.getCurrentDevice() != null) {
            tvCurrentDevice.setText(getString(R.string.current_switch) + "：" + cm.getCurrentDevice().getName());
        } else {
            tvCurrentDevice.setText(getString(R.string.current_switch) + "：-");
        }
        String model = cm.getCurrentModel();
        SwitchType t = cm.getCurrentType();
        String typeName;
        switch (t) {
            case HUAWEI: typeName = "华为"; break;
            case H3C: typeName = "H3C"; break;
            case RUIJIE: typeName = "锐捷"; break;
            default: typeName = "未知"; break;
        }
        tvModel.setText(getString(R.string.switch_model) + "：" + typeName
                + (model.isEmpty() ? "" : " " + model));
        deviceAdapter.notifyDataSetChanged();
    }

    @Override
    public void onOutput(String text) {
        uiHandler.post(() -> appendTerminal(text));
    }

    private void appendTerminal(String s) {
        tvTerminal.append(s);
        terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(idleTimeout);
        cm.setOutputListener(null);
    }
}
