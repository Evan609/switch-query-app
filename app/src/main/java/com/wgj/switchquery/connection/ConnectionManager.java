package com.wgj.switchquery.connection;

import com.wgj.switchquery.model.PortDescriptor;
import com.wgj.switchquery.model.SwitchDevice;
import com.wgj.switchquery.model.SwitchType;
import com.wgj.switchquery.switchadapter.SwitchAdapter;
import com.wgj.switchquery.switchadapter.SwitchAdapterFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连接状态机。
 * 维护 SSH 核心连接 + 节点 telnet 层级栈。
 *
 * 层级说明：
 *   level 0  -> 核心交换机（SSH 直连）
 *   level 1  -> 从核心 telnet 到的节点交换机
 *   level 2  -> 从节点 A telnet 到的节点 B
 *   ...
 *
 * 退出规则：
 *   - 退出节点回到上一层（核心或上一级节点）
 *   - 在核心执行退出会断开 SSH 连接
 */
public class ConnectionManager {

    private SshShellSession shell;
    /** 设备栈：栈底为核心，栈顶为当前所在设备 */
    private final Stack<SwitchDevice> deviceStack = new Stack<>();
    /** 当前设备识别出的适配器 */
    private SwitchAdapter currentAdapter;
    /** 当前设备识别出的型号 */
    private String currentModel = "";
    private String currentVersion = "";
    private String currentPatch = "";
    private String currentUptime = "";
    /** 当前交换机识别到的端口列表（含 description 和状态） */
    private final List<PortDescriptor> portList = new ArrayList<>();
    /** 核心交换机提示符主机名（用于判断当前是否仍在核心上） */
    private String coreHostName = "";
    /** 已推送到界面的终端位置（避免实时推送与批量推送重复） */
    private int terminalPos = 0;
    private long lastLiveFlush = 0;

    // 监听器，用于实时输出命令行
    public interface OutputListener {
        void onOutput(String text);
    }
    private OutputListener outputListener;

    public void setOutputListener(OutputListener listener) {
        this.outputListener = listener;
    }

    private void notifyOutput(String text) {
        if (outputListener != null && text != null) {
            outputListener.onOutput(text);
        }
    }

    /** 实时输出回调：节流后把新增输出推送到终端区 */
    private void liveFlush() {
        if (shell == null) return;
        long now = System.currentTimeMillis();
        if (now - lastLiveFlush < 120) return;
        lastLiveFlush = now;
        flushTerminal();
    }

    /** 推送尚未显示过的设备输出（实时显示命令行） */
    public void flushTerminal() {
        if (shell == null) return;
        int now = shell.getBufferPosition();
        if (now > terminalPos) {
            String text = shell.getNewOutput(terminalPos);
            terminalPos = now;
            notifyOutput(text);
        }
    }

    /**
     * SSH 连接核心交换机。
     */
    public void connectCore(SwitchDevice core) throws Exception {
        shell = new SshShellSession();
        notifyOutput("正在 SSH 连接核心交换机 " + core.getIp() + ":" + core.getPort() + " ...\n");
        shell.connect(core.getIp(), core.getPort(), core.getUsername(), core.getPassword());
        terminalPos = shell.getBufferPosition();
        shell.setChunkListener(this::liveFlush);
        deviceStack.clear();
        deviceStack.push(core);
        currentAdapter = null;
        pingTemplate = null;
        interactivePing = false;
        coreHostName = shell.getLastPromptHost();
        if (coreHostName.isEmpty()) {
            // 提示符可能尚未完整到达，补读一次
            shell.readUntilPrompt(1500);
            coreHostName = shell.getLastPromptHost();
        }
        notifyOutput("核心交换机连接成功。\n");
        // 自动识别核心型号
        detectCurrentModel();
    }

