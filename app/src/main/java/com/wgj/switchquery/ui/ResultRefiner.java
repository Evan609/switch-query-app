package com.wgj.switchquery.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询结果精简器：把交换机返回的完整命令行解析为易读的精简信息。
 * 原始命令行输出不受影响，始终完整显示在终端区。
 */
public class ResultRefiner {

    /** 按功能类型精简输出。 */
    public static String refine(String type, String raw) {
        if (raw == null || raw.trim().isEmpty()) return "（无返回内容）";
        switch (type) {
            case "port_up": return refinePortUp(raw);
            case "port_vlan": return refinePortVlan(raw);
            case "port_optical": return refineOptical(raw);
            case "port_errors": return refineErrors(raw);
            case "vlan_ip": return refineVlanIp(raw);
            case "all_vlans": return refineAllVlans(raw);
            default: return raw.trim();
        }
    }

    /** 端口UP状态：只显示 UP / DOWN。 */
    private static String refinePortUp(String raw) {
        String state = null;
        Matcher m = Pattern.compile(
                "(?i)current state\\s*:?\\s*(administratively down|up|down)").matcher(raw);
        if (m.find()) state = m.group(1);
        if (state == null) {
            m = Pattern.compile(
                    "(?i)is\\s+(administratively down|up|down)").matcher(raw);
            if (m.find()) state = m.group(1);
        }
        if (state == null) return "未获取到端口状态";
        if (state.equalsIgnoreCase("up")) return "端口状态：UP";
        if (state.toLowerCase().contains("admin")) return "端口状态：DOWN（管理性关闭）";
        return "端口状态：DOWN";
    }

    /** 端口VLAN：只显示 VLAN ID（Trunk 口显示 Native 与放行列表）。 */
    private static String refinePortVlan(String raw) {
        // H3C display interface：PVID + Tagged VLAN ID 列表
        String pvid = null;
        Matcher mp = Pattern.compile(
                "(?i)(pvid|untagged\\s+vlan(?:\\s+id)?)\\s*[:：]\\s*(\\d{1,4})").matcher(raw);
        if (mp.find()) pvid = mp.group(2);
        Matcher mt = Pattern.compile(
                "(?i)tagged\\s+vlan(?:\\s+id)?\\s*[:：]?\\s*([\\d,\\s\\-]{1,300})").matcher(raw);
        String tagged = null;
        if (mt.find()) {
            tagged = mt.group(1).replaceAll("[\\s,\\-]+$", "").trim();
            if (tagged.isEmpty() || "none".equalsIgnoreCase(tagged)) tagged = null;
        }
        // H3C Trunk：放行列表在 "VLAN permitted"（部分版本为 "VLAN Passing"）行，可能折行
        String permitted = extractH3cVlanList(raw, "vlan\\s+permitted");
        if (permitted == null) permitted = extractH3cVlanList(raw, "vlan\\s+passing");
        if (pvid != null && permitted != null) {
            return "端口VLAN（Trunk）：Native " + pvid + "  放行 " + permitted;
        }
        if (pvid != null && tagged != null) {
            return "端口VLAN（Trunk）：Native " + pvid + "  放行 " + tagged;
        }
        if (permitted != null) {
            return "端口VLAN（Trunk）：放行 " + permitted;
        }
        if (pvid != null) return "端口VLAN：" + pvid;
        Matcher m = Pattern.compile("(?i)access\\s+vlan\\s*[:：]\\s*(\\d{1,4})").matcher(raw);
        if (m.find()) return "端口VLAN：" + m.group(1);
        // 锐捷表格式：名称 [编号] enabled ACCESS/TRUNK accVlan native [Protected] vlanLists
        m = Pattern.compile(
                "(?im)^\\S+(?:\\s+\\S+)?\\s+enabled\\s+(access|trunk)\\s+(\\d{1,4})"
                + "\\s+(\\d{1,4})(?:\\s+\\S+)?\\s*(.*)$").matcher(raw);
        if (m.find()) {
            if ("access".equalsIgnoreCase(m.group(1))) return "端口VLAN：" + m.group(2);
            String list = appendVlanContinuations(raw, m.end(),
                    m.group(4) == null ? "" : m.group(4).trim());
            return "端口VLAN（Trunk）：Native " + m.group(3)
                    + (list.isEmpty() ? "" : "  放行 " + list);
        }
        // 华为 display port vlan 表格式：名称 linkType pvid [放行列表]
        m = Pattern.compile(
                "(?im)^\\S+\\s+(access|trunk|hybrid)\\s+(\\d{1,4})[ \\t]*(.*)$").matcher(raw);
        if (m.find()) {
            String hwPvid = m.group(2);
            String list = appendVlanContinuations(raw, m.end(), m.group(3).trim());
            if ("access".equalsIgnoreCase(m.group(1))) return "端口VLAN：" + hwPvid;
            return "端口VLAN（Trunk）：Native " + hwPvid
                    + (list.isEmpty() ? "" : "  放行 " + list);
        }
        // 行式 Native + Allowed
        Matcher mn = Pattern.compile(
                "(?i)native\\s+vlan\\s*[:：]?\\s*(\\d{1,4})").matcher(raw);
        Matcher ma = Pattern.compile(
                "(?i)(allowed\\s+vlans?|tagged\\s+vlans?)[^\\d\\n]*([\\d\\s,\\-]+)").matcher(raw);
        String nativeVlan = mn.find() ? mn.group(1) : null;
        String allowed = ma.find() ? ma.group(2).trim() : null;
        if (nativeVlan != null || allowed != null) {
            StringBuilder sb = new StringBuilder("端口VLAN（Trunk）：");
            if (nativeVlan != null) sb.append("Native ").append(nativeVlan);
            if (allowed != null) sb.append("  放行 ").append(allowed);
            return sb.toString();
        }
        // 兜底：通用多行 VLAN 列表提取。
        // 某些设备（如 H3C 聚合口）的 VLAN 列表因终端宽度不足折行，
        // 续行以数字开头且只含数字/逗号/连字符，需合并后才能拿到完整列表。
        String vlanList = findMultiLineVlanList(raw);
        if (vlanList != null) {
            if (pvid != null) {
                return "端口VLAN（Trunk）：Native " + pvid + "  放行 " + vlanList;
            }
            return "端口VLAN（Trunk）：放行 " + vlanList;
        }
        return "未获取到VLAN信息";
    }

