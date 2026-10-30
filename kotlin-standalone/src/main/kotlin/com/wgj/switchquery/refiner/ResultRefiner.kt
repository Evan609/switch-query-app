package com.wgj.switchquery.refiner

import java.util.TreeSet

/**
 * 查询结果精简器（Kotlin 版，与 Java 版 com.wgj.switchquery.ui.ResultRefiner 行为逐行对应）。
 *
 * 只做纯字符串解析，不依赖 Android；输出文案与 Java 版完全一致，
 * 便于用 tools/ParityDump.java 做逐字节对比。
 */
object ResultRefiner {

    /** 按功能类型精简输出。 */
    @JvmStatic
    fun refine(type: String, raw: String?): String {
        if (raw == null || raw.trim().isEmpty()) return "（无返回内容）"
        return when (type) {
            "port_up" -> refinePortUp(raw)
            "port_vlan" -> refinePortVlan(raw)
            "port_optical" -> refineOptical(raw)
            "port_errors" -> refineErrors(raw)
            "vlan_ip" -> refineVlanIp(raw)
            "all_vlans" -> refineAllVlans(raw)
            else -> raw.trim()
        }
    }

    /** 端口UP状态：只显示 UP / DOWN。 */
    private fun refinePortUp(raw: String): String {
        var state: String? = Regex("(?i)current state\\s*:?\\s*(administratively down|up|down)")
            .find(raw)?.groupValues?.get(1)
        if (state == null) {
            state = Regex("(?i)is\\s+(administratively down|up|down)")
                .find(raw)?.groupValues?.get(1)
        }
        if (state == null) return "未获取到端口状态"
        if (state.equals("up", ignoreCase = true)) return "端口状态：UP"
        if (state.lowercase().contains("admin")) return "端口状态：DOWN（管理性关闭）"
        return "端口状态：DOWN"
    }

