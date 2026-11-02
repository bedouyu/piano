# 第三方材料

## 手机内识谱引擎

应用内的 `app/src/main/assets/local-omr/` 包含homr识谱模型及代码、从Partsong适配的浏览器推理桥、Pyodide/Python、ONNX Runtime Web，以及NumPy、OpenCV、Pillow等运行依赖。相关原始许可证和作者说明保存在该目录的 `licenses/` 与 `sources/` 中；固定版本、来源及逐文件SHA256见 `manifest.json`。

- homr源码固定提交：`863eb3c6487f3c6fe50267fb71613f05162bb7c5`，https://github.com/liebharc/homr
- 浏览器适配参考：https://github.com/pzfreo/partsong （AGPL-3.0）
- Pyodide：https://pyodide.org/ （以随附原始许可为准）
- ONNX Runtime：https://github.com/microsoft/onnxruntime （MIT）
- 手机可行性研究参考Andromr：https://github.com/aicelen/Andromr；当前APK并未打入Andromr应用。

本应用修改了浏览器资源加载方式，改为只读取APK本地资源，去掉面向合唱声部的特定谱表拆分逻辑，并添加手机端任务编排。0.2.1将Python与ONNX推理分入独立工作线程，加入应用内二进制响应通道，以替代JSPI调用。源代码、构建和离线资源复现入口保留在当前项目；后续分发时须连同相关许可证和对应源码要求一并处理。

## Salamander Grand Piano

APK内的钢琴MP3采样来自 Alexander Holm 的 Salamander Grand Piano（Yamaha C5），由 Tone.js 音频仓库分发；CC BY 3.0。

- 原始说明与署名：app/src/main/assets/piano/SALAMANDER-LICENSE.txt
- 来源：https://github.com/Tonejs/audio/tree/master/salamander
- 授权：https://creativecommons.org/licenses/by/3.0/
- 本应用选取部分采样，以变速/变调方式覆盖88键。未将音频宣称为本应用原创。

## Bravura

乐谱字体 Bravura，Steinberg Media Technologies GmbH，SIL Open Font License 1.1。

- 原始授权：app/src/main/assets/fonts/BRAVURA-LICENSE.txt
- 来源：https://github.com/steinbergmedia/bravura
- 未修改字体。

## 用户参考乐谱

references/中的乐谱图片由用户提供，供本项目功能验证；不打入APK，不作为内置原创练习，也不对外发布。
