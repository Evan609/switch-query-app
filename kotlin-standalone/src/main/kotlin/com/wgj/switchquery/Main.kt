package com.wgj.switchquery

import com.wgj.switchquery.refiner.ResultRefiner
import java.io.File
import kotlin.system.exitProcess

/**
 * 三种运行模式：
 *   .\gradlew run                 → 自检（与 Java 版输出对齐的用例）
 *   .\gradlew run --args="demo"   → 打印示例解析结果
 *   .\gradlew run --args="parity samples out.txt" → 输出逐字节对比文本（供与 Java 版 diff）
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "parity" -> parityDump(File(args.getOrNull(1) ?: "samples"), args.getOrNull(2))
        "demo" -> demo()
        else -> selfTest()
    }
}

private fun escape(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\r", "\\r")
    .replace("\n", "\\n")
    .replace("\t", "\\t")

private fun parityDump(dir: File, outFile: String?) {
    val files = dir.listFiles { f -> f.name.endsWith(".txt") }?.sortedBy { it.name }
    if (files == null || files.isEmpty()) {
        println("目录下没有 .txt 样例：${dir.absolutePath}")
        exitProcess(2)
    }
    val sb = StringBuilder()
    for (f in files) {
        val name = f.name.removeSuffix(".txt")
        val type = if (name.contains("__")) name.substringBefore("__") else "unknown"
        val raw = f.readText()
        sb.append(name).append('|').append(type).append('|')
            .append(escape(ResultRefiner.refine(type, raw))).append('\n')
    }
    if (outFile != null) {
        File(outFile).writeText(sb.toString())
        println("wrote $outFile (${files.size} cases)")
    } else {
        print(sb)
    }
}

private fun demo() {
    val ruijie = """
        Interface                Switchport  Mode    Access  Native  Protected  VLAN lists
        AggregatePort 20          enabled     TRUNK
        1      1      Disabled  20,23,120-139
        2001-2020,2301-2320
        2395
        JiLin_HeNanJie_JieDian#
    """.trimIndent()
    println("=== 锐捷 show interfaces AggregatePort 20 switchport ===")
    println(ResultRefiner.refine("port_vlan", ruijie))

    val huawei = """
        Port                  Link Type    PVID  Trunk VLAN List
        -----------------------------------------------------------------------------
        GigabitEthernet0/0/1  access       100   -
    """.trimIndent()
    println("\n=== 华为 display port vlan GigabitEthernet0/0/1 ===")
    println(ResultRefiner.refine("port_vlan", huawei))
}

private fun selfTest() {
    var failures = 0

    fun check(name: String, type: String, raw: String, expected: String) {
        val actual = ResultRefiner.refine(type, raw)
        if (actual == expected) {
            println("PASS  $name")
        } else {
            failures++
            println("FAIL  $name")
            println("  期望: " + escape(expected))
            println("  实际: " + escape(actual))
        }
    }

    val ruijieTrunk = """
        Interface                Switchport  Mode    Access  Native  Protected  VLAN lists
        AggregatePort 20          enabled     TRUNK
        1      1      Disabled  20,23,120-139
        2001-2020,2301-2320
        2395
        JiLin_HeNanJie_JieDian#
    """.trimIndent()

    check(
        "锐捷 Trunk 多行 VLAN 列表合并", "port_vlan", ruijieTrunk,
        "端口VLAN（Trunk）：Native 1  放行 20,23,120-139,2001-2020,2301-2320,2395",
    )

    val huaweiAccess = """
        Port                  Link Type    PVID  Trunk VLAN List
        -----------------------------------------------------------------------------
        GigabitEthernet0/0/1  access       100   -
    """.trimIndent()
    check("华为 Access 端口", "port_vlan", huaweiAccess, "端口VLAN：100")

    check(
        "无 VLAN 信息", "port_vlan",
        "GigabitEthernet0/0/1\nCurrent state: UP\nLine protocol state: UP",
        "未获取到VLAN信息",
    )

    check("端口 UP", "port_up", "Current state: UP\nLine protocol state: UP", "端口状态：UP")
    check(
        "管理性关闭", "port_up",
        "GigabitEthernet1/0/1 is administratively down, line protocol is down",
        "端口状态：DOWN（管理性关闭）",
    )

    check("错误包求和", "port_errors", "5 input errors, 3 output errors", "错误包总数：8")
    check(
        "VLAN IP 与掩码", "vlan_ip",
        "Vlanif100\n  IP address: 10.1.1.1/24",
        "IP：10.1.1.1\n掩码：255.255.255.0（/24）",
    )
    check(
        "光功率单通道", "port_optical",
        "Current Diagnostic Parameters\n" +
            "Temperature(C)  Voltage(V)  Bias(mA)  RX power(dBm)  TX power(dBm)\n" +
            "30              3.29        5.50      -5.20          -3.10",
        "RX 光功率：-5.20 dBm\nTX 光功率：-3.10 dBm",
    )
    check("空输出", "port_vlan", "   ", "（无返回内容）")

    println()
    if (failures == 0) {
        println("全部用例通过")
    } else {
        println("$failures 项用例失败")
        exitProcess(1)
    }
}
