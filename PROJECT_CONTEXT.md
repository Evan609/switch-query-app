# PROJECT_CONTEXT.md — 交换机信息查询APP

> **本文件是任何新会话的第一入口。每次发版或架构变更后更新。**
> 版本历史详见 `开发者更新文档.md`。

---

## 一、项目是什么

一个 Android APP + Python 授权服务端的组合：
- **APP**（`app/`）：通过 SSH 跳板 → telnet 到内网交换机，执行只读查询命令，解析结果展示
- **服务端**（`server/app.py`）：授权码管理、APP 版本发布、公告下发、APK 下载
- **支持厂商**：H3C、华为、锐捷（三种适配器在 `switchadapter/` 目录）

## 二、关键约束（必须遵守）

| 约束 | 原因 | 验证方式 |
|---|---|---|
| **APP 只能发 display/show 只读命令** | 用户明确要求不能改交换机配置 | grep `sendLine\|write(` 确认无 `system-view`/`configure`/`undo` 等 |
| **不能发 screen-length/terminal length** | 用户明确禁止 | 同上，这两个命令已从 `disablePaging()` 中移除 |
| 分页由 reader 线程自动处理 | 检测 `--More--` 自动发空格 | `SshShellSession.java` reader 线程 |
| **不自动连接服务器** | 用户用 FTP 上传 + 服务器本地操作 | 直接给文件路径和手动命令 |

## 三、开发环境

| 依赖 | 版本 | 路径/验证 |
|---|---|---|
| JDK | **17.0.2（必须）** | `C:\Program Files\Java\jdk-17.0.2` |
| Android SDK | 34 | 见 `local.properties`（换电脑需修改） |
| Python | 3.7+ | 服务端开发 |
| Gradle | 8.9（wrapper 自带） | 不需要全局安装，缓存在 `C:\Users\CATORA\.gradle\wrapper\dists\gradle-8.9-bin\` |
| Git | 任意 | 仓库已推 GitHub |

> ⚠️ **机器上装了 JDK 25（`C:\Program Files\Java\jdk-25.0.5.0.3`）且已是 PATH 默认 JDK。**
> 本项目**禁止用 JDK 25 编译**：实测 `assembleRelease` 会在 `lintVitalAnalyzeRelease` 阶段失败
> （`AndroidLintWorkAction > 25.0.5.0.3`），AGP 8.7.3 的 Lint 引擎无法解析该版本串。
> 因此**每条编译命令都必须在前面显式设置 `JAVA_HOME` 指向 jdk-17.0.2**（见第四节）。

## 四、构建与发布流程

> 🔴 **硬性要求：前两行设置 JAVA_HOME 不能省，也不能依赖系统默认 JDK。**
> 系统默认已是 JDK 25，直接 `gradlew` 编译 release 必失败。

```powershell
# 1. 编译（release 签名版）
#    ← 这两行必须每次执行，且必须在 gradlew 之前
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.2"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleRelease

# 2. 复制 APK
Copy-Item app\build\outputs\apk\release\app-release.apk 网管家交换机信息查询.apk -Force

# 3. 算 SHA256（用于服务端发布时填入）
(Get-FileHash "网管家交换机信息查询.apk" -Algorithm SHA256).Hash

# 4. 验证签名（sdk 路径见 local.properties）
& "F:\Android\SDK\build-tools\36.0.0\apksigner.bat" verify --print-certs "网管家交换机信息查询.apk"

