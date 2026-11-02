# 手机离线识谱资源准备

本目录只用于开发机准备资源；不会被安卓运行时加载。下载缓存位于`downloads/`，不打入APK。

```powershell
python -X utf8 tools/mobile-omr/prepare.py runtime
python -X utf8 tools/mobile-omr/prepare.py models
python -X utf8 tools/mobile-omr/build_worker.py
python -X utf8 tools/mobile-omr/licenses_manifest.py
python -X utf8 tools/mobile-omr/verify.py
```

前三条需联网下载缺失依赖；已有资源会复用。Pyodide轮子按官方锁文件SHA256校验。`licenses_manifest.py`补齐许可证并记录每个可打包资源的SHA256；`verify.py`纯本地核对字节、哈希及必须文件，不启动推理。

固定来源：homr提交`863eb3c6487f3c6fe50267fb71613f05162bb7c5`，Partsong提交`18b38f2602ec5a4cf6230ebee391595a4d18d9fd`，Pyodide314.0.7/Python3.14.2，ONNX Runtime Web1.30.0。当前兼容路径使用`ort.wasm.min.mjs`和标准`ort-wasm-simd-threaded.mjs/.wasm`，固定单线程；同时保留原有`asyncify`和`jsep`依赖供源码归档和后续验证。

构建产物全部写入`app/src/main/assets/local-omr/`。HTTP(S)静态资源路径需支持同源`.js/.mjs`、`.wasm`、`.whl`及二进制模型，模块worker使用相对路径，不需要CDN。Android宿主负责提供本地HTTPS资产源、正确MIME和`/__omr_rpc__/{runId}/{callId}`响应流。该响应流在读取时等待另一个工作线程的结果，拦截回调本身不得阻塞；界面通过`Android.deliverInferenceChunk`分块交付结果，取消或超时需唤醒等待者。

兼容路径不需要JSPI、SharedArrayBuffer或跨源隔离。`worker.js`在加载模型前执行echo自检；Python普通同步执行，推理经独立`inference-worker.js`完成。测试模拟缺少JSPI时应删除`WebAssembly.Suspending`/`promising`属性，而非将其设为`undefined`（Pyodide会用`in`判断能力）。二进制包测试：`node tests/ui/rpc-codec.cjs`；真实模型完整验证：`node tests/ui/compat-runtime.cjs`。

`build_worker.py`不会加入Partsong的合唱专用“同谱号拆开大谱表”规则，因此保留钢琴谱上下谱表同为高音谱号的合法情况。识谱完成后仍需校对。
