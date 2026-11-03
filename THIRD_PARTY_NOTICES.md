# 第三方组件与许可

根目录 [LICENSE](LICENSE) 中的 MIT 许可适用于本项目原创代码与文档。第三方组件、模型、系统界面与商标仍遵循各自的许可和权利声明。

## 构建时下载的组件

这些二进制依赖与模型不提交到 Git。准备脚本固定版本与 SHA256，构建时核对，并将相关许可打包到 APK 的 `assets/licenses/`。

| 组件 | 当前固定版本 | 许可与来源 |
| --- | --- | --- |
| sherpa-onnx Java / JNI | 2.0.1 | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE) |
| sherpa-onnx 中文关键词模型 | `sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile` | 归档内 `README.md` 标注 Apache License 2.0，来自 [官方 kws-models 发布](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models)；原模型来源为 [pkufool 模型卡](https://www.modelscope.cn/models/pkufool/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01) |
| ONNX Runtime | 随 sherpa-onnx 2.0.1 Android 归档固定的二进制 | [MIT](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE)，准备脚本同时保留 [第三方声明](https://github.com/microsoft/onnxruntime/blob/v1.28.2/ThirdPartyNotices.txt) |

具体下载地址、模型与二进制校验值见 [prepare_wake_dependencies.py](work/minimal-launcher/prepare_wake_dependencies.py)。分发 APK 时需要保留以上许可文件、第三方声明与模型来源信息。

## 外部运行环境与服务

Home Assistant、Termux、Android 与官方 Codex CLI 为分别安装的外部运行环境，未作为源码或二进制包含在本仓库中。使用或另行分发时应遵守各自的许可与服务条款。ChatGPT / OpenAI 服务不因本项目采用 MIT 许可而获得额外使用权。

截图中的 Android 系统界面、应用图标与相关品牌标识属于其原权利人。本项目的 MIT 许可不提供第三方商标授权。
