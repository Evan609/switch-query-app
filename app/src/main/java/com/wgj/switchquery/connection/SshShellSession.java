package com.wgj.switchquery.connection;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSH 交互式 Shell 会话。
 * 封装 JSch，提供命令执行、提示符检测、分页处理能力。
 * 节点交换机的 telnet 也在核心的 shell 内部完成，因此本类同时承载核心与节点的命令通道。
 */
public class SshShellSession {

    private Session session;
    private ChannelShell channel;
    private InputStream in;
    private OutputStream out;
    private static final Charset GBK = Charset.forName("GBK");

    private final StringBuilder buffer = new StringBuilder();
    private final Object lock = new Object();
    private volatile boolean reading = false;
    private Thread readerThread;
    /** 新输出到达时的回调（实时显示命令行用） */
    private volatile Runnable chunkListener;

    // 严格提示符：整行独占、不含空格。
    // 华为/H3C: <hostname> 或 [hostname]；锐捷: hostname> 或 hostname#
    private static final Pattern STRICT_PROMPT = Pattern.compile(
            "^(<[A-Za-z0-9._\\-]+>|\\[[A-Za-z0-9._\\-]+\\]|[A-Za-z0-9._\\-]+[>#$])$"
    );

    // 分页提示（H3C/华为 "---- More ----"，锐捷 "--More--"，均要求两侧连字符，避免误匹配普通文本）
    private static final Pattern MORE_PATTERN = Pattern.compile(
            "(?i)-{2,}\\s*more\\s*-{2,}|press any key to continue"
    );

    public SshShellSession() {
    }

    public void connect(String host, int port, String user, String pass) throws Exception {
        JSch jsch = new JSch();
        session = jsch.getSession(user, host, port);
        session.setPassword(pass);

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password");
        session.setConfig(config);
        session.setTimeout(15000);
        session.connect(15000);

        channel = (ChannelShell) session.openChannel("shell");
        channel.setPty(true);
        // 终端窗口设为 200列 x 500行：窗口够高，常规查询输出一屏放得下，
        // 设备就不会产生 "---- More ----" 分页与重绘（即查询时命令行闪烁的来源）。
        // 这只是连接建立时的终端参数协商，等同于把终端窗口拉高，
        // 不向交换机发送任何命令，不涉及任何配置修改。
        channel.setPtyType("vt100", 200, 500, 0, 0);
        in = channel.getInputStream();
        out = channel.getOutputStream();
        channel.connect(15000);

        startReader();
        // 等待初始提示符
        readUntilPrompt(8000);
    }

