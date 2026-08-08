# 🏀 HoopVision (篮睛) - Android 客户端

本目录为 HoopVision 的 Android 原生端工程源码。

### 核心模块一览：
* `ui/camera/`: 包含 `CameraScreen.kt`（Compose 相机界面与毛玻璃卡片）、`PoseHelper.kt`（MediaPipe 骨骼推理）、`PoseSmoother.kt`（指数移动平均滤波）、`ShotStateMachine.kt`（投篮动作切分状态机）。
* `util/`: 包含 `TtsManager.kt`（Android 原生 TTS 语音播报管理）。
* `network/`: 包含 `ApiClient.kt`（Retrofit REST 通信与数据模型）。

完整架构设计与项目介绍请参阅根目录 [README.md](../README.md)。
