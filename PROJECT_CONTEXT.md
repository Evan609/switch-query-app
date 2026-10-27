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
| JDK | 17.0.2 | `C:\Program Files\Java\jdk-17.0.2` |
| Android SDK | 34 | 见 `local.properties`（换电脑需修改） |
| Python | 3.7+ | 服务端开发 |
| Gradle | 8.9（wrapper 自带） | 不需要全局安装 |
| Git | 任意 | 仓库已推 GitHub |

## 四、构建与发布流程

```powershell
# 1. 编译（自动用 gradle wrapper）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.2"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew assembleDebug

# 2. 复制 APK
Copy-Item app\build\outputs\apk\debug\app-debug.apk 网管家交换机信息查询.apk -Force

# 3. 算 SHA256（用于服务端发布时填入）
(Get-FileHash "网管家交换机信息查询.apk" -Algorithm SHA256).Hash

# 4. 更新版本号：app/build.gradle 里 versionCode 和 versionName 各 +1
# 5. 更新本文件和 开发者更新文档.md
# 6. git add → commit → push
```

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

## 九、当前状态（v1.7.8）

- ✅ APP 当前发布版：v1.7.8（versionCode 34）
- ✅ 锐捷/华为 VLAN 多行续行解析已修复
- ✅ `--More--` 与提示符同行时检测已修复
- ✅ 服务端 NOT_FOUND 容错已加（末尾斜杠、favicon、路径回显）
- ⏳ 等待用户 FTP 上传 app.py 到服务器

## 十、用户偏好（重要！）

- **直接给文件和手动命令**，不要自动连接服务器（沙箱限制 + 用户方式不同）
- 每次发版必须：递增 versionCode/Name → 更新本文件 → 更新开发者文档 → 给 APK 链接 → git push
- 部署用 FTP 上传 + 服务器本地 `systemctl restart switch-auth`

## 十一、待办（TODO）

- [ ] 服务端 app.py 修复部署（用户 FTP 上传中）
- [ ] 未来考虑：release keystore 签名（目前是 debug 签名）