    /** 端口VLAN：只显示 VLAN ID（Trunk 口显示 Native 与放行列表）。 */
    private fun refinePortVlan(raw: String): String {
        // H3C display interface：PVID + Tagged VLAN ID 列表
        val pvid: String? = Regex("(?i)(pvid|untagged\\s+vlan(?:\\s+id)?)\\s*[:：]\\s*(\\d{1,4})")
            .find(raw)?.groupValues?.get(2)

        var tagged: String? = Regex("(?i)tagged\\s+vlan(?:\\s+id)?\\s*[:：]?\\s*([\\d,\\s\\-]{1,300})")
            .find(raw)?.groupValues?.get(1)
        if (tagged != null) {
            tagged = Regex("[\\s,\\-]+$").replace(tagged, "").trim()
            if (tagged.isEmpty() || tagged.equals("none", ignoreCase = true)) tagged = null
        }

        // H3C Trunk：放行列表在 "VLAN permitted"（部分版本为 "VLAN Passing"）行，可能折行
        var permitted = extractH3cVlanList(raw, "vlan\\s+permitted")
        if (permitted == null) permitted = extractH3cVlanList(raw, "vlan\\s+passing")

        if (pvid != null && permitted != null) {
            return "端口VLAN（Trunk）：Native " + pvid + "  放行 " + permitted
        }
        if (pvid != null && tagged != null) {
            return "端口VLAN（Trunk）：Native " + pvid + "  放行 " + tagged
        }
        if (permitted != null) {
            return "端口VLAN（Trunk）：放行 " + permitted
        }
        if (pvid != null) return "端口VLAN：" + pvid

        Regex("(?i)access\\s+vlan\\s*[:：]\\s*(\\d{1,4})").find(raw)?.let {
            return "端口VLAN：" + it.groupValues[1]
        }

        // 锐捷表格式：名称 [编号] enabled ACCESS/TRUNK accVlan native [Protected] vlanLists
        val ruijie = Regex(
            "(?im)^\\S+(?:\\s+\\S+)?\\s+enabled\\s+(access|trunk)\\s+(\\d{1,4})" +
                "\\s+(\\d{1,4})(?:\\s+\\S+)?\\s*(.*)$"
        ).find(raw)
        if (ruijie != null) {
            if (ruijie.groupValues[1].equals("access", ignoreCase = true)) {
                return "端口VLAN：" + ruijie.groupValues[2]
            }
            val initial = ruijie.groups[4]?.value ?: ""
            val list = appendVlanContinuations(raw, ruijie.range.last + 1, initial.trim())
            return "端口VLAN（Trunk）：Native " + ruijie.groupValues[3] +
                if (list.isEmpty()) "" else "  放行 " + list
        }

        // 华为 display port vlan 表格式：名称 linkType pvid [放行列表]
        val huawei = Regex("(?im)^\\S+\\s+(access|trunk|hybrid)\\s+(\\d{1,4})[ \\t]*(.*)$").find(raw)
        if (huawei != null) {
            val hwPvid = huawei.groupValues[2]
            val list = appendVlanContinuations(
                raw,
                huawei.range.last + 1,
                (huawei.groups[3]?.value ?: "").trim(),
            )
            if (huawei.groupValues[1].equals("access", ignoreCase = true)) return "端口VLAN：" + hwPvid
            return "端口VLAN（Trunk）：Native " + hwPvid +
                if (list.isEmpty()) "" else "  放行 " + list
        }

        // 行式 Native + Allowed
        val nativeVlan = Regex("(?i)native\\s+vlan\\s*[:：]?\\s*(\\d{1,4})")
            .find(raw)?.groupValues?.get(1)
        val allowed = Regex("(?i)(allowed\\s+vlans?|tagged\\s+vlans?)[^\\d\\n]*([\\d\\s,\\-]+)")
            .find(raw)?.groupValues?.get(2)?.trim()
        if (nativeVlan != null || allowed != null) {
            val sb = StringBuilder("端口VLAN（Trunk）：")
            if (nativeVlan != null) sb.append("Native ").append(nativeVlan)
            if (allowed != null) sb.append("  放行 ").append(allowed)
            return sb.toString()
        }

        // 兜底：通用多行 VLAN 列表提取。
        // 走到这里 pvid 必为 null（上方已有 if (pvid != null) return），与 Java 版分支等价。
        val vlanList = findMultiLineVlanList(raw)
        if (vlanList != null) return "端口VLAN（Trunk）：放行 " + vlanList
        return "未获取到VLAN信息"
    }

    /**
     * 通用多行 VLAN 列表提取：扫描所有行，找到含 VLAN ID 列表
     * （数字、逗号、连字符）的行，并合并后续以数字开头的续行。
     */
    private fun findMultiLineVlanList(raw: String?): String? {
        if (raw == null) return null
        val lines = raw.split(Regex("\\r?\\n"))
        val segRe = Regex("(\\d{1,4}[,\\-]\\d{1,4}(?:[,\\s]+\\d{1,4}(?:[,\\-]\\d{1,4})*)*)")
        var best: String? = null
        var bestCount = 0
        for (i in lines.indices) {
            val vm = segRe.find(lines[i]) ?: continue
            val list = StringBuilder(vm.value)
            for (j in i + 1 until lines.size) {
                val t = lines[j].trim()
                if (t.isEmpty() || !t[0].isDigit()) break
                if (!Regex("[\\d,\\s\\-]+,?").matches(t)) break
                list.append(",").append(t)
            }
            val result = Regex("\\s+").replace(list.toString(), "")
            val count = result.split(",").size
            if (count > bestCount) {
                bestCount = count
                best = result
            }
        }
        return best
    }

