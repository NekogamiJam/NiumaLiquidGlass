# 离线 ASR JNI 适配层

这不是重新实现 ASR：使用 sherpa-onnx v1.12.17 官方 Android ARM64 C API 和 ONNX Runtime，调用同一份 SenseVoice 2024-07-17 int8、tokens 与 Silero VAD。模型许可和引擎许可分别见 [第三方说明](<../THIRD-PARTY-NOTICES.md>)。

- 构建入口：[build-native.ps1](<../build-native.ps1>)；NDK r28c、C++17、ARM64、静态 libc++；编译器临时目录仅在用户授权的工作区内。
- 头文件与库：本目录 `vendor/c-api.h` 和工程内 `app/src/main/jniLibs/arm64-v8a`。当前桥接构建不再依赖历史 phone-asr-check 目录。NDK 默认从研究目录的集中构建环境解析，也可传入 `-NdkRoot`；临时文件只写 `offline-asr/native/compiler-temp`。脚本本身不下载、安装 SDK 或访问手机。
- CPU ASR 2 线程；VAD 1 线程、阈值 0.5、最短语音 0.25s、句末静音 0.5s、每次 512 个 16kHz float 样本、最长分段 20s；24s 有界环形缓冲保留片段前 200ms；结束时仅向 VAD 补 0.8s 零样本。
- `create/reset/accept/finish/destroy` 仅从 Kotlin 进程级单线程推理 executor 调用。原始 PCM、结果只留内存，不打开麦克风、编辑器、网络或录音文件。麦克风捕获由 Kotlin 明确启动。
- JNI 边界固定错误消息；C++ 异常不越界；C API stream/result/segment 使用 RAII；拒绝错误帧长、非有限样本、已销毁句柄；分段访问必须仍在环形缓冲的有效绝对范围内，不能绕回错误音频。
- 自有 ring 在 reset/destroy 时填零；这不宣称能对 OS/第三方运行库的所有内存副本进行安全擦除。

修正版实际构建与 `llvm-readelf` / `llvm-nm` 核验结果在构建日志。动态依赖只有 `libsherpa-onnx-c-api.so`、`libonnxruntime.so`、`libm.so`、`libdl.so`、`libc.so`；没有额外 `libc++_shared.so`。5 个 LocalAsrNative JNI 方法均导出。

此适配层源码可重建；打包的 sherpa/ORT 与原有 librime JNI 仍是上游预编译输入，不能把整个源码包描述为全部原生依赖的完整对应源码或字节级可复现构建。
