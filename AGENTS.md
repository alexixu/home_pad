# Project working notes

- Project root is the checkout directory containing this file. Resolve it from the current checkout and use that working directory explicitly.
- User prefers Chinese and authorized installing/customizing the connected Xiaomi Pad 5 Pro.
- Home Assistant now runs locally in Termux on the tablet; the launcher uses http://127.0.0.1:8123. Wi-Fi loss/recovery and one reboot with automatic service/desktop recovery were verified. See README.md and outputs/平板本地服务验证.json for bounded evidence.
- Preserve the plug's power state. Do not send turn_on, turn_off or toggle commands for tests.
- Do not print account credentials, Xiaomi/HA tokens, private configuration files, signing passwords, or temporary transfer secrets.
- Do not run both copies of HA with the same Xiaomi authorization simultaneously. Mac container and Colima profile ha are stopped.
- Android launcher source: work/minimal-launcher/app. Build: python3 work/minimal-launcher/build.py. Keep existing signing key for upgrades.
- Outputs are user-facing deliverables; intermediates belong in work. Physical USB disconnection and a successful launcher refresh were confirmed by the user, with the ADB device absent. Real plug switching and prolonged unattended operation are not yet verified; do not infer them from read-only checks.
- A tablet-local 24-hour read-only soak recorder is running until 2026-10-31T16:35:24Z. See outputs/平板独立运行稳定性验证.json for the exact run and USB-disconnect evidence. Do not run work/tablet-ha/collect_soak_monitor.py during the run: its Termux foreground-session control can interrupt HA. Use bounded read-only process/state observation; later redesign collection without Ctrl+C. Do not reset or extend the run. Long-term success remains pending until logs are recovered and checked.
- Do not repeatedly ask the user to plug/unplug USB for testing. USB may remain connected during the long-term stability run; keep Mac HA stopped and USB forwards absent except for bounded log retrieval. The completed physical-unplug refresh check is separate evidence. Do not claim continuous 24-hour disconnection or battery endurance.
- Old workspace work/outputs paths are compatibility symlinks. Do not copy another project there or remove them without checking remaining references.

- Current launcher source is 2.1.2 (voice behavior unchanged from 2.1): local wake only; conversation uses the official Codex app-server's normal ChatGPT login with V3 WebRTC. No API key is needed for this verified transport. V1/V2/V3 WebSocket failures do not establish a WebRTC restriction. Never extract tokens or impersonate a first-party client.
- The per-user voice broker is managed by LaunchAgent com.alex.homepad.voice. It depends on Mac login, network and wakefulness; tablet-local HA remains independent. Keep microphone sessions closed while editing or between bounded tests. Only status queries are allowed in live tests; retain original soak deadline.