    /**
     * 在当前交换机上 telnet 到目标节点。
     * 登录后会校验提示符主机名是否已切换为节点，
     * 避免节点离线/密码错误时误把核心当成节点。
     */
    public void telnetToNode(SwitchDevice node) throws Exception {
        if (shell == null || !shell.isConnected()) {
            throw new IllegalStateException("未连接核心交换机");
        }
        // 核心主机名以连接时记录的为准，取不到时用当前提示符兜底
        String coreHost = coreHostName.isEmpty() ? shell.getLastPromptHost() : coreHostName;
        // 上一次失败可能残留在 telnet 转义菜单，先确保回到核心提示符
        ensureBackAtPrompt();
        notifyOutput("\n正在 telnet 到节点 " + node.getIp() + " ...\n");
        int pos = shell.getBufferPosition();
        shell.sendLine("telnet " + node.getIp());

        // 等待用户名/密码提示，或连接失败提示
        String out = shell.readUntil(
                "(?i)(username|login)\\s*:|password\\s*:|connection timed out|timed out"
                        + "|connection refused|unable to connect|connection closed"
                        + "|no route to host|destination unreachable|connection failed"
                        + "|unknown host", 20000);
        flushTerminal();
        String lower = stripControl(out).toLowerCase();
        if (isTelnetConnectFail(lower)) {
            ensureBackAtPrompt();
            throw new Exception("节点离线或无法连接：" + node.getIp());
        }

        boolean sawUserPrompt = lower.contains("username") || lower.contains("login:");
        boolean sawPassPrompt = lower.contains("password");
        if (!sawUserPrompt && !sawPassPrompt) {
            // 没有收到任何登录提示，说明 telnet 没有真正连上节点
            ensureBackAtPrompt();
            throw new Exception("节点离线或无法连接：" + node.getIp());
        }

        pos = shell.getBufferPosition();
        if (sawUserPrompt) {
            shell.sendLine(node.getUsername());
            out = shell.readUntil(
                    "(?i)password\\s*:|login incorrect|authentication failed|login invalid"
                            + "|access denied|invalid password|bad password|login failed"
                            + "|username or password|username\\s*:", 12000);
            flushTerminal();
            lower = stripControl(out).toLowerCase();
            if (isAuthFail(lower) || lower.contains("username")) {
                ensureBackAtPrompt();
                throw new Exception("节点登录失败：用户名或密码错误");
            }
            pos = shell.getBufferPosition();
        }

        // 输入密码，等待提示符或认证失败提示
        shell.sendLine(node.getPassword());
        out = shell.readUntil(
                "(?m)^(<[A-Za-z0-9._\\-]+>|\\[[A-Za-z0-9._\\-]+\\]|[A-Za-z0-9._\\-]+[>#$])\\s*$"
                        + "|(?i)login incorrect|authentication failed|login invalid|access denied"
                        + "|invalid password|bad password|login failed|username or password"
                        + "|username\\s*:|password\\s*:", 20000);
        flushTerminal();
        lower = stripControl(out).toLowerCase();
        if (isAuthFail(lower) || lower.contains("password:") || lower.contains("username:")) {
            ensureBackAtPrompt();
            throw new Exception("节点登录失败：用户名或密码错误");
        }

        // 关键校验：提示符主机名必须已从核心切换到节点（telnet 转义菜单的主机名不是设备）
        String host = shell.getLastPromptHost();
        notifyOutput("[校验] 核心主机=" + coreHost + " 当前主机=" + host + "\n");
        if (host.isEmpty() || coreHost.isEmpty() || "telnet".equalsIgnoreCase(host)
                || host.equalsIgnoreCase(coreHost)) {
            ensureBackAtPrompt();
            throw new Exception("节点登录失败：未进入节点命令行，请检查节点是否在线及账号密码");
        }

        deviceStack.push(node);
        currentAdapter = null;
        notifyOutput("已登录节点：" + node.getName() + " (" + node.getIp() + ")\n");
        // 消费登录后可能仍在途的提示符/输出残留，避免干扰后续命令识别
        shell.readUntilPrompt(800);
        // 分页由 reader 线程自动处理：检测到 --More-- 时自动发送空格，
        // endsWithPrompt() 已修复 --More-- 与提示符同行的检测问题。
        detectCurrentModel();
    }

    /** telnet 连接失败提示 */
    private boolean isTelnetConnectFail(String lower) {
        return lower.contains("timed out") || lower.contains("connection refused")
                || lower.contains("unable to connect") || lower.contains("connection closed")
                || lower.contains("no route to host") || lower.contains("destination unreachable")
                || lower.contains("connection failed") || lower.contains("unknown host");
    }

    /** 认证失败提示 */
    private boolean isAuthFail(String lower) {
        return lower.contains("login incorrect") || lower.contains("authentication failed")
                || lower.contains("login invalid") || lower.contains("access denied")
                || lower.contains("invalid password") || lower.contains("bad password")
                || lower.contains("login failed") || lower.contains("username or password")
                || lower.contains("permission denied");
    }