    /** 提取 H3C 输出中 "VLAN permitted" / "VLAN Passing" 的 VLAN 列表，支持折行续接。 */
    private fun extractH3cVlanList(raw: String, keyword: String): String? {
        val m = Regex("(?im)^\\s*" + keyword + "\\s*[:：]\\s*(.*)$").find(raw) ?: return null
        val list = StringBuilder((m.groups[1]?.value ?: "").trim())
        val rest = raw.substring(m.range.last + 1)
        for (line in rest.split(Regex("\\r?\\n"))) {
            val t = line.trim()
            // 跳过空行：正则 (.*)$ 的 end() 指向换行符之前，substring 后首段为空
            if (t.isEmpty()) continue
            // 折行的续段只含数字/逗号/空格/连字符，且以数字开头
            if (t[0].isDigit() && Regex("[\\d,\\s\\-]+,?").matches(t)) {
                list.append(" ").append(t)
            } else {
                break
            }
        }
        val result = Regex("(?i)\\(default\\s+vlan\\)")
            .replace(list.toString(), "")
            .let { Regex("[,\\s]+$").replace(it, "") }
            .trim()
        if (result.isEmpty() || result.equals("none", ignoreCase = true)) return null
        return result
    }

    /**
     * 从指定位置开始扫描后续行，合并 VLAN 列表的续行。
     * 续行判断：以数字开头，且只含数字/逗号/空格/连字符。
     */
    private fun appendVlanContinuations(raw: String?, fromIndex: Int, initial: String?): String {
        if (raw == null || fromIndex >= raw.length) return initial ?: ""
        val list = StringBuilder(initial ?: "")
        val rest = raw.substring(fromIndex)
        for (line in rest.split(Regex("\\r?\\n"))) {
            val t = line.trim()
            // 跳过空行：正则 (.*)$ 的 end() 指向换行符之前，substring 后首段为空
            if (t.isEmpty()) continue
            if (!t[0].isDigit()) break
            if (!Regex("[\\d,\\s\\-]+,?").matches(t)) break
            list.append(",").append(t)
        }
        return Regex("\\s+").replace(list.toString(), "")
    }

    /** 端口光模块：显示 current diagnostic parameters 区段的 RX / TX 光功率，支持多通道。 */
    private fun refineOptical(raw: String): String {
        val section = extractCurrentSection(raw)
        val lines = section.split(Regex("\\r?\\n"))
        val rxList = mutableListOf<String>()
        val txList = mutableListOf<String>()
        val exclude = Regex("threshold|alarm|warn|default|high|low|min|max")

        // 策略1：关键字锚定——"RX power"/"TX power" 字样后紧跟的数字
        val rxRe = Regex("(?i)rx\\s*power[^:：0-9\\-]*[:：]?\\s*(-?\\d+\\.?\\d*)")
        val txRe = Regex("(?i)tx\\s*power[^:：0-9\\-]*[:：]?\\s*(-?\\d+\\.?\\d*)")
        for (line in lines) {
            if (exclude.containsMatchIn(line.lowercase())) continue
            rxRe.find(line)?.let { rxList.add(it.groupValues[1]) }
            txRe.find(line)?.let { txList.add(it.groupValues[1]) }
        }

        // 策略2：锐捷 (OK)[Cn] 标记格式（100G/40G 多通道）
        if (rxList.isEmpty() && txList.isEmpty()) {
            Regex("(-?\\d+\\.?\\d*)\\(OK\\)\\[C\\d+/").findAll(section).forEach {
                rxList.add(it.groupValues[1])
            }
            Regex("AP\\]\\s*(-?\\d+\\.?\\d*)").findAll(section).forEach {
                txList.add(it.groupValues[1])
            }
        }

        // 策略3：表头行之后的数据数字流，RX=倒数第二、TX=倒数第一
        if (rxList.isEmpty() || txList.isEmpty()) {
            var headerFound = false
            var inChannelSection = false
            val rows = mutableListOf<List<String>>()
            for (line in lines) {
                val low = line.lowercase()
                // H3C: [module] 段结束，[channel] 段开始
                if (low.contains("[channel]") || (low.contains("channel") && low.contains("bias"))) {
                    inChannelSection = true
                    headerFound = true
                    continue
                }
                if (!inChannelSection && low.contains("[module]")) {
                    headerFound = false
                    continue
                }
                if (low.contains("rx") && low.contains("power") && numbersOf(line).isEmpty()) {
                    if (exclude.containsMatchIn(low)) continue
                    headerFound = true
                    continue
                }
                if (headerFound) {
                    val clean = Regex("\\[[^\\]]*\\]?").replace(line, " ")
                    val nums = numbersOf(clean)
                    if (nums.isNotEmpty()) {
                        if (inChannelSection) {
                            if (nums.size >= 2) {
                                rows.add(listOf(nums[nums.size - 2], nums[nums.size - 1]))
                            }
                        } else {
                            rows.add(nums)
                        }
                    }
                }
            }
            if (rows.isNotEmpty()) {
                if (inChannelSection) {
                    for (row in rows) {
                        rxList.add(row[0])
                        txList.add(row[1])
                    }
                } else {
                    val last = rows[rows.size - 1]
                    if (last.size >= 2) {
                        if (rxList.isEmpty()) rxList.add(last[last.size - 2])
                        if (txList.isEmpty()) txList.add(last[last.size - 1])
                    }
                }
            }
        }

        if (rxList.isEmpty() && txList.isEmpty()) {
            return "未获取到光功率信息（可能无光模块或不支持）"
        }
        val n = maxOf(rxList.size, txList.size)
        if (n == 1) {
            return "RX 光功率：" + (rxList.firstOrNull()?.let { "$it dBm" } ?: "-") +
                "\nTX 光功率：" + (txList.firstOrNull()?.let { "$it dBm" } ?: "-")
        }
        val sb = StringBuilder("光功率（共 " + n + " 通道）：")
        for (k in 0 until n) {
            sb.append("\n通道").append(k + 1).append("：RX ")
                .append(rxList.getOrNull(k) ?: "-").append(" dBm / TX ")
                .append(txList.getOrNull(k) ?: "-").append(" dBm")
        }
        return sb.toString()
    }