    private void startReader() {
        reading = true;
        readerThread = new Thread(() -> {
            // 用 InputStreamReader + GBK 流式解码，自动处理 GBK 双字节字符在 chunk 边界截断的情况
            try (InputStreamReader reader = new InputStreamReader(in, GBK)) {
                char[] tmp = new char[8192];
                while (reading) {
                    int len = reader.read(tmp);
                    if (len < 0) break;
                    if (len > 0) {
                        synchronized (lock) {
                            String chunk = new String(tmp, 0, len);
                            buffer.append(chunk);
                            // 限制缓冲区大小，防止无限增长
                            if (buffer.length() > 512 * 1024) {
                                buffer.delete(0, buffer.length() - 256 * 1024);
                            }
                            lock.notifyAll();
                        }
                        // 检测分页并自动发送空格
                        String current = getBufferTail(200);
                        if (MORE_PATTERN.matcher(current).find()) {
                            sendRaw(" ");
                        }
                        // 通知外部有新输出（用于实时显示命令行）
                        Runnable l = chunkListener;
                        if (l != null) l.run();
                    }
                }
            } catch (Exception ignored) {
                // 连接断开
            }
        }, "ssh-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * 设置新输出回调，用于把设备返回实时推送到界面。
     */
    public void setChunkListener(Runnable listener) {
        this.chunkListener = listener;
    }

    private String getBufferTail(int length) {
        synchronized (lock) {
            if (buffer.length() <= length) return buffer.toString();
            return buffer.substring(buffer.length() - length);
        }
    }

    /**
     * 发送原始字节（不追加换行）。
     */
    public void sendRaw(String data) throws Exception {
        out.write(data.getBytes(GBK));
        out.flush();
    }

    /**
     * 发送命令并以换行结束。
     */
    public void sendLine(String command) throws Exception {
        out.write((command + "\n").getBytes(GBK));
        out.flush();
    }

    /**
     * 读取直到匹配指定正则表达式，返回匹配到的全部输出。
     */
    public String readUntil(String regex, long timeout) throws Exception {
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        long deadline = System.currentTimeMillis() + timeout;
        int startPos;
        synchronized (lock) {
            startPos = buffer.length();
        }
        while (true) {
            synchronized (lock) {
                String tail = buffer.length() > startPos
                        ? buffer.substring(startPos) : "";
                if (pattern.matcher(tail).find()) {
                    return tail;
                }
                if (System.currentTimeMillis() >= deadline) {
                    return tail; // 超时返回已有内容
                }
                lock.wait(Math.min(500, deadline - System.currentTimeMillis()));
            }
        }
    }

    /**
     * 读取直到出现命令提示符。
     */
    public String readUntilPrompt(long timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout;
        int startPos;
        synchronized (lock) {
            startPos = buffer.length();
        }
        while (true) {
            synchronized (lock) {
                String tail = buffer.length() > startPos
                        ? buffer.substring(startPos) : "";
                if (endsWithPrompt(tail)) {
                    return tail;
                }
                if (System.currentTimeMillis() >= deadline) {
                    return tail;
                }
                lock.wait(Math.min(500, deadline - System.currentTimeMillis()));
            }
        }
    }

    /**
     * 判断当前输出末尾是否停在命令提示符。
     */
    public boolean isAtPrompt() {
        synchronized (lock) {
            return endsWithPrompt(buffer.toString());
        }
    }

    /**
     * 取当前提示符中的主机名（如 JiLin_RuiJie_S6510_Switch）。
     * 末尾不是提示符时返回空串。
     */
    public String getLastPromptHost() {
        synchronized (lock) {
            // 只取缓冲区尾部，避免整段历史输出干扰判断
            int from = Math.max(0, buffer.length() - 600);
            String trimmed = stripAnsi(buffer.substring(from))
                    // 剥离可能与提示符同行的分页符
                    .replaceAll("(?im)-{2,}\\s*more\\s*-{0,}", "")
                    .replaceAll("(?i)press any key to continue", "")
                    .trim();
            if (trimmed.isEmpty()) return "";
            int nl = trimmed.lastIndexOf('\n');
            String lastLine = (nl >= 0 ? trimmed.substring(nl + 1) : trimmed).trim();
            if (!STRICT_PROMPT.matcher(lastLine).matches()) return "";
            return lastLine.replaceAll("[<>\\[\\]#$]", "").trim();
        }
    }

    /**
     * 判断输出末尾是否为命令提示符。
     */
    public static boolean endsWithPrompt(String text) {
        if (text == null) return false;
        // 剥离 ANSI 转义与控制字符后再判断，避免分片/转义导致漏检
        String cleaned = text.replaceAll("\\x1b\\[[0-9;?]*[A-Za-z]", "")
                .replaceAll("[\\x00-\\x08\\x0b-\\x1f\\x7f-\\xff]", "");
        // 剥离分页符（可能与提示符同一行，如 "--More--  HostName#"），
        // 否则严格整行匹配会因行首残留 "--More--" 而漏检提示符
        cleaned = cleaned.replaceAll("(?im)-{2,}\\s*more\\s*-{0,}", "")
                .replaceAll("(?i)press any key to continue", "");
        String trimmed = cleaned.trim();
        if (trimmed.isEmpty()) return false;
        // 取最后一行
        int lastNl = trimmed.lastIndexOf('\n');
        String lastLine = lastNl >= 0 ? trimmed.substring(lastNl + 1).trim() : trimmed;
        if (lastLine.isEmpty()) return false;
        // 严格整行匹配提示符：独占一行且不含空格，
        // 避免把输出/描述中以 >、# 结尾的行误判为提示符
        return STRICT_PROMPT.matcher(lastLine).matches();
    }

    /**
     * 执行一条命令，返回完整输出（包含命令回显和提示符）。
     * 通过命令回显锚定：必须等到本轮命令回显出现，且提示符出现在回显之后，
     * 避免上一轮残留/迟到的提示符被误判为本轮命令结束。
     */
    private static String stripAnsi(String s) {
        return s.replaceAll("\\x1b\\[[0-9;?]*[A-Za-z]", "")
                .replaceAll("[\\x00-\\x08\\x0b-\\x1f\\x7f-\\xff]", "");
    }

    public String execute(String command, long timeout) throws Exception {
        int startPos;
        synchronized (lock) {
            startPos = buffer.length();
        }
        sendLine(command);
        long deadline = System.currentTimeMillis() + timeout;
        // 回显等待窗口：3秒内未找到回显则退化为整体提示符判断，
        // 避免分片/控制字符导致回显匹配失败时干等超时
        long echoDeadline = System.currentTimeMillis() + 3000;
        long lastOverallCheck = 0;
        while (true) {
            synchronized (lock) {
                String tail = buffer.length() > startPos ? buffer.substring(startPos) : "";
                String clean = stripAnsi(tail);
                int echoIdx = clean.indexOf(command);
                if (echoIdx >= 0) {
                    String afterEcho = clean.substring(echoIdx + command.length());
                    if (endsWithPrompt(afterEcho)) {
                        return clean;
                    }
                } else if (System.currentTimeMillis() >= echoDeadline) {
                    // 回显超时未出现，退化为整体提示符判断（每200ms检查一次）
                    if (endsWithPrompt(clean)) {
                        return clean;
                    }
                }
                // 即使回显已出现，也定期检查整体提示符（回显后提示符可能被其他内容分隔）
                long now = System.currentTimeMillis();
                if (echoIdx >= 0 && now - lastOverallCheck >= 200) {
                    lastOverallCheck = now;
                    if (endsWithPrompt(clean)) {
                        return clean;
                    }
                }
                if (System.currentTimeMillis() >= deadline) {
                    return clean;
                }
                lock.wait(Math.min(200, deadline - System.currentTimeMillis()));
            }
        }
    }

    /**
     * 执行一条命令并返回清理后的输出（去掉命令回显和末尾提示符）。
     */
    public String executeClean(String command, long timeout) throws Exception {
        String raw = execute(command, timeout);
        if (raw == null || raw.isEmpty()) return "";
        // 去掉第一行（命令回显）
        String[] lines = raw.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        String result = sb.toString().trim();
        // 去掉末尾提示符行
        int lastNl = result.lastIndexOf('\n');
        if (lastNl >= 0) {
            String lastLine = result.substring(lastNl + 1).trim();
            if (endsWithPrompt(lastLine)) {
                result = result.substring(0, lastNl).trim();
            }
        }
        return result;
    }

    /**
     * 获取当前缓冲区全部内容（用于实时显示命令行）。
     */
    public String getFullOutput() {
        synchronized (lock) {
            return buffer.toString();
        }
    }

    /**
     * 获取从指定位置开始的新增输出。
     */
    public String getNewOutput(int sincePos) {
        synchronized (lock) {
            if (sincePos >= buffer.length()) return "";
            return buffer.substring(sincePos);
        }
    }

    public int getBufferPosition() {
        synchronized (lock) {
            return buffer.length();
        }
    }

    public boolean isConnected() {
        return session != null && session.isConnected()
                && channel != null && channel.isConnected();
    }

    public void disconnect() {
        reading = false;
        try {
            if (readerThread != null) readerThread.interrupt();
        } catch (Exception ignored) {}
        try {
            if (channel != null) channel.disconnect();
        } catch (Exception ignored) {}
        try {
            if (session != null) session.disconnect();
        } catch (Exception ignored) {}
        channel = null;
        session = null;
    }
}
