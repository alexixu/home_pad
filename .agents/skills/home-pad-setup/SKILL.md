---
name: home-pad-setup
description: 在已配置平板本地 Home Assistant 的小米 Pad 5 Pro 上，自动检查环境、准备依赖、构建或升级 home_pad，并只读验证状态。用于安装、升级和诊断本项目的家庭面板。
---

# home_pad 安装与只读验收

以本 skill 所在 GitHub checkout 为源码与脚本入口，完成面板安装或升级，再报告真实结果。当前支持 macOS/Linux 构建主机、Android API 26+ 的 arm64 小米 Pad 5 Pro，以及平板 localhost:8123 上已有的 Home Assistant。语音服务的 LaunchAgent 部署适用于 macOS。

## 入口

在仓库根目录运行。脚本从自身位置寻找项目，不依赖原作者目录或固定设备序列号。

```sh
python3 .agents/skills/home-pad-setup/scripts/home_pad.py doctor
```

一个已授权 ADB 设备时自动选择。多个设备时，先根据用户目标确定设备，再给每条命令传 `--serial`。ADB 可以使用现有 USB 或已配对的网络连接，当前连接可用时沿用它。

## 完成安装

1. 阅读项目 `AGENTS.md`，检查用户选择的机型、已有 HA 和当前服务。已有连接或授权应复用。
2. 运行 `doctor`。工具链支持项目本地工具，或 `JAVA_HOME` 与 `ANDROID_HOME` 指向的 JDK 17、Android API 33 和 Build Tools 35.0.0。缺项时按 [部署与首次连接](references/setup.md) 准备对应工具。
3. 使用用户已有的本机私有 HA 配置文件，包含 `token` 与 `entity_id`，权限为 0600。实体须是实际存在的 `switch` 或 `light`。不要把配置内容、账号密码、签名密码或语音配对令牌写入聊天、命令参数或 Git。
4. 调用统一安装入口。它准备固定校验和的唤醒依赖、复用构建脚本生成签名 APK、核对现有安装的证书并执行覆盖安装，最后进行只读 HA 验收。

```sh
python3 .agents/skills/home-pad-setup/scripts/home_pad.py setup --config /absolute/private/tablet-config.json
```

已有签名 APK 时可跳过构建。先检查，再执行同一入口。

```sh
python3 .agents/skills/home-pad-setup/scripts/home_pad.py setup --apk outputs/极简搜索桌面.apk --config /absolute/private/tablet-config.json --check-only
python3 .agents/skills/home-pad-setup/scripts/home_pad.py setup --apk outputs/极简搜索桌面.apk --config /absolute/private/tablet-config.json
```

同一 APK 已安装时返回 `already_installed`，保留当前应用会话。首次安装后的家居连接、默认桌面与可选语音配对，按 [首次连接步骤](references/setup.md) 完成。用户明确授权这些配置时直接执行可用的本机配置操作，涉及账号登录或输入私有授权时使用用户设备上的正常流程。

## 运行边界

- 所有实机验收只做状态查询，保留设备当前供电状态。不要调用 `turn_on`、`turn_off`、`toggle` 或任何设备调节服务。脚本的 HA 网络请求固定为两个 GET。
- 检测到 `monitor.py` 采样进程时，脚本停止会修改安装的操作。保全原运行，不重置、延长或中止采样，不为继续安装收集、删除或改写日志。
- 语音会话占用时停止升级。脚本检查现有语音配对服务和应用播放状态。只读验收与相同 APK 的幂等检查可继续。
- 不重启 Termux、HA 或 Mac 上的同授权 HA 实例，不执行清数据、卸载旧包、降级、解锁或刷机。签名不匹配时恢复用户原签名材料，不能通过卸载绕过。
- 首次构建可生成本机签名，已有签名必须完整复用。签名文件不完整时停止，避免产生一套无法覆盖升级的新身份。
- 安装超时或结果不明时，先检查已安装 APK 的哈希与 HA 状态，再决定下一步。每次运行只尝试一次安装。

## 交付证据

分别报告构建、签名、实际安装、同包跳过及 HA GET 结果。`already_installed` 表示此次没有发生覆盖安装。HA 报告的状态不等同于物理功率测量。新设备、长时间稳定性、熄屏唤醒和真实通断只按各自实际证据说明。

需要仅诊断时运行 `verify --config ...`。需要仅准备依赖时运行 `prepare`。隔离编译使用 `build --check-only`，它产生未签名检查包，不安装。
