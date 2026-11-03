# 部署与首次连接

## Quick Start

适用于已配置平板本地 HA 的小米 Pad 5 Pro。首次账号授权和连接配置使用用户自己的正常流程。

1. 平板进入“设置 → 关于平板 → 详细信息与规格”，连续点击系统版本或 MIUI 版本 7 次，出现开发者提示。
2. 进入“更多设置 / 其他设置 → 开发者选项”，打开“USB 调试”。用数据线连接电脑，在平板弹窗中授权自己的电脑。系统菜单名称可能因版本不同有所变化。[小米官方说明](https://www.mi.com/global/support/faq/details/KA-168765/)、[Android 设备连接说明](https://developer.android.com/studio/run/device)
3. 使用有权限的 GitHub 登录取得本项目，在 Codex 中打开项目，在对话中运行以下提示。

```text
$home-pad-setup 检查已连接的小米平板，安装或升级 home_pad，只查询设备状态，保持现有供电状态。
```

skill 先检查 ADB 与工具链，再按下方前提完成准备和安装。已有授权连接直接沿用，无需为了复查反复插拔。首次账号登录和私有配置由用户在本机完成。

## 取得项目

源码、skill 和脚本在同一个仓库。使用有访问权限的正常 GitHub 登录克隆，然后在该 checkout 启动 Codex。

```sh
git clone https://github.com/alexixu/home_pad.git
cd home_pad
```

项目内 `.agents/skills/home-pad-setup` 会作为项目 skill 被发现。用户可以直接输入 `$home-pad-setup`，也可以明确要求按该目录中的 `SKILL.md` 完成安装。使用 checkout 中的 skill，让执行脚本与项目源码保持在同一个版本。

## 准备主机与平板

- Python 3.9+，JDK 17，Android Platform 33、Build Tools 35.0.0，以及已授权的 ADB。
- 标准 SDK 可通过 `JAVA_HOME`、`ANDROID_HOME` 指定。项目既有的 `work/minimal-launcher/tools/` 解包目录继续可用。
- JDK 与 Android SDK 缺失时，从官方发行方取得工具。按 Android 官方工具的当前流程安装 `platforms;android-33`、`build-tools;35.0.0` 和 `platform-tools`。涉及用户接受许可时保留正常交互。[Android SDK 工具说明](https://developer.android.com/tools/sdkmanager)
- 只连接或选择用户指定的平板。现有网络 ADB 和 USB ADB 都可用，USB 供电可以保留。

## Home Assistant 首次连接

本 skill 面向已经在平板本地运行、完成用户登录和设备授权的 HA。`Termux/proot` 是本项目维护的部署方式，兼容范围以项目实测为准。新设备须先完成 Termux、HA 和设备集成的初始设置，不能从 GitHub 获取作者的私有授权来恢复环境。

用于只读验收的私有 JSON 文件包含用户自己的 `token` 和 `entity_id`。将它放在用户自有、排除 Git 的私有目录中，权限设为 0600。不要生成带真实授权的示例文件或把内容打印给模型。

首次安装后从控制中心进入“打开设置”→“家居连接设置”，使用本机 HA 地址 `http://127.0.0.1:8123`，填写用户自己的长期访问令牌和实际设备实体。保存后只读取状态。面板使用 Android Keystore 加密保存令牌。已有安装保留其原配置，无需重新输入。

设备目录使用本项目已有的私有目录导入机制，须与该面板实际 HA 连接匹配。其他机型、其他平台 HA、自动导入全量云设备或从零部署 HA，不包含在此安装脚本的自动化承诺中。

## 可选 ChatGPT 语音

语音服务复用 `work/voice-service/configure.py`、`install_service.py` 和该目录的 `README.md`。需要时再阅读该说明。当前已验证路径是正常 Codex 登录的 app-server V3 WebRTC，Mac 管理语音会话，平板音频直连 OpenAI，基础面板独立于语音服务运行。

首次部署时，按用户实际网络地址生成本地 TLS 与配对文件，再安装当前用户的 LaunchAgent。不要覆盖已有配对身份。首次登录由用户使用官方 Codex 完成，既有登录直接复用。配对从控制中心的“ChatGPT 语音连接”进行，私有文件保留在本机，保持证书和主机名校验。

升级面板时脚本会检测项目现有的 `work/voice-service/private/tablet-voice-setup.json`。其他部署位置通过 `--voice-pairing /absolute/private/tablet-voice-setup.json` 指定，该文件旁需要原 `tls-cert.pem`。服务健康不可核验或会话仍占用时，保留当前运行并处理具体原因。

## 单独运行步骤

```sh
python3 .agents/skills/home-pad-setup/scripts/home_pad.py prepare
python3 .agents/skills/home-pad-setup/scripts/home_pad.py build --check-only
python3 .agents/skills/home-pad-setup/scripts/home_pad.py build
python3 .agents/skills/home-pad-setup/scripts/home_pad.py install --config /absolute/private/tablet-config.json
python3 .agents/skills/home-pad-setup/scripts/home_pad.py verify --config /absolute/private/tablet-config.json
```

安装校验证书使用 Android 官方 apksigner。出现签名不同或降级条件时，恢复与当前安装匹配的源码和本地签名材料，然后重新检查。[apksigner 官方说明](https://developer.android.com/tools/apksigner)