# 5. 更新版本号：app/build.gradle 里 versionCode 和 versionName 各 +1
# 6. 更新本文件和 开发者更新文档.md
# 7. git add → commit → push
```

### 编译报错对照

| 报错 | 原因 | 处理 |
|---|---|---|
| `AndroidLintWorkAction > 25.0.5.0.3` | 用了系统默认 JDK 25 | 按上面设置 `JAVA_HOME` 为 jdk-17.0.2 后重试 |
| `Unsupported class file major version 69` | Gradle 跑在 JDK 25 上 | 同上 |
| `app-release-unsigned.apk` | 缺 `keystore.properties` 或 `.jks` | 从离线备份拷贝两个签名文件 |

### 签名密钥（严禁入库）

| 文件 | 位置 | 是否入库 |
|---|---|---|
| `keystore/switchquery-release.jks` | 项目内 | ❌ `.gitignore` 已排除 `*.jks`/`*.keystore` |
| `keystore.properties` | 项目根目录 | ❌ `.gitignore` 已排除 |

- alias：`switchquery`，RSA 2048，有效期 10950 天（约 30 年）
- 证书：`CN=Evan609, OU=SwitchQuery, O=WGJ, L=Beijing, ST=Beijing, C=CN`
- **换电脑/新克隆仓库必须单独拷贝这两个文件，否则无法出签名包**
- **务必备份到离线安全位置**：`.jks` 丢失后无法再发布同签名的更新（只能换包名重装）

## 五、服务端部署（服务器本地执行）

```bash
# 上传 app.py 后
systemctl restart switch-auth
systemctl status switch-auth

