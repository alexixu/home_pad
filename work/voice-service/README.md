# 平板 ChatGPT 语音服务

2.1 默认使用官方 Codex CLI 的 app-server V3 WebRTC 和当前用户正常的 ChatGPT 登录，**不需要 OpenAI API key**。平板与 OpenAI 直接传输语音；本服务负责建立会话和转交有限的家居工具请求。Home Assistant（HA）查询与动作仍由平板原生端处理，HA 令牌不进入本服务或模型。

实测 CLI 版本为 `0.165.0-alpha.1.2`。2.1 已在平板完成“室内温度是多少”与“你好，小爱”唤醒后的插座状态查询，详见 `outputs/Codex语音实机验证.json`。这是实验性 app-server 接口的有界验证，不保证其他 CLI 版本、账号或未来服务版本行为一致。先前 WebSocket 的 API key 报错不能推广到已验证的 WebRTC 路径。

## 准备和运行

需要 Python 3.9+、OpenSSL、已正常登录 ChatGPT 的官方 Codex CLI，以及平板能访问的 HTTPS 局域网地址。当前安装器使用 ChatGPT.app 内的官方 CLI，不读取或复制其登录文件，也不修改全局 Codex 配置。不要把账号凭据或 token 写入命令行。

以下命令在仓库根目录运行，用于首次创建身份；地址须替换为当前 Mac 在局域网中的地址：

```sh
python3 work/voice-service/configure.py --endpoint https://192.168.1.10:18443
python3 work/voice-service/install_service.py --check-only
python3 work/voice-service/install_service.py install
python3 work/voice-service/install_service.py status
```

准备脚本只在忽略上传的 `private/` 下创建证书、配对 token、配置与 `tablet-voice-setup.json`；默认只读，不启动服务、不联系 OpenAI、不覆盖已有身份。已有身份时保留证书和配对信息，只更新必要配置后重新运行安装器。原生配对入口为“控制中心 → 打开设置 → ChatGPT 语音连接”；平板使用 Android Keystore 加密保存配对凭据。配对文件只用于本地导入，不能上传或贴入聊天；导入后删除平板上的临时文件。

安装器只管理当前用户的 `com.alex.homepad.voice` LaunchAgent，提供 RunAtLoad、KeepAlive 和 15 秒重启节流；会等待旧 label 真正卸载才重新安装。服务与日志文件仅当前用户可读，plist 不含凭据。停止语音服务：

```sh
python3 work/voice-service/install_service.py stop
```

也可前台调试（先停止自己的 LaunchAgent，避免端口冲突）：

```sh
python3 work/voice-service/server.py --config work/voice-service/private/config.json --host 0.0.0.0 --port 18443
```

当前部署需要 Mac 用户保持登录、Mac 未休眠、网络可用。已验证只终止本语音进程后由 launchd 自动恢复，未重新测试 Mac 重启、登出或休眠恢复。**平板本地 HA 与插座触控链路仍独立于 Mac、USB 和此语音服务。** 不要为恢复语音而重启 HA 或稳定性记录器。

## 会话与权限

当前安装器和 HTTP 服务只支持 IPv4 监听；IPv6 参数被明确拒绝。非本机监听必须使用 TLS。原生端同时验证主机名、证书有效期与配对的完整 DER 证书 SHA256；不接受重定向。所有接口要求设备 Bearer token，非空 `Origin` 被拒绝，不提供浏览器跨域访问。

| 接口 | 用途 |
| --- | --- |
| `GET /health` | 已鉴权的配置状态和有限会话计数；`configured` 仅代表 CLI 可找到，不证明账号可连接语音 |
| `POST /v1/voice/sessions` | `{offer_sdp, read_only, nonce}` 创建会话，返回 `{sessionId, answerSdp, protocol:"live", backend:"codex", readOnly}` |
| `GET /v1/voice/sessions/{id}/events` | 原生端最多等待 5 秒，获取有限的工具事件 |
| `POST /v1/voice/sessions/{id}/results` | `{call_id, result}` 返回对应会话、尚未过期的工具结果 |
| `POST /v1/voice/sessions/{id}/close` | 幂等关闭本会话及其 Codex 子进程 |

