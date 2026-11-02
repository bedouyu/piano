# 本机离线识谱运行时

本目录随安卓APK分发；识别时只读取本地资源，不请求CDN、不向服务器发送乐谱。

- `worker.js`：Python工作线程；同步请求应用内的本地二进制通道，协议见`manifest.json`。
- `inference-worker.js`：独立的ONNX推理线程；默认CPU/WASM单线程。
- `rpc-codec.js`：本地结果包编码；保留张量原始二进制，最大64MiB，由界面按512KiB分块交给安卓宿主。
- `python/runner.py`：调用固定版本homr识别单页；保留钢琴大谱表及同谱号双谱表，不包含合唱分谱特化规则。
- `python/onnxruntime.py`：将Python推理桥接至本机ONNX Runtime Web。
- `models/`：三个官方homr模型，字节数和SHA256见清单。
- `pyodide/`：Python WebAssembly运行时和本机OpenCV、NumPy、Pillow、typing_extensions轮子。
- `ort/`：ONNX Runtime Web及WebGPU/WASM执行后端，含asyncify模块。
- `homr.zip`：固定版本homr源代码、词表及许可证。
- `licenses/`、`sources/`：许可证、署名和桥接源代码备份。

兼容路径不需要WebAssembly JSPI、SharedArrayBuffer或跨源隔离。Python与ONNX分别运行，安卓宿主把推理结果作为本地HTTPS响应返回；这个地址由APK自身拦截处理，不连接外部服务器。仍需系统组件具备模块工作线程、WebAssembly SIMD及工作线程内XMLHttpRequest。识别启动前先进行通道自检；取消时同时终止两个线程和本地会话。识谱结果仍须人工校对。

重建工具位于项目`tools/mobile-omr/`，下载压缩包缓存不打入APK。`manifest.json`记录来源、版本、改造点和逐文件SHA256。
