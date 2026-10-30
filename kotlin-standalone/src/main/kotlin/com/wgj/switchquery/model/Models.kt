package com.wgj.switchquery.model

/** 交换机厂商/型号类型。对应 Java 版 com.wgj.switchquery.model.SwitchType。 */
enum class SwitchType(val label: String) {
    HUAWEI("华为"),
    H3C("H3C"),
    RUIJIE("锐捷"),
    UNKNOWN("未知"),
}

/** 交换机设备（核心或节点）。对应 Java 版 SwitchDevice。 */
data class SwitchDevice(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val username: String,
    val password: String,
    val core: Boolean = false,
)

/** 网络项目：一个核心交换机 + 若干节点交换机。对应 Java 版 Project。 */
data class Project(
    val id: String,
    val name: String,
    val core: SwitchDevice? = null,
    val nodes: List<SwitchDevice> = emptyList(),
) {
    fun withNode(node: SwitchDevice?): Project =
        if (node == null) this else copy(nodes = nodes + node)
}

/**
 * 端口描述符：rawName 用于命令行，displayName 用于界面展示。
 * 对应 Java 版 com.wgj.switchquery.model.PortDescriptor。
 */
class PortDescriptor(
    @JvmField val rawName: String,
    @JvmField val prefixType: String,
    @JvmField val prefixAbbr: String,
    @JvmField val portNumber: String,
    description: String?,
    status: String?,
    @JvmField val adminStatus: String?,
) {
    @JvmField
    var description: String? = description

    @JvmField
    var status: String = status ?: "unknown"

    @JvmField
    var displayName: String =
        if (!description.isNullOrEmpty()) "[$prefixAbbr] $description"
        else "[$prefixAbbr] $portNumber"

    /** 状态颜色枚举（给界面用）。 */
    enum class StatusColor { GREEN, DEFAULT, RED }

    fun statusColor(): StatusColor = when {
        status.equals("up", ignoreCase = true) -> StatusColor.GREEN
        status.equals("down", ignoreCase = true) -> StatusColor.DEFAULT
        else -> StatusColor.RED
    }
}