客户端必须等待 WebRTC 连接、数据通道和真实 `session.started` 全部就绪才显示已连接。Codex 工具由原生端轮询处理，页面不另外执行数据通道中的工具请求。退出、进入后台或错误会取消待处理动作、停止采音并关闭会话；服务对客户端失联设 60 秒空闲回收、1 小时绝对上限。最多同时存在 2 个正在创建或已建立的会话，默认每分钟最多创建 3 次。

超时不能自动换新 nonce 重试。相同 nonce 与相同请求在服务进程内复用原结果；内容冲突被拒绝，关闭后返回已结束，不重新创建。历史保留至该服务进程结束，最多 4096 条，满后明确拒绝并要求重启。工具结果必须匹配本会话尚未完成的调用，超过 30 秒拒绝；只允许有界的家居状态字段，不转交 HA 实体 ID、令牌或任意文本载荷。

2.2 的 Codex 路径在两种模式下都注册 `get_home_status` 与 `get_home_devices`。只有私有服务配置 `allow_control=true` 且原生配对设置取消“仅查询状态，禁用语音设备控制”时，才允许 `control_home_device` 和兼容的 `set_device_power`。前者只接受目录中的不透明目标引用、目录版本、固定动作、类型化值及原话；后者仍只接受固定烤箱插座的开/关。正常模式的明确指令无需二次确认；原生端独立核对真实原话及实际设备能力，模型不能自己授权动作。触控操作保留确认。

设备目录和动作结果有严格字段白名单，不转发原始实体 ID、地址或凭据。目录最多包含 64 台设备、单台 64 个实体、总计 256 个实体及 128 KiB，保留真实总数与截断标记；查询设备或功能可获得更具体的子集。只有 `/results` 的 HTTP 请求上限扩至 256 KiB，其他请求仍为 64 KiB。全量导入进度见项目 README，不能把工具注册成功当作全部设备已接入。

当前转录没有可作为授权依据的最终用户轮次标记，因此保守保留整个会话输入，最多接受一次电源意图。否定、混合查询、修正、无效片段或不匹配请求被拒绝，可能需要重新进入语音再说完整指令。已经发出的操作无法被稍后的纠正撤回。**真实电源通断没有测试；所有实机验证只查询状态。**

Codex 子线程为临时空目录、只读沙箱、无网络工具，关闭 shell、exec、浏览器、插件和其他无关能力；通过公开 MCP 元数据列出继承配置中的服务名，逐个禁用并复核可用 MCP 工具数为零。此检查不调用 MCP 业务工具。只接受严格的家居动态工具；正常 Codex 登录由 CLI 自行管理，不导出 token、不冒用其他客户端身份。

## 可选 API 兼容路径

旧的直接 GPT-Live API 路径仍保留，仅在私有配置明确设置 `provider:"openai"` 时使用；它需要另行可用的 API key 与模型权限。`api_key_file` 指向当前用户所有的 0600 私有文件，也支持服务环境变量 `OPENAI_API_KEY`；密钥不进入 APK 或配对文件。当前 Codex 部署没有使用此路径，单独填写 key 文件不会改变默认 provider。

## 验证

```sh
python3 -m unittest discover -s work/voice-service -p 'test_*.py'
```

94 项模拟测试覆盖 backend、全设备目录、HTTP 边界、旧 API 路径及安装器，不连接 OpenAI、平板或 HA。实机证据与限制另见输出记录；模拟测试不证明物理开关或长期稳定。

协议参考：[Codex app-server](https://learn.chatgpt.com/docs/app-server)、[Codex Voice](https://learn.chatgpt.com/docs/features/voice)。当前实现以本机官方 CLI schema 和实测事件为依据，建立独立会话，不接管已有 ChatGPT App 对话。