    /** 提取一行中的全部数字（含负数与小数）。 */
    private fun numbersOf(line: String): List<String> =
        Regex("-?\\d+(?:\\.\\d+)?").findAll(line).map { it.value }.toList()

    /** 截取 "current diagnostic parameters" 区段文本。 */
    private fun extractCurrentSection(raw: String): String {
        val lower = raw.lowercase()
        val start = lower.indexOf("current diagnostic parameters")
        if (start < 0) return raw
        var end = raw.length
        for (kw in listOf("threshold", "alarm")) {
            val idx = lower.indexOf(kw, start + 10)
            if (idx in 0 until end) end = idx
        }
        return raw.substring(start, end)
    }

    /** 端口错误包：input/output errors 即为收/发方向错误总和。 */
    private fun refineErrors(raw: String): String {
        var input = findCount(raw, "(?i)(\\d+)\\s+input\\s+errors?\\b")
        var output = findCount(raw, "(?i)(\\d+)\\s+output\\s+errors?\\b")
        if (input < 0) input = findCount(raw, "(?i)input\\s+errors?\\s*[:：]\\s*(\\d+)")
        if (output < 0) output = findCount(raw, "(?i)output\\s+errors?\\s*[:：]\\s*(\\d+)")
        if (input >= 0 || output >= 0) {
            return "错误包总数：" + (maxOf(input, 0L) + maxOf(output, 0L))
        }
        // 华为段式输出（Input: ... 0 errors ...）无 input 前缀，按 "N errors" 求和兜底
        var total = 0L
        var found = false
        for (m in Regex("(?i)(\\d+)\\s+errors\\b").findAll(raw)) {
            val tail = raw.substring(maxOf(0, m.range.first - 40), m.range.first).lowercase()
            if (tail.contains("crc") || tail.contains("frame") || tail.contains("align") ||
                tail.contains("symbol") || tail.contains("fifo")
            ) continue
            total += m.groupValues[1].toLong()
            found = true
        }
        if (!found) return "未获取到错误包统计"
        return "错误包总数：$total"
    }

    private fun findCount(raw: String, regex: String): Long =
        Regex(regex).find(raw)?.groupValues?.get(1)?.toLong() ?: -1L

