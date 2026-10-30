# Kotlin 独立预览工程（switchquery-kotlin-preview）

把现有 Android 项目里**纯逻辑层**（交换机输出解析 + 数据模型）用 Kotlin 重写一份，
**独立构建、独立运行、与 Android 工程零耦合**，用于评估 Kotlin 迁移成本与行为一致性。

## 与主项目的关系

| | 主项目 | 本工程 |
|---|---|---|
| 根目录 | `f:\TRAE_Work\交换机信息查询APP` | `kotlin-standalone\` |
| 构建脚本 | 根 `settings.gradle` + `app/build.gradle`（AGP 8.7.3） | 自有 `settings.gradle.kts` + `build.gradle.kts`（Kotlin JVM 2.1.0） |
| 语言 | Java 17 | Kotlin 2.1.0（`jvmToolchain(17)`） |
| 产物 | APK | 纯 JVM jar / 直接 `run` |

**互不干扰的证据**：

- 根 `settings.gradle` 只有 `include ':app'`，**没有**引入本目录，也没有任何 Kotlin 插件依赖；
- 本目录有自己的 `settings.gradle.kts`，Gradle 用 `-p kotlin-standalone` 单独构建；
- 本工程不依赖 Android SDK，`/gradlew assembleRelease` 的产物、体积、签名都不受影响；
- 两套代码互不引用：Kotlin 侧只是「照着 Java 版重写」，Java 侧一行没改。

## 目录结构

```
kotlin-standalone/
├── settings.gradle.kts            # rootProject.name = switchquery-kotlin-preview
├── build.gradle.kts               # kotlin("jvm") 2.1.0 + application + jvmToolchain(17)
├── src/main/kotlin/com/wgj/switchquery/
│   ├── Main.kt                    # 入口：自检 / demo / parity
│   ├── model/Models.kt            # SwitchType / SwitchDevice / Project / PortDescriptor
│   └── refiner/ResultRefiner.kt   # 解析核心（对应 Java 版 ResultRefiner）
├── tools/ParityDump.java          # 对比基准：编译 Java 原版，输出同样格式的文本
├── samples/*.txt                  # 12 份真实厂商回显样例（UTF-8 无 BOM）
└── 架构与代码注释.md               # 整体框架 + 每个文件的职责 + 逐函数说明
```

> 想先看代码结构，直接读 [`架构与代码注释.md`](架构与代码注释.md)；只想跑起来，看下文。


## 运行方式

**必须先指定 JDK 17**（PATH 上的默认 JDK 若为 25，Gradle 8.9 / Kotlin 2.1 都可能报错）：

```powershell
cd f:\TRAE_Work\交换机信息查询APP
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.2"
```

### 1. 自检（9 个用例，覆盖 6 类解析）

```powershell
.\gradlew.bat -p kotlin-standalone run
```

期望输出：9 行 `PASS` + `全部用例通过`。
若中文显示为乱码，说明终端代码页不是 UTF-8：换用 Windows Terminal，或先执行 `chcp 65001`
（程序本身固定按 UTF-8 输出，写文件也不受代码页影响）。

### 2. 示例输出

```powershell
.\gradlew.bat -p kotlin-standalone run --args="demo"
```

### 3. 与 Java 原版逐字节对比

```powershell
# Kotlin 侧
.\gradlew.bat -p kotlin-standalone run --args="parity samples kotlin_out.txt"

# Java 侧（编译原版 ResultRefiner + 对比工具）
New-Item -ItemType Directory -Force kotlin-standalone\build-parity\java | Out-Null
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -sourcepath app\src\main\java `
    -d kotlin-standalone\build-parity\java kotlin-standalone\tools\ParityDump.java
& "$env:JAVA_HOME\bin\java.exe" -cp kotlin-standalone\build-parity\java `
    ParityDump kotlin-standalone\samples kotlin-standalone\java_out.txt

# 对比（相同即无差异）
(Get-FileHash kotlin-standalone\java_out.txt).Hash
(Get-FileHash kotlin-standalone\kotlin_out.txt).Hash
```

本机实测结果：**12/12 样例输出完全相同，SHA256 一致**（`C8CA9345…96428`）。

## 覆盖范围

| 功能类型 | Java 原版 | Kotlin 移植 | 说明 |
|---|---|---|---|
| `port_up` | ✅ | ✅ | UP / DOWN / 管理性关闭 |
| `port_vlan` | ✅ | ✅ | 华为表格、H3C PVID+permitted、锐捷表格式、行式 Native/Allowed、多行折行合并 |
| `port_optical` | ✅ | ✅ | 单通道 / 锐捷 100G 多通道 |
| `port_errors` | ✅ | ✅ | input/output errors 求和 |
| `vlan_ip` | ✅ | ✅ | IP + 斜杠掩码 / 点分掩码 |
| `all_vlans` | ✅ | ✅ | VLAN 列表去重排序、范围展开 |
| 数据模型 | ✅ | ✅ | SwitchType / SwitchDevice / Project / PortDescriptor |

**未移植**（属于 Android 层，与解析逻辑无关）：Activity/View 界面、SSH/Telnet 连接、数据持久化、登录鉴权、服务端交互。

## 移植时踩到的 Kotlin 语义差异

1. **`Matcher.group(n)` 返回 null vs `groupValues[n]` 返回 `""`**
   Java 里未参与匹配的分组是 `null`；Kotlin 的 `groupValues[n]` 会给空串。
   移植时统一改成 `m.groups[n]?.value`，避免「空串被当成有效值」。
2. **`m.end()` → `m.range.last + 1`**
   续行合并依赖匹配结束位置，索引差一位会导致 VLAN 列表断裂。
3. **Kotlin 会告警「条件恒为 false」**
   Java 版 `if (pvid != null)` 在兜底分支里本就不可能成立（前面已 return），
   Kotlin 编译期就能识别，已改成等效写法并保留注释。
4. **字符串模板与 `String.format` 的本地化差异**
   IP 掩码拼接统一用字符串拼接，避免 `%s` 与默认 Locale 带来的格式差异。

## 结论

Kotlin 版在纯逻辑层与 Java 版**输出完全一致**，语言本身的差异集中在少量正则/空安全细节。
是否把主项目整体迁移到 Kotlin，仍按 `PROJECT_CONTEXT.md`「十二、技术选型决策」执行：
**当前保持纯 Java**，本目录只作为独立参考实现，不参与 APK 构建。
