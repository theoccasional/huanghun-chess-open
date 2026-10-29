黄昏象棋 · 开放源码仓（Huanghun Chess）

这是一个 Android 中国象棋应用（对弈 / 残局 / 打谱 / 引擎 / 连线识别）的源码仓。
本仓只放代码，不含模型权重、引擎二进制与图片音频等大件资产（原因见下）。



许可证：MIT（见 LICENSE）

这是一个 Android 中国象棋应用（对弈 / 残局 / 打谱 / 引擎 / 连线识别）的源码仓。

本仓只放代码，不含模型权重、引擎二进制与图片音频等大件资产（原因见下）。

本仓只放代码，不含模型权重、引擎二进制与图片音频等大件资产（原因见下）。


许可证：MIT（见 LICENSE）

语言：Kotlin / Java
最低 SDK：24　目标 SDK：见 app/build.gradle.kts

构建工具：Gradle 8.x + JDK 17 + Android SDK Platform 34
一、快速开始六、免责声明

git clone <本仓库地址> huanghun-chess
#cd huanghun-chess本项目仅供学习与研究使用。使用者需自行遵守所在地法律及各第三方组件许可，:app:1) 准备本地配置（不要提交）
assembleFullDebug     # 调试包:app:echo "sdk.dir=/你的/Android/Sdk" > local.properties

#assembleFullRelease   # 发布包（需自带签名）

./gradlew :app:2) 放入资产（见下一节），然后构建


./gradlew :app:assembleFullDebug     # 调试包
assembleFullRelease   # 发布包（需自带签名）
二、需要自备的资产（不在本仓）
本仓有意不包含以下文件，请自行放置到对应目录：
资产	放置路径	说明

识别模型 pose_model.onnx / layout_model.onnx / piece_classifier.onnx	app/src/full/assets/models/、app/src/full/assets/link/	本项目的识别主链，需自备或自训


引擎权重 pikafish.nnue 等	app/src/full/assets/、app/src/full/assets/engine/	受各自许可证约束（见 NOTICE）

app/src/main/java/com/pikafish/chess/
引擎可执行文件 libpikafish_bin.so / libfstockfish.so / libeleeye.so 等	app/src/main/jniLibs/arm64-v8a/	GPLv3 组件，源码见 NOTICE 的上游链接
图片/音频/字体 / 棋库	app/src/main/assets/、res/	自行替换为你有权使用的素材
缺少这些资产时，代码仍可编译，但识别与算棋不可用。
三、架构速览
├── MainActivity.kt          主界面与全部面板交互
├── ChessGame.kt             对局内核（走子、悔棋、循环判定、后台多线预想）
├── engine/                  UCI/UCCI 引擎封装（进程式，非 JNI 链接）
├── heavy/                   辎重包（引擎共享服务、HTTP 分发）


├── duel/                    同 WiFi 双人对战（发现、握手、悔棋表决）
├── qipu/                    棋谱与 ECO 判定



├── link/                    连线识别（截图取帧、布局识别、无障碍落子）

└── ui/                      棋盘视图与各页面



连线部分的设计要点：帧差驱动 + 多帧投票 + 布局记忆 + 自动方向判定；

独立实现见另一仓 link-kit（同为 MIT）。

四、第三方组件与许可


见 NOTICE。摘要：

引擎（Pikafish / Fairy-Stockfish / CCStockfish / EleEye 等）为 GPLv3， 本项目不链接它们，仅以独立可执行文件 + UCI 文本通信调用；*NNUE 权重有独立许可条款，禁止用于线上作弊等用途；*不得用于任何作弊、代打或违反对弈平台规则的行为。
.nnue、*.so、


本仓不包含上述第三方源码；如需请按其上游仓库获取。

五、贡献
local.properties、keystore 与私有素材。