    /**
     * 通用多行 VLAN 列表提取：扫描所有行，找到含 VLAN ID 列表
     * （数字、逗号、连字符）的行，并合并后续以数字开头的续行。
     * 返回 VLAN 条目最多的列表字符串，如 "20,23,120-139,2001-2020,2301-2320,2395"。
     */
    private static String findMultiLineVlanList(String raw) {
        if (raw == null) return null;
        String[] lines = raw.split("\\r?\\n");
        String best = null;
        int bestCount = 0;
        // VLAN 列表片段：至少含一个逗号或连字符，以数字开头
        Pattern segRe = Pattern.compile("(\\d{1,4}[,\\-]\\d{1,4}(?:[,\\s]+\\d{1,4}(?:[,\\-]\\d{1,4})*)*)");
        for (int i = 0; i < lines.length; i++) {
            Matcher vm = segRe.matcher(lines[i]);
            if (!vm.find()) continue;
            StringBuilder list = new StringBuilder(vm.group(1));
            // 合并后续续行：以数字开头，且只含数字/逗号/空格/连字符
            for (int j = i + 1; j < lines.length; j++) {
                String t = lines[j].trim();
                if (t.isEmpty() || !Character.isDigit(t.charAt(0))) break;
                if (!t.matches("[\\d,\\s\\-]+,?")) break;
                list.append(",").append(t);
            }
            String result = list.toString().replaceAll("\\s+", "");
            int count = result.split(",").length;
            if (count > bestCount) {
                bestCount = count;
                best = result;
            }
        }
        return best;
    }

    /**
     * 提取 H3C display interface 输出中 "VLAN permitted" / "VLAN Passing" 的 VLAN 列表。
     * 列表较长时会折行，后续行以数字开头则继续拼接到列表末尾。
     */
    private static String extractH3cVlanList(String raw, String keyword) {
        Matcher m = Pattern.compile(
                "(?im)^\\s*" + keyword + "\\s*[:：]\\s*(.*)$").matcher(raw);
        if (!m.find()) return null;
        StringBuilder list = new StringBuilder(m.group(1).trim());
        String rest = raw.substring(m.end());
        for (String line : rest.split("\\r?\\n")) {
            String t = line.trim();
            // 跳过空行：正则 (.*)$ 的 end() 指向换行符之前，substring 后首段为空
            if (t.isEmpty()) continue;
            // 折行的续段只含数字/逗号/空格/连字符，且以数字开头
            if (Character.isDigit(t.charAt(0)) && t.matches("[\\d,\\s\\-]+,?")) {
                list.append(" ").append(t);
            } else {
                break;
            }
        }
        String result = list.toString()
                .replaceAll("(?i)\\(default\\s+vlan\\)", "")
                .replaceAll("[,\\s]+$", "")
                .trim();
        if (result.isEmpty() || "none".equalsIgnoreCase(result)) return null;
        return result;
    }

