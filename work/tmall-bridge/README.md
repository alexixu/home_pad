# 天猫精灵 Wi-Fi 窗帘后台桥接

将已登录天猫精灵中的两台杜亚 V7 Wi-Fi 窗帘接成 Home Assistant `cover` 实体，并加入现有平板控制中心。运行时官方 App 位于平板的独立虚拟显示屏，物理屏幕保持 home_pad。

控制链路：控制中心 / Codex → 平板本地 HA → 专用命令 helper → 平板本地桥接 → 官方 App → 窗帘。账号登录和厂商通信由官方 App 处理，不提取或复用其令牌。

## 当前范围

- 两个精确目标：主卧窗帘、主卧窗纱；要求房间、名称和在线设备卡片唯一匹配。
- 读取官方页面的“窗帘打开位置”。约每 30 秒查询一轮。
- 窗帘提供打开、关闭、停止。客厅奋腾达吸顶灯通过官方详情页每分钟读取一次电源报告，并作为只读实体展示。
- 三个按钮须由官方页面文字和无障碍节点同时确认；不按固定坐标点击。
- 操作前进入官方设置页核对设备名称与房间，返回稳定详情页后，对唯一匹配的按钮发出无障碍点击。
- 按用户指令关闭后，用户确认两台均已关闭；随后打开时，用户确认只有窗纱打开。增加身份与时序校验、尝试真实触控后，用户均反馈窗帘没有动作；真实触控未解决问题，已撤回该运行方式，窗帘打开故障仍未解决。**官方位置一直回报 10%，与不同实物位置不符，当前不能把百分比或 HA 开合状态作为可靠位置证据。** 报告已带 `position_trusted: false`。
- HA 与桥接都在平板上运行。Mac 用于构建、首次配置和 ADB 启动，不承担运行时代理。
- **平板重启后需要通过已授权 ADB 重新启动桥接。** 当前尚未实现桥接的开机自启；这不改变已有 HA 的启动配置。

## 安装与查看

前提：项目现有平板本地 HA、私有 HA 配置、正常官方账号登录、JDK / Android 工具链和已授权 ADB。沿用现有配置，不重建 HA，不清除应用数据。

从项目根目录执行：

```sh
python3 work/tmall-bridge/build.py
work/homeassistant/client-venv/bin/python work/tmall-bridge/ha_setup.py install
python3 work/tmall-bridge/deploy.py start
work/homeassistant/client-venv/bin/python work/tmall-bridge/ha_setup.py export
```

HA 配置工具依赖 `websocket-client==1.9.0`。它使用本项目既有私有 HA 管理会话创建三个专用 `input_text` helper 和两个 Template cover；不会调用窗帘或插座服务。运行时仅使用现有非管理员平板身份，访问 `127.0.0.1:8123`。

已登录的天猫精灵中有唯一的「客厅 / 灯」卡片，且确认它是奋腾达吸顶灯时，可执行 `ha_setup.py install-lamp` 添加只读 Template 二值传感器。重启桥接并导出目录后，控制中心可按名称或 `FTD` 搜索。页面无法读取或报告过期时，实体显示不可用。

平板桌面需要 2.3.1 或后续版本，以便刷新时导入新设备目录。2.3.2 起，控制中心的窗帘卡片直接显示“打开”“关闭”，不展示当前开合状态，也不根据开合百分比禁用这两个按钮；停止等操作保留在“操作…”菜单。进入 **控制中心 → 全部设备 → 刷新**，在窗帘行选择“操作…”即可查看三个动作。触屏沿用现有确认流程；Codex 语音沿用既有意图校验。

```sh
python3 work/tmall-bridge/deploy.py status
work/homeassistant/client-venv/bin/python work/tmall-bridge/ha_setup.py verify
python3 work/tmall-bridge/deploy.py stop
```

停止只处理本桥接，先将报告设为不可用、停止后台天猫精灵，再释放虚拟屏；不重启 HA 或 Termux。不要直接用进程信号代替正常停止。

## 过期和故障处理

命令只有两个固定目标和三种动作，要求已授权 HA 用户、有效请求标识和新鲜时间戳。启动时忽略既有命令，不补执行、不重试；执行前再次读取命令和页面能力。没有可用页面、目标重名、未登录或主屏离开中控时拒绝操作。

点击后保留官方页面至少六秒，并记录可识别的厂商 UI 成功或失败提示。`status` 中的 `last_command_evidence` 分别给出目标核验、输入方式和结果阶段；`clicked_unconfirmed` 只表示已发送输入，`vendor_ui_acknowledged` 只表示看到了 UI 回执，两者均不等同于实物已经到位。未收到回执时不自动重放。

报告包含观测时间，过期后 HA 实体不可用。正常停止会立即置为不可用；异常退出时，模板按分钟检查，约 2–3 分钟内过期。恢复只恢复状态查询，不重放旧动作。官方 App 升级后若页面结构变化，应重新只读验收。

## 私有数据

`private/`、`build/`、登录辅助文件、设备原始记录和图片均不进入 Git。平板上的运行配置放在仅 shell 可访问的目录中，配置权限为 0600；HA 凭据不进入进程参数或日志。桥接不开放 HTTP 控制端口。

离线校验：

```sh
python3 work/tmall-bridge/build.py
work/homeassistant/client-venv/bin/python -m unittest discover -s work/tmall-bridge -p 'test_*.py'
```

实现参考：[HA Template cover](https://www.home-assistant.io/integrations/template/#cover)、[scrcpy 虚拟显示屏](https://github.com/Genymobile/scrcpy/blob/master/doc/virtual-display.md)。系统兼容方式与第三方许可见项目 `THIRD_PARTY_NOTICES.md`。
