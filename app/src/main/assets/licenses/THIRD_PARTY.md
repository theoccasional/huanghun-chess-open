# 第三方组件与开源义务说明（THIRD_PARTY）

本仓库是**公开源码的 GPL-3.0 工程**：应用代码（Kotlin / Java）与随仓库提供的资源，
除下列第三方组件另有许可外，均按根目录 [LICENSE](../../../../../LICENSE) 的 GNU GPL v3.0 分发。
本文件说明各第三方组件的许可证、包内形态与源码获取方式，不构成法律意见。

## 一、引擎（独立进程，GPL）

| 组件 | 包内形态 | 许可证 | 源码 / 说明 |
|---|---|---|---|
| Pikafish（皮卡鱼） | `assets/engine/pikafish`（可执行） | GPLv3 | https://github.com/official-pikafish/Pikafish |
| Fairy-Stockfish | `libfstockfish.so`（可执行） | GPLv3 | https://github.com/fairy-stockfish/Fairy-Stockfish |
| CCStockfish | `assets/ccstockfish/` | GPLv3 | 上游 CCStockfish 项目 |
| EleEye（象眼） | `libeleeye.so` | GPLv2+ | 上游 EleEye 项目 |
| Chameleon / NewGG / UFX | `libchameleon.so` / `libnewgg.so` / `libufx.so` | GPLv3（各项目） | 各上游项目页 |

- 以上引擎均以**独立可执行文件**随包，App 通过 `ProcessBuilder` 以 stdin/stdout 走 UCI/UCCI 通信，
  **不链接进 App 进程**；GPL 组件之间为聚合关系。
- 本项目对上述引擎的改动仅为编译参数、可执行权限与启动方式，**未修改算法源码**。
- GPLv3 正文随包：`assets/licenses/GPL-3.0.txt`；LGPLv3 正文：`assets/licenses/LGPL-3.0.txt`。

## 二、权重与数据（非 GPL）

| 组件 | 许可证要点 |
|---|---|
| `pikafish.nnue`、`xiangqi.nnue`、`entry.nnue` | NNUE-License：仅限合法用途；未经许可不得商用。全文见 `assets/licenses/NNUE-LICENSE.md` |

公开版为免费、无广告构建，未对权重作商业使用。若加入广告、会员或付费分发，
须先取得权重权利人的商用许可。

## 三、界面 / 连线组件

| 组件 | 包内形态 | 许可证 | 上游 / 义务 |
|---|---|---|---|
| 微思象棋播放器（vschess） | `assets/vschess/`（未修改的压缩发行文件） | **LGPL-3.0** | https://github.com/FastLight126/vschess ；保留文件头版权声明，随包附 `licenses/LGPL-3.0.txt`；上游源码公开可取 |
| yk_xiangqi 连线内核 | 编译进 App（`com.yk.xiangqi.*`） | **GPL-3.0** | https://github.com/zyk17/yk_xiangqi ；与本仓库 GPL-3.0 一致 |
| jQuery | `assets/vschess/js/jquery.js` | MIT | https://jquery.com/ |
| X7 引擎 | **未随包分发** | 上游未声明许可证 | https://github.com/zyk17/x7 ；仅作实现参考，不复制、不分发 |
| xiangqi-pad | **未随包分发** | 上游未声明 | https://github.com/hylixihuan123/xiangqi-pad ；仅作思路参考，不复制代码 |

## 四、运行时与系统组件

| 组件 | 形态 | 许可证 / 条款 |
|---|---|---|
| ONNX Runtime Android | Maven 依赖 | MIT |
| ML Kit 中文文字识别 | Maven 依赖 | Google SDK 条款（随 App 分发，不提供源码） |
| AndroidX / AppCompat / RecyclerView 等 | Maven 依赖 | Apache-2.0 |
| Kotlin 标准库 | Maven 依赖 | Apache-2.0 |

## 五、模型

- **摆子识别模型**（`models/pose_model.onnx`、`models/layout_model.onnx`、`models/yolo_model.onnx`）：
  作者自有模型。**不随源码仓库分发**（`.gitignore` 已排除），仅由作者在官方 APK 构建时放入；
  使用官方 APK 不构成开源许可授权，模型权利仍归作者。
- **连线识别模型**（`link/piece_classifier.onnx`、`link/yolov11.onnx`）：作者自有模型，随仓库提供，
  供本工程构建与连线功能使用；不得单独抽取为第三方产品分发。
- 识别结果仅用于本机摆子 / 连线辅助，不得用于线上比赛作弊。

## 六、音频与素材

- 走子 / 吃子 / 将军 / 悔棋 / 开始 / 结束等**短音效**：本工程自带的短促程序合成音效，未从任何棋软或第三方素材采样。
- 三首**背景音乐**（`bgm1.mp3`、`bgm2.mp3`、`bgm3.mp3`）：自制背景乐，AI 生成 + 轻度调整，提示词仅基于风格 / 意象 / 情感需求、不含任何既有曲目名称；界面以 1MP3 / 2MP3 / 3MP3 中性编号展示。
- 棋谱、图片与版式素材的来源与处置，见根目录 [ASSET_LICENSES.md](../../../../../ASSET_LICENSES.md)。

## 七、GPL 合规要点（自查）

1. 分发 APK 时，必须同时让接收者能取得**本仓库对应源码**（本仓库即源码；发布页请链接到对应 tag）。
2. 随包保留 GPLv3 / LGPLv3 正文与第三方清单，不得删除版权声明。
3. 若修改引擎源码或 vschess，必须提供修改后的对应源码。
4. 引擎保持独立进程；不要把 GPL 引擎改为 JNI 直接链接。
5. NNUE 权重不得用于商业用途（未经许可），不得用于线上作弊。
6. 自有识别模型不属于 GPL 授权范围，对外分发前请自行确认权利归属。