    /**
     * 确保回到核心提示符：若仍停留在节点登录/telnet 会话中，
     * 用 Ctrl-] + quit 中断 telnet 会话（只读操作，不改配置）。
     */
    private void ensureBackAtPrompt() {
        try {
            String host = shell.getLastPromptHost();
            boolean atTelnetMenu = "telnet".equalsIgnoreCase(host);
            if (shell.isAtPrompt() && !atTelnetMenu) return;
            if (!atTelnetMenu) {
                // 仍停在 telnet 登录/连接过程，用 Ctrl-] 进入转义菜单
                shell.sendRaw("\u001d");
                Thread.sleep(250);
            }
            shell.sendLine("quit");
            String res = shell.readUntilPrompt(5000);
            if (!SshShellSession.endsWithPrompt(res)) {
                shell.sendRaw("\u0003");
                shell.readUntilPrompt(2000);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 退出当前交换机，回到上一层。
     * @return true 表示已退回到核心，false 表示退回到上一级节点
     */
    public boolean exitToPrevious() throws Exception {
        if (shell == null || !shell.isConnected()) {
            throw new IllegalStateException("未连接任何交换机");
        }
        if (deviceStack.size() <= 1) {
            // 已经在核心，退出则断开
            disconnectAll();
            return true;
        }
        SwitchDevice leaving = deviceStack.peek();
        SwitchAdapter adapter = currentAdapter;

        // 异常保护：若当前提示符已是核心主机名（节点 telnet 会话已断开），
        // 不再发送退出命令，否则会把核心一起退出
        String host = shell.getLastPromptHost();
        if (!coreHostName.isEmpty() && coreHostName.equalsIgnoreCase(host)) {
            deviceStack.pop();
            currentAdapter = null;
            currentModel = "";
            currentVersion = "";
            currentPatch = "";
            currentUptime = "";
            notifyOutput("\n已回到核心交换机：" + coreHostName + "\n");
            detectCurrentModel();
            return true;
        }

        String exitCmd = (adapter != null) ? adapter.getExitCommand() : "quit";
        notifyOutput("\n正在退出 " + leaving.getName() + " ...\n");
        int pos = shell.getBufferPosition();
        shell.sendLine(exitCmd);
        shell.readUntilPrompt(8000);
        flushTerminal();
        deviceStack.pop();
        currentAdapter = null;
        currentModel = "";
        currentVersion = "";
        currentPatch = "";
        currentUptime = "";
        // 退出后重新识别上一层的型号
        detectCurrentModel();
        return deviceStack.size() == 1;
    }

    /**
     * 识别当前交换机型号与版本。
     */
    public void detectCurrentModel() throws Exception {
        if (shell == null || !shell.isConnected()) return;
        notifyOutput("\n正在识别交换机型号...\n");
        SwitchAdapter detected = null;
        String versionOutput = "";
        for (String cmd : SwitchAdapterFactory.getDetectCommands()) {
            int pos = shell.getBufferPosition();
            String out = shell.executeClean(cmd, 10000);
            flushTerminal();
            versionOutput += out + "\n";
            detected = SwitchAdapterFactory.detect(out);
            if (detected != null) break;
        }
        if (detected != null) {
            currentAdapter = detected;
            currentModel = detected.extractModel(versionOutput);
            currentVersion = detected.extractVersion(versionOutput);
            currentPatch = extractPatch(versionOutput);
            currentUptime = extractUptime(versionOutput);
            notifyOutput("\n识别成功：" + currentAdapter.getType().getLabel()
                    + " - " + currentModel + " (版本: " + currentVersion + ")\n");
        } else {
            currentAdapter = null;
            currentModel = "未知型号";
            currentVersion = "未知版本";
            currentPatch = "";
            currentUptime = "";
            notifyOutput("\n未能识别交换机型号，将使用通用命令。\n");
        }
        // 识别端口列表（只读命令）
        detectPorts();
    }

    /** 获取当前交换机识别到的端口描述符列表（副本）。 */
    public List<PortDescriptor> getPortList() {
        return new ArrayList<>(portList);
    }

    /** 兼容旧接口：返回原始端口名列表（供命令参数使用）。 */
    public List<String> getRawPortNames() {
        List<String> names = new ArrayList<>(portList.size());
        for (PortDescriptor p : portList) names.add(p.rawName);
        return names;
    }

    /** 当前栈顶节点的 id（在节点层级时），否则 null。 */
    public String getNodeId() {
        if (deviceStack.size() > 1) {
            return deviceStack.peek().getId();
        }
        return null;
    }

    /**
     * 已连接核心状态下，重新执行版本命令并提取核心信息（型号/版本/补丁/运行时间）。
     * 仅允许在核心层级调用（只读命令，不更改任何配置）。
     */
    public void connectCoreAndLoadInfo() throws Exception {
        if (shell == null || !shell.isConnected()) {
            throw new IllegalStateException("未连接核心交换机");
        }
        if (deviceStack.size() != 1) {
            throw new IllegalStateException("当前不在核心层级");
        }
        String cmd = (currentAdapter != null) ? currentAdapter.getVersionCommand() : "display version";
        int pos = shell.getBufferPosition();
        String out = shell.executeClean(cmd, 15000);
        flushTerminal();
        if (currentAdapter != null) {
            currentModel = currentAdapter.extractModel(out);
            currentVersion = currentAdapter.extractVersion(out);
        }
        currentPatch = extractPatch(out);
        currentUptime = extractUptime(out);
    }

    /** 从 display version 输出中提取补丁信息行（取首个含 patch 的行）。 */
    private static String extractPatch(String text) {
        if (text == null) return "";
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.toLowerCase().contains("patch")) {
                return l.length() > 80 ? l.substring(0, 80) : l;
            }
        }
        return "";
    }

    /** 从 display version 输出中提取运行时间行（含 uptime 的行）。 */
    private static String extractUptime(String text) {
        if (text == null) return "";
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.toLowerCase().contains("uptime")) {
                return l.length() > 80 ? l.substring(0, 80) : l;
            }
        }
        return "";
    }

    /** 重新识别当前交换机端口（供界面主动刷新）。 */
    public void refreshPorts() {
        detectPorts();
    }

    // ---------------- 端口解析：分隔线定位 + 前缀缩写 + description 提取 ----------------

    /** 完整前缀 → 缩写映射表（长名在前，确保最长匹配优先） */
    private static final String[][] PREFIX_ABBR = {
            {"HundredGigabitEthernet", "100GE"},
            {"Twenty-FiveGigabitEthernet", "25GE"},
            {"TwentyFiveGigabitEthernet", "25GE"},
            {"FortyGigabitEthernet", "40GE"},
            {"FortyGigE", "40GE"},
            {"TFGigabitEthernet", "TFGE"},
            {"Ten-GigabitEthernet", "10GE"},
            {"TenGigabitEthernet", "10GE"},
            {"XGigabitEthernet", "10GE"},
            {"HundredGigE", "100GE"},
            {"GigabitEthernet", "GE"},
            {"FastEthernet", "FE"},
            {"M-GigabitEthernet", "MGE"},
            {"Bridge-Aggregation", "Agg"},
            {"AggregatePort", "Agg"},
            {"Eth-Trunk", "Agg"},
    };

    /** 行首端口名前缀识别（长名在前 + 可选空白 + 编号；含 H3C/华为缩写） */
    private static final Pattern PORT_AT_START = Pattern.compile(
            "^(HundredGigabitEthernet|HundredGigE|HundredGE|HGE|100GE"
            + "|Ten-GigabitEthernet|TenGigabitEthernet|XGigabitEthernet|XGE|10GE"
            + "|Twenty-FiveGigabitEthernet|TwentyFiveGigabitEthernet|25GE"
            + "|FortyGigabitEthernet|FortyGigE|FGE|40GE"
            + "|TFGigabitEthernet"
            + "|M-GigabitEthernet|M-GE|MGE"
            + "|GigabitEthernet|GE"
            + "|FastEthernet|FE"
            + "|Bridge-Aggregation|BAGG|AggregatePort|Eth-Trunk)"
            + "(\\s*)(\\d[\\d/\\.]*(?::\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** parsePortDescriptors 执行完后留下的诊断文本（供 detectPorts 打印到实时命令行） */
    private static String lastParseDiagnostic = null;

    private static boolean isLogicalPortName(String port) {
        String lower = port.toLowerCase();
        // 保留聚合口：Bridge-Aggregation / AggregatePort / Eth-Trunk 都不以这些词开头
        return lower.startsWith("vlan") || lower.startsWith("loop")
                || lower.startsWith("null") || lower.startsWith("meth")
                || lower.startsWith("mgmt") || lower.startsWith("aux")
                || lower.startsWith("console") || lower.startsWith("nve")
                || lower.startsWith("inloop") || lower.startsWith("reg")
                || lower.startsWith("mge");  // M-GE / MGE 管理口
    }

    /** 剥离 ANSI 转义序列与控制字符。 */
    private static String stripControlChars(String s) {
        if (s == null) return "";
        return s.replaceAll("\\x1b\\[[0-9;?]*[A-Za-z]", "")
                .replaceAll("[\\x00-\\x08\\x0b-\\x1f\\x7f-\\xff]", "");
    }

    /** 根据完整前缀查缩写，找不到返回完整前缀本身。 */
    private static String abbrOf(String fullPrefix) {
        for (String[] m : PREFIX_ABBR) {
            if (fullPrefix.equalsIgnoreCase(m[0])) return m[1];
        }
        return fullPrefix;
    }

    /** 清洗 description：stripControl + trim + 内部空白合并 + 截断 25 字符。 */
    private static String cleanDescription(String raw) {
        if (raw == null) return null;
        String s = stripControlChars(raw).trim();
        if (s.isEmpty()) return null;
        s = s.replaceAll("\\s+", " ");
        if (s.length() > 25) s = s.substring(0, 24) + "…";
        return s;
    }

    /**
     * 扫描分隔线（全是 '-' 和空格 的行），提取每个 '-' 段的起始位置。
     * 分隔线示例："---------------------------------------- -------- -------------- -----------"
     * 返回：各列起始位置数组，末尾补一行结束位置（= 行长度）。
     */
    private static int[] scanSeparatorStarts(String sepLine) {
        List<Integer> starts = new ArrayList<>();
        boolean inDash = false;
        for (int i = 0; i < sepLine.length(); i++) {
            char c = sepLine.charAt(i);
            if (c == '-' && !inDash) {
                starts.add(i);
                inDash = true;
            } else if (c != '-') {
                inDash = false;
            }
        }
        starts.add(sepLine.length());
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = starts.get(i);
        return arr;
    }

    /** 表头中查找关键字位置（用于匹配列含义）。 */
    private static int headerKeywordIndex(String header, String keyword) {
        return header.toLowerCase().indexOf(keyword.toLowerCase());
    }

    /** 在 starts 数组中找"第一个 >= 目标 indexOf"的列起始位置。 */
    private static int resolveColumnStart(int[] starts, int keywordIdx) {
        if (keywordIdx < 0) return -1;
        for (int s : starts) {
            if (s >= keywordIdx) return s;
        }
        return starts[starts.length - 2]; // 兜底：倒数第二个（最后一个是行尾）
    }

    /**
     * 从端口列表命令输出中解析 PortDescriptor 列表。
     * 通用策略：
     *  1. 剥 ANSI + 找分隔线 → scanSeparatorStarts 得各列起始位置 starts[]
     *  2. 根据表头关键字（Interface / Status / Administrative / Description）
     *     把 starts[] 映射为 ifaceCol / statusCol / adminCol / descCol
     *     adminCol 可能不存在（H3C 没有）
     *  3. 逐行解析，按列位置截取字段
     *  4. 过滤逻辑口（VLAN/Loopback/Null/Mgmt/Nve），保留聚合口
     *  5. 识别前缀 + 缩写 + 生成 displayName
     */
    public static List<PortDescriptor> parsePortDescriptors(String text) {
        List<PortDescriptor> result = new ArrayList<>();
        StringBuilder diag = new StringBuilder();
        if (text == null) return result;

        // ===== 预处理 =====
        // Step 0: 先 strip ANSI 转义 + 控制字符（最优先，避免后面所有正则被 ANSI 干扰）
        text = stripControlChars(text);

        // Step 1: 激进 strip 所有分页符（无论出现在行首/行中/行尾）
        // H3C/华为: ---- More ----  --More--  press any key to continue  Press Q to exit
        text = text.replaceAll("(?im)-{2,}\\s*more\\s*-{2,}\\s*", "");
        text = text.replaceAll("(?im)-{2,}\\s*more\\s*", "");
        text = text.replaceAll("(?im)press any key to continue[^\\n]*", "");
        text = text.replaceAll("(?im)press q to exit[^\\n]*", "");

        String[] rawLines = text.split("\\r?\\n");

        // Step 2: 续行合并 —— 把"不是端口名开头、不是表头、不是说明行、非空"的行 merge 回上一行
        // 原因：H3C display interface brief 终端宽度不够时，
        //   - 分页符切断当前数据行接口名残留到下一行
        //   - PVID + Description 被挤到下一行
        List<String> mergedLines = new ArrayList<>();
        for (String raw : rawLines) {
            String stripped = stripControlChars(raw).trim();
            if (stripped.isEmpty()) continue;

            // 分隔线
            if (stripped.matches("^[\\-\\s]+$")) {
                mergedLines.add(raw);
                continue;
            }

            // 说明行（跳过）—— 不 merge 进上一行
            String lower = stripped.toLowerCase();
            if (lower.startsWith("link:") || lower.startsWith("protocol:")
                    || lower.startsWith("speed:") || lower.startsWith("duplex:")
                    || lower.startsWith("type:") || lower.contains("brief information")) {
                continue;
            }

            // 表头行（跳过续行合并）
            boolean isHeader = (lower.contains("interface") && (lower.contains("link") || lower.contains("status")));
            if (isHeader) {
                mergedLines.add(raw);
                continue;
            }

            // 逻辑口数据行（跳过续行合并，作为独立行保留）
            // 避免 InLoop0/Vlan24/NULL0/REGO 等 merge 进上一行物理口数据导致 parts 污染
            boolean isLogicalDataRow =
                    lower.startsWith("inloop") || lower.startsWith("vlan")
                    || lower.startsWith("loop") || lower.startsWith("null")
                    || lower.startsWith("meth") || lower.startsWith("mgmt")
                    || lower.startsWith("aux") || lower.startsWith("console")
                    || lower.startsWith("nve") || lower.startsWith("reg");
            if (isLogicalDataRow) {
                mergedLines.add(raw);
                continue;
            }

            // 端口名开头 → 新数据行
            if (PORT_AT_START.matcher(stripped).lookingAt()) {
                mergedLines.add(raw);
                continue;
            }

            // 不是端口名开头 → 续行，merge 回上一行
            if (!mergedLines.isEmpty()) {
                String prev = mergedLines.get(mergedLines.size() - 1);
                mergedLines.set(mergedLines.size() - 1, prev + " " + raw.trim());
            } else {
                // 列表空时直接加（异常情况）
                mergedLines.add(raw);
            }
        }

        String[] lines = mergedLines.toArray(new String[0]);

        String headerLine = null;
        String sepLine = null;
        int headerLineIdx = -1;

        // 扫描表头行和分隔线行
        // 关键：不断覆盖 headerLine = 取**最后一个**含 interface+link/status 的行
        // H3C display interface brief 先输出逻辑口（InLoop0/MGE/Vlan）的表头，
        // 真正的物理口表头在后面，必须取最后一个！
        for (int i = 0; i < lines.length; i++) {
            String stripped = stripControlChars(lines[i]);
            if (sepLine == null && stripped.trim().matches("^[\\-\\s]+$")) {
                sepLine = lines[i];
                if (i > 0) {
                    headerLine = lines[i - 1];
                    headerLineIdx = i - 1;
                }
            }
            if (stripped.toLowerCase().contains("interface")
                    && (stripped.toLowerCase().contains("link") || stripped.toLowerCase().contains("status"))) {
                headerLine = lines[i];  // 不断覆盖 → 取最后一个
                headerLineIdx = i;
            }
        }

        // 表头折行处理
        if (headerLine != null && headerLineIdx >= 0 && headerLineIdx + 1 < lines.length) {
            String next = stripControlChars(lines[headerLineIdx + 1]).trim();
            if (!next.isEmpty() && !next.matches("^[\\-\\s]+$")) {
                String nextLower = next.toLowerCase();
                boolean isHeaderContinuation =
                        (nextLower.contains("pvid") || nextLower.contains("description")
                                || nextLower.contains("speed") || nextLower.contains("duplex")
                                || nextLower.contains("type"))
                                && !PORT_AT_START.matcher(next).lookingAt()
                                && !nextLower.startsWith("link:")
                                && !nextLower.startsWith("protocol:");
                if (isHeaderContinuation) {
                    headerLine = headerLine + " " + next;
                }
            }
        }

        // 兜底：没有表头
        if (headerLine == null) {
            for (String line : lines) {
                String clean = stripControlChars(line).trim();
                if (clean.isEmpty()) continue;
                Matcher m = PORT_AT_START.matcher(clean);
                if (m.lookingAt()) {
                    String raw = m.group(1) + m.group(2) + m.group(3);
                    if (isLogicalPortName(raw)) continue;
                    String prefixType = m.group(1);
                    String portNum = m.group(3).trim();
                    String after = clean.substring(m.end()).trim();
                    String status = "unknown";
                    if (!after.isEmpty()) {
                        String[] parts = after.split("\\s+");
                        if (parts.length >= 1) status = parts[0];
                    }
                    result.add(new PortDescriptor(raw, prefixType, abbrOf(prefixType),
                            portNum, null, status, null));
                }
            }
            // 无表头兜底路径的诊断
            diag.append("[深度诊断] 无表头兜底解析，共识别 ").append(result.size()).append(" 个端口\n");
            lastParseDiagnostic = diag.toString();
            return result;
        }

        String cleanHeader = stripControlChars(headerLine);

        // ===== 动态计算 Description 之前有多少个"标准列" =====
        // 表头拆分后，"Description" 之前的列数（包括 Interface）→ 减 1 = Interface 之后、Description 之前的字段数
        int standardFieldCount = 3; // 默认：Link + Protocol + IP（Description = parts[3..]）
        boolean hasDescCol = cleanHeader.toLowerCase().contains("description");
        if (hasDescCol) {
            String[] headerWords = cleanHeader.trim().split("\\s+");
            int descWordIdx = -1;
            for (int i = 0; i < headerWords.length; i++) {
                if (headerWords[i].equalsIgnoreCase("description")) {
                    descWordIdx = i;
                    break;
                }
            }
            if (descWordIdx > 0) {
                // descWordIdx = 表头里 "Description" 的 word index
                // standardFieldCount = descWordIdx - 1  ← 减去 Interface 列
                standardFieldCount = descWordIdx - 1;
            }
        }

        // ===== 深度诊断：表头信息 =====
        diag.append("[深度诊断]\n");
        diag.append("  sepLine=").append(sepLine != null ? "有(" + sepLine.trim().length() + "字符)" : "无").append("\n");
        diag.append("  cleanHeader=\"").append(cleanHeader.trim()).append("\"\n");
        diag.append("  hasDescCol=").append(hasDescCol)
                .append("  standardFieldCount=").append(standardFieldCount).append("\n");

        int ifaceCol, statusCol, descCol, adminCol;

        if (sepLine != null) {
            // 有分隔线 → 锐捷格式：用分隔线定位列
            int[] starts = scanSeparatorStarts(sepLine);
            ifaceCol = resolveColumnStart(starts, headerKeywordIndex(cleanHeader, "interface"));
            descCol  = resolveColumnStart(starts, headerKeywordIndex(cleanHeader, "description"));
            int statusKwIdx = headerKeywordIndex(cleanHeader, "status");
            if (statusKwIdx < 0) statusKwIdx = headerKeywordIndex(cleanHeader, "link");
            statusCol = resolveColumnStart(starts, statusKwIdx);
            int adminKwIdx = headerKeywordIndex(cleanHeader, "administrative");
            adminCol = adminKwIdx >= 0 ? resolveColumnStart(starts, adminKwIdx) : -1;
        } else {
            // 无分隔线 → H3C 格式：列对不齐，只能用空格拆分
            ifaceCol = -1; // 标记：不用列位置截取
            statusCol = -1;
            descCol = standardFieldCount; // Description = parts[standardFieldCount..]
            adminCol = -1;
        }

        // 逐行解析
        for (String line : lines) {
            String stripped = stripControlChars(line);
            String trimmed = stripped.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.matches("^[\\-\\s]+$")) continue;
            if (trimmed.equalsIgnoreCase(cleanHeader.trim())) continue;
            if (trimmed.toLowerCase().startsWith("link:")
                    || trimmed.toLowerCase().startsWith("protocol:")
                    || trimmed.toLowerCase().contains("brief information")) continue;

            // 用 PORT_AT_START 正则从行首匹配端口名
            // 关键：统一从 trimmed（已去前导空格）匹配，确保 pm.end() 与 trimmed.substring() 基准一致
            // 之前从 ifacePart 匹配但用 stripped.substring(pm.end())，当行首有空格（如 --More-- 打断后）
            // pm.end() 是相对于 ifacePart 的偏移量，作用在 stripped 上会导致位置偏移，parts 被污染
            Matcher pm = PORT_AT_START.matcher(trimmed);
            if (!pm.lookingAt()) continue;

            String rawName = pm.group(1) + pm.group(2) + pm.group(3);
            if (isLogicalPortName(rawName)) continue;

            String prefixType = pm.group(1);
            String portNum = pm.group(3).trim();
            String abbr = abbrOf(prefixType);

            // ===== 从端口名之后开始提取 =====
            // 用 trimmed 而非 stripped：pm 是从 trimmed 匹配的，pm.end() 是相对于 trimmed 的偏移量
            String restAfterPort = trimmed.substring(pm.end()).trim();
            String[] parts = restAfterPort.isEmpty() ? new String[0] : restAfterPort.split("\\s+");

            // Status（永远是 parts[0]）
            String status = parts.length >= 1 ? parts[0] : "unknown";

            // Description（parts[standardFieldCount..] 合并）
            String desc = null;
            if (hasDescCol && parts.length > standardFieldCount) {
                // 防御：parts[standardFieldCount] 如果是纯数字（PVID 值，1~4094），
                // 说明 Description 列实际为空，最后一个字段是 PVID
                String firstDescPart = parts[standardFieldCount];
                if (firstDescPart.matches("\\d+")) {
                    int maybeVlan = Integer.parseInt(firstDescPart);
                    if (maybeVlan >= 1 && maybeVlan <= 4094) {
                        // 这是 PVID，不是 Description，跳过
                    } else {
                        // 虽然是数字但不在 VLAN 范围（比如端口号的一部分），继续提取
                        desc = extractDescFromParts(parts, standardFieldCount);
                    }
                } else {
                    desc = extractDescFromParts(parts, standardFieldCount);
                }
            }

            result.add(new PortDescriptor(rawName, prefixType, abbr,
                    portNum, desc, status, null));

            // 逐行诊断（前 10 行，避免刷屏）
            if (result.size() <= 10) {
                diag.append("  [行").append(result.size()).append("] raw=\"").append(rawName)
                        .append("\"  rest=\"").append(restAfterPort).append("\"  parts[")
                        .append(parts.length).append("]=[");
                for (int p = 0; p < parts.length; p++) {
                    if (p > 0) diag.append(",");
                    diag.append("\"").append(parts[p]).append("\"");
                }
                diag.append("]  desc=").append(desc == null ? "null" : "\"" + desc + "\"")
                        .append("\n");
            }
        }

        lastParseDiagnostic = diag.toString();
        return result;
    }

    /** 判断提取出的 description 是否像状态/协议碎片（误提取的防御） */
    private static boolean looksLikeStatusFragment(String s) {
        if (s == null || s.isEmpty()) return true;
        String lower = s.toLowerCase();
        if (lower.equals("up") || lower.equals("down") || lower.equals("adm")
                || lower.equals("--") || lower.equals("up(s)") || lower.equals("")
                || lower.equals("up(stby)") || lower.equals("down(s)")) return true;
        // 1~3 个字符，大写/混合（可能是 F(a), A, T 这种）
        if (s.length() <= 3 && s.matches("[A-Za-z0-9()/]+")) return true;
        return false;
    }

    /** 从 parts[from..] 合并提取 Description，并做碎片校验 */
    private static String extractDescFromParts(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int j = from; j < parts.length; j++) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(parts[j]);
        }
        String desc = cleanDescription(sb.toString().trim());
        if (desc != null && looksLikeStatusFragment(desc)) {
            desc = null;
        }
        return desc;
    }

    /** 安全截取子串：自动处理边界。 */
    private static String safeSubstring(String s, int from, int to) {
        if (s == null) return "";
        int len = s.length();
        int a = Math.max(0, Math.min(from, len));
        int b = Math.max(a, Math.min(to, len));
        return s.substring(a, b);
    }

    /**
     * 识别当前交换机端口列表（全部为只读查询命令，不更改任何配置）。
     * 首选命令无结果时自动换备选命令重试（应对型号误识别）。
     */
    private void detectPorts() {
        portList.clear();
        if (shell == null || !shell.isConnected()) return;
        String cmd = (currentAdapter != null)
                ? currentAdapter.getPortListCommand() : "display interface brief";
        String firstOut = tryPortCommand(cmd);
        if (portList.isEmpty()) {
            String alt = cmd.startsWith("display")
                    ? "show interfaces status" : "display interface brief";
            notifyOutput("\n未解析到端口，尝试备选命令 " + alt + " ...\n");
            String altOut = tryPortCommand(alt);
            if (altOut != null && altOut.length() > firstOut.length()) {
                firstOut = altOut;
            }
        }
        notifyOutput("\n识别到 " + portList.size() + " 个端口。\n");

        // 打印 parsePortDescriptors 留下的诊断信息
        if (lastParseDiagnostic != null && !lastParseDiagnostic.isEmpty()) {
            notifyOutput(lastParseDiagnostic);
            lastParseDiagnostic = null;
        }

        if (!portList.isEmpty()) {
            // 诊断摘要：打印每个端口的 rawName + desc，方便排查 Description 问题
            StringBuilder diag = new StringBuilder("[诊断摘要] 每个端口的 rawName / desc：\n");
            int showCount = Math.min(portList.size(), 15);
            for (int i = 0; i < showCount; i++) {
                PortDescriptor p = portList.get(i);
                diag.append("  ").append(i + 1).append(". ")
                        .append(p.rawName).append(" | desc=")
                        .append(p.description == null ? "null" : "\"" + p.description + "\"")
                        .append(" | status=").append(p.status)
                        .append(" | displayName=").append(p.displayName)
                        .append("\n");
            }
            if (portList.size() > showCount) {
                diag.append("  ... 共 ").append(portList.size()).append(" 个\n");
            }
            notifyOutput(diag.toString());
        }
        if (portList.isEmpty() && firstOut != null) {
            // 诊断：显示解析输入的转义内容，便于定位格式问题
            StringBuilder diag = new StringBuilder("[诊断] 解析输入共 ")
                    .append(firstOut.length()).append(" 字符，前400字符转义显示：\n");
            int n = Math.min(firstOut.length(), 400);
            for (int i = 0; i < n; i++) {
                char c = firstOut.charAt(i);
                if (c == '\n') diag.append("\\n\n");
                else if (c >= 32 && c < 127) diag.append(c);
                else diag.append(String.format("\\x%02X", (int) c));
            }
            notifyOutput(diag.toString() + "\n");
        }
    }

    /** 执行端口列表命令并解析，成功时写入 portList；返回清理后的输出（失败返回 null）。 */
    private String tryPortCommand(String cmd) {
        try {
            int pos = shell.getBufferPosition();
            String out = shell.executeClean(cmd, 60000);
            flushTerminal();
            List<PortDescriptor> parsed = parsePortDescriptors(out);
            if (!parsed.isEmpty()) {
                portList.clear();
                portList.addAll(parsed);
            }
            return out;
        } catch (Exception e) {
            notifyOutput("\n端口识别命令执行失败：" + e.getMessage() + "\n");
            return null;
        }
    }

    /**
     * 在当前交换机上执行一条查询命令，返回输出。
     */
    public String executeQuery(String command) throws Exception {
        if (shell == null || !shell.isConnected()) {
            throw new IllegalStateException("未连接任何交换机");
        }
        int pos = shell.getBufferPosition();
        String output = shell.executeClean(command, 15000);
        flushTerminal();
        return output;
    }

    /**
     * 执行原始命令（不清理输出），用于查看全部配置等。
     */
    public String executeRaw(String command) throws Exception {
        if (shell == null || !shell.isConnected()) {
            throw new IllegalStateException("未连接任何交换机");
        }
        int pos = shell.getBufferPosition();
        shell.execute(command, 90000);
        flushTerminal();
        return shell.getNewOutput(pos);
    }

    public SwitchAdapter getCurrentAdapter() {
        return currentAdapter;
    }

    public SwitchType getCurrentType() {
        return currentAdapter != null ? currentAdapter.getType() : SwitchType.UNKNOWN;
    }

    public String getCurrentModel() {
        return currentModel;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public String getCurrentPatch() {
        return currentPatch;
    }

    public String getCurrentUptime() {
        return currentUptime;
    }

    public SwitchDevice getCurrentDevice() {
        return deviceStack.isEmpty() ? null : deviceStack.peek();
    }

    public int getCurrentLevel() {
        return deviceStack.size();
    }

    public boolean isCoreLevel() {
        return deviceStack.size() == 1;
    }

    public boolean isConnected() {
        return shell != null && shell.isConnected();
    }

    public SshShellSession getShell() {
        return shell;
    }

    /** 已验证可用的 ping 命令模板（%s 处填目标 IP） */
    private volatile String pingTemplate = null;
    /** 本设备只接受交互式 ping（裸 ping + 逐条回车） */
    private volatile boolean interactivePing = false;

    /**
     * 在线检测：在核心命令行里 ping 目标 IP（只读命令，不修改任何配置）。
     * 返回：1=在线，0=离线，-1=无法检测。
     */
    public int pingHost(String ip) {
        if (shell == null || !shell.isConnected()) return -1;
        if (interactivePing) return pingInteractive(ip);
        List<String> candidates = new ArrayList<>();
        if (pingTemplate != null) candidates.add(String.format(pingTemplate, ip));
        addPingCandidates(candidates, ip);
        for (String cmd : candidates) {
            int pos = shell.getBufferPosition();
            String out;
            try {
                out = shell.executeClean(cmd, 15000);
            } catch (Exception e) {
                out = "";
            }
            flushTerminal();
            String lower = stripControl(out).toLowerCase();
            if (lower.contains("protocol [") || lower.contains("extended commands")
                    || lower.contains("repeat count")) {
                // 进入了交互式 ping，按 Ctrl+C 退回提示符，避免影响后续命令
                try {
                    shell.sendRaw("\u0003");
                    shell.readUntilPrompt(3000);
                } catch (Exception ignored) {}
                continue;
            }
            if (isCommandRejected(lower)) continue;
            if (hasPingStatistics(lower)) {
                pingTemplate = cmd.replace(ip, "%s");
                return isPingReachable(lower) ? 1 : 0;
            }
        }
        // 带参数的写法全部被拒：改走交互式 ping（发裸 ping，按提示回车接受默认值）
        int r = pingInteractive(ip);
        if (r >= 0) {
            interactivePing = true;
            return r;
        }
        return -1;
    }

    /**
     * 交互式 ping：发送 ping IP，遇到提示逐条回车接受默认值（Repeat count 填 1，只发一个包）。
     */
    private int pingInteractive(String ip) {
        int pos = shell.getBufferPosition();
        try {
            shell.sendLine("ping " + ip);
            boolean done = false;
            for (int i = 0; i < 14 && !done; i++) {
                String chunk = shell.readUntil(
                        "(?i)(repeat count|datagram size|timeout in seconds"
                                + "|extended commands|sweep range|success rate is|packet loss"
                                + "|packets transmitted|invalid input|unrecognized|protocol \\[)",
                        4000);
                String lower = stripControl(chunk).toLowerCase();
                if (lower.contains("invalid input") || lower.contains("unrecognized")) break;
                if (hasPingStatistics(lower)) {
                    done = true;
                    break;
                }
                if (lower.contains("repeat count")) {
                    shell.sendLine("1");
                } else if (lower.contains("datagram size") || lower.contains("timeout in seconds")
                        || lower.contains("extended commands") || lower.contains("sweep range")
                        || lower.contains("protocol [")) {
                    shell.sendLine("");
                } else {
                    break;
                }
            }
            shell.readUntilPrompt(8000);
            String out = shell.getNewOutput(pos);
            notifyOutput(out);
            String lower = stripControl(out).toLowerCase();
            if (hasPingStatistics(lower)) return isPingReachable(lower) ? 1 : 0;
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 剥离 ANSI 转义与控制字符 */
    private static String stripControl(String s) {
        if (s == null) return "";
        return s.replaceAll("\\x1b\\[[0-9;?]*[A-Za-z]", "")
                .replaceAll("[\\x00-\\x08\\x0b-\\x1f\\x7f-\\xff]", "");
    }

    /** 按厂商追加 ping 候选写法（不同 RGOS/VRP 版本关键字不同） */
    private void addPingCandidates(List<String> candidates, String ip) {
        SwitchType type = currentAdapter != null ? currentAdapter.getType() : SwitchType.UNKNOWN;
        if (type == SwitchType.RUIJIE || type == SwitchType.UNKNOWN) {
            candidates.add("ping " + ip + " repeat 1");
            candidates.add("ping ip " + ip + " repeat 1");
            candidates.add("ping " + ip + " count 1");
            candidates.add("ping -c 1 " + ip);
        } else {
            candidates.add("ping -c 1 " + ip);
            candidates.add("ping " + ip + " count 1");
            candidates.add("ping " + ip + " repeat 1");
        }
    }

    /** 命令是否被设备判为非法/不支持 */
    private boolean isCommandRejected(String lower) {
        return lower.contains("invalid input") || lower.contains("unrecognized")
                || lower.contains("invalid command") || lower.contains("wrong parameter")
                || lower.contains("too many parameters") || lower.contains("error:")
                || lower.contains("incomplete command") || lower.contains("ambiguous");
    }

    /** 输出中是否包含 ping 统计信息（用于确认命令真的执行了） */
    private boolean hasPingStatistics(String lower) {
        return lower.contains("packet loss") || lower.contains("packets transmitted")
                || lower.contains("bytes from") || lower.contains("reply from")
                || lower.contains("success rate is");
    }

    /** 解析 ping 输出判断是否可达 */
    private boolean isPingReachable(String lower) {
        if (lower.contains("100% packet loss") || lower.contains("100.0% packet loss")) return false;
        if (lower.contains("0% packet loss") || lower.contains("0.0% packet loss")) return true;
        if (lower.contains("bytes from") || lower.contains("reply from")) return true;
        // Cisco/RGOS 风格：Success rate is 100 percent (1/1)
        if (lower.contains("success rate is 100")) return true;
        if (lower.contains("success rate is 0")) return false;
        if (lower.contains("0 percent (0/")) return false;
        return false;
    }

    /** TCP 探测目标主机端口是否可达（用于核心交换机在线检测，不影响已有 SSH 会话） */
    public static boolean isPortReachable(String host, int port, int timeoutMs) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 断开所有连接。
     */
    public void disconnectAll() {
        if (shell != null) {
            shell.setChunkListener(null);
            shell.disconnect();
        }
        terminalPos = 0;
        deviceStack.clear();
        currentAdapter = null;
        pingTemplate = null;
        interactivePing = false;
        coreHostName = "";
        currentModel = "";
        currentVersion = "";
        currentPatch = "";
        currentUptime = "";
        notifyOutput("\n连接已断开。\n");
    }
}