    /** 查看VLAN的IP：只显示 IP 与掩码。 */
    private fun refineVlanIp(raw: String): String {
        val m = Regex(
            "(\\d{1,3}(?:\\.\\d{1,3}){3})(?:/(\\d{1,2}))?" +
                "(?:[\\s,]+(\\d{1,3}(?:\\.\\d{1,3}){3}))?"
        ).find(raw) ?: return "该VLAN未配置IP地址"
        val ip = m.groupValues[1]
        if (ip == "0.0.0.0") return "该VLAN未配置IP地址"
        val prefix = m.groups[2]?.value
        val dotMask = m.groups[3]?.value
        val sb = StringBuilder("IP：" + ip)
        if (prefix != null) {
            sb.append("\n掩码：").append(prefixToMask(prefix)).append("（/").append(prefix).append("）")
        } else if (dotMask != null) {
            sb.append("\n掩码：").append(dotMask)
        }
        return sb.toString()
    }

    /** 前缀长度转点分掩码。 */
    private fun prefixToMask(prefix: String): String {
        val p = prefix.toIntOrNull() ?: return "/" + prefix
        if (p < 0 || p > 32) return "/" + prefix
        val mask = if (p == 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return ((mask shr 24) and 0xFF).toString() + "." + ((mask shr 16) and 0xFF) +
            "." + ((mask shr 8) and 0xFF) + "." + (mask and 0xFF)
    }

    /** 查看所有VLAN：只显示 VLAN ID 列表。 */
    private fun refineAllVlans(raw: String): String {
        val vlans = TreeSet<Int>()
        // 表格行首数字（华为 VID 表、锐捷 VLAN 表）
        extractVlansFromText(vlans, raw, "(?im)^\\s*(\\d{1,4})\\s+\\S")
        // H3C "VLAN ID: 100" 段
        extractVlansFromText(vlans, raw, "(?i)vlan\\s+id\\s*[:：]\\s*(\\d{1,4})")
        // H3C "The VLANs include: 1(default), 100, 2401-2420" 块
        val mi = Regex("(?i)vlans?\\s+include[^\\n]*\\n([\\s\\S]{0,300})").find(raw)
        if (mi != null) {
            extractVlansFromText(vlans, mi.groupValues[1], "\\d{1,4}")
        }
        if (vlans.isEmpty()) return "未获取到VLAN列表"
        val sb = StringBuilder("VLAN列表（共 " + vlans.size + " 个）：\n")
        var i = 0
        for (v in vlans) {
            sb.append(v)
            if (++i % 10 == 0) sb.append("\n") else sb.append("   ")
        }
        return sb.toString().trim()
    }

    /** 从一段文本中提取 VLAN ID（含范围展开）。 */
    private fun extractVlansFromText(set: MutableSet<Int>, text: String, numPattern: String) {
        // 第一轮：匹配范围（支持 - 和 –）
        for (m in Regex("(\\d{1,4})\\s*[-–]\\s*(\\d{1,4})").findAll(text)) {
            addVlanRange(set, m.groupValues[1], m.groupValues[2])
        }
        // 第二轮：匹配独立数字（TreeSet 自动去重）
        // numPattern 可能无捕获组，此时 groups.size == 1，用整体匹配值
        for (m in Regex(numPattern).findAll(text)) {
            val num = if (m.groups.size >= 2) m.groups[1]?.value else m.value
            if (num != null) addVlan(set, num)
        }
    }

    private fun addVlan(set: MutableSet<Int>, s: String) {
        val id = s.toIntOrNull() ?: return
        if (id in 1..4094) set.add(id)
    }

    private fun addVlanRange(set: MutableSet<Int>, startStr: String, endStr: String) {
        var start = startStr.toIntOrNull() ?: return
        var end = endStr.toIntOrNull() ?: return
        if (start > end) {
            val t = start
            start = end
            end = t
        }
        // 限制范围大小，防止误匹配导致几千个 VLAN 被展开
        if (end - start > 500 || start < 1 || end > 4094) return
        set.addAll(start..end)
    }
}