    /**
     * 从指定位置开始扫描后续行，合并 VLAN 列表的续行。
     * 续行判断：以数字开头，且只含数字/逗号/空格/连字符。
     *
     * @param raw        原始输出
     * @param fromIndex  起始扫描位置（通常为匹配器的 end()）
     * @param initial    已匹配到的首段 VLAN 列表
     * @return 合并续行后的完整列表字符串
     */
    private static String appendVlanContinuations(String raw, int fromIndex, String initial) {
        if (raw == null || fromIndex >= raw.length()) return initial == null ? "" : initial;
        StringBuilder list = new StringBuilder(initial == null ? "" : initial);
        String rest = raw.substring(fromIndex);
        for (String line : rest.split("\\r?\\n")) {
            String t = line.trim();
            // 跳过空行：正则 (.*)$ 的 end() 指向换行符之前，substring 后首段为空
            if (t.isEmpty()) continue;
            if (!Character.isDigit(t.charAt(0))) break;
            if (!t.matches("[\\d,\\s\\-]+,?")) break;
            list.append(",").append(t);
        }
        return list.toString().replaceAll("\\s+", "");
    }

    /** 端口光模块：显示 current diagnostic parameters 区段的当前 RX / TX 光功率，支持多通道（100G/40G）。 */
    private static String refineOptical(String raw) {
        String section = extractCurrentSection(raw);
        String[] lines = section.split("\\r?\\n");
        List<String> rxList = new ArrayList<>();
        List<String> txList = new ArrayList<>();
        Pattern exclude = Pattern.compile("threshold|alarm|warn|default|high|low|min|max");
        // 策略1：关键字锚定——"RX power"/"TX power" 字样后紧跟的数字，
        // 值永远跟随名称，不受通道号位置（行首/行尾）、同行其他参数影响
        Pattern rxRe = Pattern.compile("(?i)rx\\s*power[^:：0-9\\-]*[:：]?\\s*(-?\\d+\\.?\\d*)");
        Pattern txRe = Pattern.compile("(?i)tx\\s*power[^:：0-9\\-]*[:：]?\\s*(-?\\d+\\.?\\d*)");
        for (String line : lines) {
            if (exclude.matcher(line.toLowerCase()).find()) continue;
            Matcher m = rxRe.matcher(line);
            if (m.find()) rxList.add(m.group(1));
            m = txRe.matcher(line);
            if (m.find()) txList.add(m.group(1));
        }
        // 策略2：锐捷 (OK)[Cn] 标记格式（100G/40G 多通道）。
        // RX 值带 "[Cn/" 前缀（如 -13.60(OK)[C1/ 或 [C1/AP]），TX 值在 "AP]" 之后；
        // 80 列折行会把 [Cn/AP] 拆成两行，该规则对折行免疫
        if (rxList.isEmpty() && txList.isEmpty()) {
            Matcher m = Pattern.compile("(-?\\d+\\.?\\d*)\\(OK\\)\\[C\\d+/").matcher(section);
            while (m.find()) rxList.add(m.group(1));
            m = Pattern.compile("AP\\]\\s*(-?\\d+\\.?\\d*)").matcher(section);
            while (m.find()) txList.add(m.group(1));
        }
        // 策略3：收集表头行之后全部数据数字流（剥离 [Cn] 标记），
        // 光功率表列序固定为 ...RX、TX，故 RX=倒数第二、TX=倒数第一；
        // 对折行（RX/TX 被折到不同行）免疫，并支持单边补缺
        // H3C 特殊处理：跳过 [module] 段，只取 [channel] 段的数据行
        if (rxList.isEmpty() || txList.isEmpty()) {
            boolean headerFound = false;
            boolean inChannelSection = false;
            List<List<String>> rows = new ArrayList<>();
            for (String line : lines) {
                String low = line.toLowerCase();
                // H3C: [module] 段结束，[channel] 段开始
                if (low.contains("[channel]") || (low.contains("channel") && low.contains("bias"))) {
                    inChannelSection = true;
                    headerFound = true;
                    continue;
                }
                if (!inChannelSection && low.contains("[module]")) {
                    headerFound = false; // module 段不是光功率数据
                    continue;
                }
                if (low.contains("rx") && low.contains("power") && numbersOf(line).isEmpty()) {
                    if (exclude.matcher(low).find()) continue;
                    headerFound = true;
                    continue;
                }
                if (headerFound) {
                    String clean = line.replaceAll("\\[[^\\]]*\\]?", " ");
                    List<String> nums = numbersOf(clean);
                    if (!nums.isEmpty()) {
                        if (inChannelSection) {
                            // channel 段：每行 N Bias RX TX，取倒数第二和倒数第一
                            if (nums.size() >= 2) {
                                List<String> row = new ArrayList<>();
                                row.add(nums.get(nums.size() - 2));
                                row.add(nums.get(nums.size() - 1));
                                rows.add(row);
                            }
                        } else {
                            // 非 channel 段（单通道/通用）：收集所有数字
                            rows.add(nums);
                        }
                    }
                }
            }
            if (!rows.isEmpty()) {
                if (inChannelSection) {
                    // 多通道：每行取 RX/TX
                    for (List<String> row : rows) {
                        rxList.add(row.get(0));
                        txList.add(row.get(1));
                    }
                } else {
                    // 单通道/通用：取最后一行的最后两个数字
                    List<String> last = rows.get(rows.size() - 1);
                    if (last.size() >= 2) {
                        if (rxList.isEmpty()) rxList.add(last.get(last.size() - 2));
                        if (txList.isEmpty()) txList.add(last.get(last.size() - 1));
                    }
                }
            }
        }
        if (rxList.isEmpty() && txList.isEmpty()) {
            return "未获取到光功率信息（可能无光模块或不支持）";
        }
        int n = Math.max(rxList.size(), txList.size());
        if (n == 1) {
            return "RX 光功率：" + (rxList.isEmpty() ? "-" : rxList.get(0) + " dBm")
                    + "\nTX 光功率：" + (txList.isEmpty() ? "-" : txList.get(0) + " dBm");
        }
        StringBuilder sb = new StringBuilder("光功率（共 " + n + " 通道）：");
        for (int k = 0; k < n; k++) {
            sb.append("\n通道").append(k + 1).append("：RX ")
                    .append(k < rxList.size() ? rxList.get(k) : "-").append(" dBm / TX ")
                    .append(k < txList.size() ? txList.get(k) : "-").append(" dBm");
        }
        return sb.toString();
    }