# 配置文件：/opt/switch-auth/config.json（从 config.example.json 复制）
# 服务文件：server/switch-auth.service（systemd）
# 备份脚本：server/backup.sh（自动备份 SQLite 数据库 + APK + 配置）
```

## 六、服务器信息

| 项 | 值 |
|---|---|
| IP | `39.107.26.67`（Rocky Linux 8，阿里云 ECS） |
| 服务 | `switch-auth.service`（HTTPS 8443） |
| 代码路径 | `/opt/switch-auth/app.py` |
| 数据路径 | `/opt/switch-auth/`（含 SQLite、APK、配置） |
| 访问方式 | FTP 上传文件 + SSH 到服务器本地执行命令 |
| GitHub | https://github.com/Evan609/switch-query-app |

## 七、APP 发送的全部命令（无配置类）

| 类别 | 命令 |
|---|---|
| 查询 | `display/show version`、`display/show vlan`、`display/show interface <port>`、`display/show interface brief`、`display/show interfaces description`、`display/show interfaces <port> switchport`、`display/show port vlan <port>`、`display/show transceiver...`、`display/show current-configuration`、`display/show running-config` |
| 诊断 | `ping <ip>` |
| 连接 | `telnet <ip>`、用户名、密码 |
| 退出 | `quit`/`exit`、`Ctrl+]`(\u001d)、`Ctrl+C`(\u0003) |
| 翻页 | 空格（reader 线程检测 `--More--` 自动发） |

## 八、核心文件索引

| 文件 | 作用 | 改了容易出问题 |
|---|---|---|
| `app/src/.../connection/ConnectionManager.java` | SSH 会话管理、命令发送 | ⚠️ 命令白名单在此 |
| `app/src/.../connection/SshShellSession.java` | PTY、reader 线程、`--More--` 自动翻页、prompt 检测 | ⚠️ 连接稳定性核心 |
| `app/src/.../ui/ResultRefiner.java` | 原始输出 → 结构化结果解析 | ⚠️ 每次修 bug 高频 |
| `app/src/.../switchadapter/*Adapter.java` | 厂商差异处理 | |
| `app/src/.../auth/remote/*` | 授权 + 版本更新模块 | |
| `server/app.py` | 服务端全部逻辑（HTTP + SQLite） | ⚠️ 部署后需重启 |
| `server/config.example.json` | 服务端配置模板 | |

## 九、当前状态（v1.8.0）

- ✅ APP 当前发布版：v1.8.0（versionCode 36）— release keystore 签名
- ✅ v1.8.0 相对 v1.7.9 仅版本号变更，代码完全一致
- ✅ 锐捷/华为 VLAN 多行续行解析已修复
- ✅ `--More--` 与提示符同行时检测已修复
- ✅ 服务端 NOT_FOUND 容错已加（末尾斜杠、favicon、路径回显）
- ⏳ 等待用户 FTP 上传 app.py 到服务器
- ✅ 服务端已加「登录设备」记录（device_log 表 + 管理页卡片），APP 无需改动，待部署
- ⚠️ 从 v1.7.8 及更早（debug 签名）升级的用户必须先卸载旧版；v1.7.9 起可直接覆盖升级

## 十、用户偏好（重要！）

- **直接给文件和手动命令**，不要自动连接服务器（沙箱限制 + 用户方式不同）
- **默认一律用 release 包**：编译用 `.\gradlew.bat assembleRelease`，产物取 `app/build/outputs/apk/release/app-release.apk`；不再默认用 debug 包（除非用户明确要求调试）
- 每次发版必须：递增 versionCode/Name → 更新本文件 → 更新开发者文档 → 给 APK 链接 → git push
- 部署用 FTP 上传 + 服务器本地 `systemctl restart switch-auth`

## 十一、待办（TODO）

- [ ] 服务端 app.py 修复部署（用户 FTP 上传中）
- [x] release keystore 签名（v1.7.9 起生效）
- [ ] 把 `keystore/switchquery-release.jks` + `keystore.properties` 备份到离线安全位置（网盘/U盘/密码管理器）

## 十二、技术选型决策

### 2026-10-30 决定：APP 保持纯 Java，不引入 Kotlin

**结论**：继续用 Java 17，不加 Kotlin 插件、不加 stdlib、不迁移现有代码。

**理由**
- 代码已稳定（33 文件 / 4,911 行），且解析与连接逻辑都要靠真实交换机验证，全量改写回归成本高
- Kotlin stdlib 在 `minifyEnabled false` 下无法裁剪，APK 会从 4.92 MB 涨到 6.5 MB 左右，抵消刚完成的体积优化
- Java 17 已有 record / switch 表达式 / var / 文本块，与 Kotlin 的差距在空安全和协程，而这两项对本项目收益有限

**重新评估的条件**（满足两条以上再谈）
1. 要重写连接层或加批量并发查询、多协议（SNMP 等）
2. 代码量翻倍到 1 万行以上
3. 团队引入新成员且更熟 Kotlin
4. 体积不再敏感（如仅企业内部分发）

### 2026-10-30 附带产物：独立 Kotlin 预览工程（不参与 APK）

目录 `kotlin-standalone/`：自有 `settings.gradle.kts` + `build.gradle.kts`（kotlin("jvm") 2.1.0、
jvmToolchain 17），把解析层 `ResultRefiner` 与四个数据模型 1:1 重写为 Kotlin，用于评估迁移成本。

- **零耦合**：根 `settings.gradle` 仍只有 `include ':app'`，`app/` 下无任何 `.kt`；本目录不被根工程引用，
  只能在 `.\gradlew.bat -p kotlin-standalone ...` 下单独构建，不影响 `assembleRelease` 的体积与签名。
- **等价性已实测**：12 份真实厂商回显样例（覆盖 6 类解析）经 Java 原版与 Kotlin 版各跑一遍，
  输出文件 SHA256 完全一致（`C8CA9345…96428`）。
- 运行方式与移植注意点见 `kotlin-standalone/README.md`（含 `run` / `demo` / `parity` 三种模式）。
- 结论未变：主项目继续纯 Java，本目录仅作参考实现；若将来决定迁移，可直接以此为起点。

### 2026-10-30 决定：构建必须用 JDK 17

本机 PATH 默认已是 JDK 25，用 25 编译 release 会在 `lintVitalAnalyzeRelease` 失败
（`AndroidLintWorkAction > 25.0.5.0.3`）。因此所有编译命令必须以设置 `JAVA_HOME` 开头（见第四节）。
JDK / Gradle / AGP 升级评估结论：收益为零、成本为一次构建系统迁移，暂不升级。
