# 黄昏象棋（Huanghun Chess）

Android 中国象棋客户端。自带引擎与开局库，可单机对弈、打谱、批量转换棋谱，
并支持「跨设备借引擎」——一台手机出借算力，另一台只负责界面与触摸。

当前版本：**2.9.308**（`versionCode 344`）

已编译的 APK 可在 [Releases](https://github.com/theoccasional/huanghun-chess-open/releases) 页面下载。

## 主要能力

- **对弈**：人机、双人、挑战残局；内置皮卡鱼（UCI）与多套开源引擎
- **开局库**：XQB / OBK / BOOK.DAT / PGN 等格式，可加载外部库
- **棋谱**：PGN、中文记谱、ICCS、CBL、XQF、ECCO 等的导入导出与批转
- **连线 / 观战**：识别屏幕与棋盘，移植并整合 YK 连线内核
- **跨设备借引擎**：发送端出借本机引擎，接收端只传着法，避免发热
- **棋例判定**：按亚象联《象棋比赛规例》处理长将、长捉、长打与重复局面

## 目录结构

```
app/         主程序（Java / Kotlin + JNI C++，assets 内为引擎、权重与棋库）
batchtool/   棋谱批转工具模块
*.gradle.kts 构建脚本
```

## 构建

环境：**JDK 17**（JDK 25 会因 AGP 解析失败）、Android SDK（compileSdk 35）、NDK。

1. 在工程根目录创建 `local.properties`，写入你自己的 SDK 路径：

```properties
sdk.dir=/path/to/Android/Sdk
```

2. 构建：

```bash
gradle :app:assembleFullRelease
# 或使用 wrapper：./gradlew :app:assembleFullRelease
```

产物：`app/build/outputs/apk/full/release/app-full-release.apk`

### 签名（可选）

仓库**不含**签名文件。把自有 keystore 放到工程根目录并命名 `pikafish-release.jks`
即可自动签名；也可用环境变量覆盖口令：

```bash
export HHXQ_STORE_PASSWORD=你的口令
export HHXQ_KEY_PASSWORD=你的口令
export HHXQ_KEY_ALIAS=你的别名
```

没有 keystore 时只会产出未签名包，构建不会因此失败。

### 资源说明

仓库包含引擎二进制与权重（`app/src/main/assets`、`app/src/main/jniLibs`），
但不包含私有识别模型（`app/src/main/assets/models/`，见下节许可说明）。
如需完整的棋盘/棋子识别能力，请自行提供模型文件放入该目录。

## 开源许可

本仓库以 **GNU GPL v3.0** 发布，全文见 [LICENSE](LICENSE)。

原因是本项目随包分发并调用多个 GPL 组件（皮卡鱼 Pikafish、Fairy-Stockfish、
CCStockfish、EleEye 等）。按 GPL 要求，分发二进制时必须提供对应源码，
本仓库即为该源码的公开获取方式。

第三方组件清单与各自的许可证、源码出处，见
[app/src/main/assets/licenses/THIRD_PARTY.md](app/src/main/assets/licenses/THIRD_PARTY.md)。

引擎权重（`*.nnue`）另有独立许可条款，见
[app/src/main/assets/licenses/NNUE-LICENSE.md](app/src/main/assets/licenses/NNUE-LICENSE.md)。

## 更新记录

### 2.9.308

- 修复棋例判定：被将军时「支士解将」曾被误判成捉子，导致「一将一捉」拖成和棋；
  现判定为长打（照捉混合），违规方必须变着。
- 新增回归测试 `app/src/test/.../model/RepetitionRuleChaseTest.kt`。

### 2.9.307

- 发送端小棋盘补齐九宫格斜线、炮位/兵位定位角标、楚河汉界。
- 三个操作按钮改为一行三列；底部产品立场文案 15 秒轮播。

## 免责声明

本项目仅供个人学习、研究与娱乐使用。请勿用于任何线上比赛作弊或商业牟利行为。