    /** 提取一行中的全部数字（含负数与小数）。 */
    private static List<String> numbersOf(String line) {
        List<String> list = new ArrayList<>();
        Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(line);
        while (m.find()) list.add(m.group());
        return list;
    }

    /**
     * 截取 "current diagnostic parameters" 区段文本（到 threshold/alarm 区段标题或文末为止）。
     * 无该区段标题时返回全文（行式输出的设备靠行内排除词过滤阈值行）。
     */
    private static String extractCurrentSection(String raw) {
        String lower = raw.toLowerCase();
        int start = lower.indexOf("current diagnostic parameters");
        if (start < 0) return raw;
        int end = raw.length();
        for (String kw : new String[]{"threshold", "alarm"}) {
            int idx = lower.indexOf(kw, start + 10);
            if (idx >= 0 && idx < end) end = idx;
        }
        return raw.substring(start, end);
    }

    /** 端口错误包：input/output errors 即为收/发方向错误总和，不再叠加其他计数。 */
    private static String refineErrors(String raw) {
        long in = findCount(raw, "(?i)(\\d+)\\s+input\\s+errors?\\b");
        long out = findCount(raw, "(?i)(\\d+)\\s+output\\s+errors?\\b");
        if (in < 0) in = findCount(raw, "(?i)input\\s+errors?\\s*[:：]\\s*(\\d+)");
        if (out < 0) out = findCount(raw, "(?i)output\\s+errors?\\s*[:：]\\s*(\\d+)");
        if (in >= 0 || out >= 0) {
            return "错误包总数：" + (Math.max(in, 0) + Math.max(out, 0));
        }
        // 华为段式输出（Input: ... 0 errors ...）无 input 前缀，按 "N errors" 求和兜底。
        // 排除 CRC/帧等明细计数行，避免重复相加
        Matcher m = Pattern.compile("(?i)(\\d+)\\s+errors\\b").matcher(raw);
        long total = 0;
        boolean found = false;
        while (m.find()) {
            String tail = raw.substring(Math.max(0, m.start() - 40), m.start()).toLowerCase();
            if (tail.contains("crc") || tail.contains("frame") || tail.contains("align")
                    || tail.contains("symbol") || tail.contains("fifo")) continue;
            total += Long.parseLong(m.group(1));
            found = true;
        }
        if (!found) return "未获取到错误包统计";
        return "错误包总数：" + total;
    }

    private static long findCount(String raw, String regex) {
        Matcher m = Pattern.compile(regex).matcher(raw);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** 查看VLAN的IP：只显示 IP 与掩码。 */
    private static String refineVlanIp(String raw) {
        Matcher m = Pattern.compile(
                "(\\d{1,3}(?:\\.\\d{1,3}){3})(?:/(\\d{1,2}))?"
                + "(?:[\\s,]+(\\d{1,3}(?:\\.\\d{1,3}){3}))?").matcher(raw);
        if (!m.find()) return "该VLAN未配置IP地址";
        String ip = m.group(1);
        if ("0.0.0.0".equals(ip)) return "该VLAN未配置IP地址";
        String prefix = m.group(2);
        String dotMask = m.group(3);
        StringBuilder sb = new StringBuilder("IP：" + ip);
        if (prefix != null) {
            sb.append("\n掩码：").append(prefixToMask(prefix)).append("（/").append(prefix).append("）");
        } else if (dotMask != null) {
            sb.append("\n掩码：").append(dotMask);
        }
        return sb.toString();
    }

    /** 前缀长度转点分掩码。 */
    private static String prefixToMask(String prefix) {
        try {
            int p = Integer.parseInt(prefix);
            if (p < 0 || p > 32) return "/" + prefix;
            long mask = p == 0 ? 0 : (0xFFFFFFFFL << (32 - p)) & 0xFFFFFFFFL;
            return ((mask >> 24) & 0xFF) + "." + ((mask >> 16) & 0xFF)
                    + "." + ((mask >> 8) & 0xFF) + "." + (mask & 0xFF);
        } catch (NumberFormatException e) {
            return "/" + prefix;
        }
    }

    /** 查看所有VLAN：只显示 VLAN ID 列表。 */
    private static String refineAllVlans(String raw) {
        Set<Integer> vlans = new TreeSet<>();
        // 表格行首数字（华为 VID 表、锐捷 VLAN 表）
        extractVlansFromText(vlans, raw, "(?im)^\\s*(\\d{1,4})\\s+\\S");
        // H3C "VLAN ID: 100" 段
        extractVlansFromText(vlans, raw, "(?i)vlan\\s+id\\s*[:：]\\s*(\\d{1,4})");
        // H3C "The VLANs include: 1(default), 100, 2401-2420" 块
        Matcher mi = Pattern.compile(
                "(?i)vlans?\\s+include[^\\n]*\\n([\\s\\S]{0,300})").matcher(raw);
        if (mi.find()) {
            extractVlansFromText(vlans, mi.group(1), "\\d{1,4}");
        }
        if (vlans.isEmpty()) return "未获取到VLAN列表";
        StringBuilder sb = new StringBuilder("VLAN列表（共 " + vlans.size() + " 个）：\n");
        int i = 0;
        for (int v : vlans) {
            sb.append(v);
            if (++i % 10 == 0) sb.append("\n"); else sb.append("   ");
        }
        return sb.toString().trim();
    }

    /**
     * 从一段文本中提取 VLAN ID（含范围展开）。
     * 先匹配范围 {@code \d{1,4}-\d{1,4}} 展开，再匹配独立数字。
     *
     * @param set       结果集
     * @param text      待提取文本
     * @param numPattern 独立数字的匹配正则（用 group(1) 取数字）
     */
    private static void extractVlansFromText(Set<Integer> set, String text, String numPattern) {
        // 第一轮：匹配范围 \d{1,4}-\d{1,4}（支持 - 和 –）
        Matcher rangeMatcher = Pattern.compile("(\\d{1,4})\\s*[-–]\\s*(\\d{1,4})").matcher(text);
        while (rangeMatcher.find()) {
            addVlanRange(set, rangeMatcher.group(1), rangeMatcher.group(2));
        }
        // 第二轮：匹配独立数字（TreeSet 自动去重）
        // 注意：numPattern 可能无捕获组（如 "\\d{1,4}"），此时 groupCount()=0，要用 group() 而非 group(1)
        Matcher m = Pattern.compile(numPattern).matcher(text);
        while (m.find()) {
            String num = m.groupCount() >= 1 ? m.group(1) : m.group();
            if (num != null) addVlan(set, num);
        }
    }

    private static void addVlan(Set<Integer> set, String s) {
        try {
            int id = Integer.parseInt(s);
            if (id >= 1 && id <= 4094) set.add(id);
        } catch (NumberFormatException ignored) {}
    }

    private static void addVlanRange(Set<Integer> set, String startStr, String endStr) {
        try {
            int start = Integer.parseInt(startStr);
            int end = Integer.parseInt(endStr);
            if (start > end) { int t = start; start = end; end = t; }
            // 限制范围大小，防止误匹配导致几千个 VLAN 被展开
            if (end - start > 500 || start < 1 || end > 4094) return;
            for (int i = start; i <= end; i++) set.add(i);
        } catch (NumberFormatException ignored) {}
    }
}
