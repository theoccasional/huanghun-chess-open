# 第三方组件与开源义务说明（THIRD_PARTY）

本 App 曾采用「整包分发 + 只开源被强制要求的部分」策略。
**自 2.9.308 起，App 自身源码已整包以 GPL-3.0 开源**（仓库根目录 LICENSE），
因此不再存在「自身代码闭源」的说法；同时私有识别模型（`assets/models/`）不随仓库分发，
需要者请自行提供。

GPL 组件仍以**独立可执行文件**形式随包提供（通过 `ProcessBuilder` 以 stdin/stdout 走 UCI/UCCI 协议通信，
**不链接进本 App 进程**）。各组件许可证与源码出处如下。

## 一、必须开源（GPLv3）

| 组件 | 包内形态 | 许可证 | 对应源码 |
|---|---|---|---|
| Pikafish（皮卡鱼） | `lib/arm64-v8a/libpikafish_bin.so`（可执行） | GPLv3 | https://github.com/official-pikafish/Pikafish |
| Fairy-Stockfish | `libfstockfish.so`（可执行） | GPLv3 | https://github.com/fairy-stockfish/Fairy-Stockfish |
| CCStockfish | `assets/ccstockfish/` | GPLv3 | 上游同 CCStockfish 项目 |
| EleEye（象眼） | `libeleeye.so` | GPLv2+ | 上游 EleEye 项目 |
| Chameleon / NewGG / UFX | `libchameleon.so` / `libnewgg.so` / `libufx.so` | GPLv3（各项目） | 各上游项目页 |

- GPLv3 正文：随包 `assets/licenses/GPL-3.0.txt`，并随发布页提供；
- **我方对上述引擎的改动**：仅编译参数与可执行权限处理，**未修改算法源码**；如需可提供构建脚本；
- 上述引擎均**未被静态/动态链接进 App**，App 仅负责拉起进程与收发 UCI 文本。

## 二、有权重/数据许可（非 GPL）

| 组件 | 许可证要点 |
|---|---|
| `pikafish.nnue`、`xiangqi.nnue`、`entry.nnue` | 见 `assets/licenses/NNUE-LICENSE.md`：仅限合法用途，禁止用于线上作弊等 |

## 三、不分发的部分（本 App 自有）

- 识别模型 `pose_model.onnx`、`layout_model.onnx`、`piece_classifier.onnx`：**本 App 自有**，不分发源码/权重；
- 应用业务代码（Kotlin/Java）：闭源；
- 第三方私有模型（如 `yolo11_model.onnx`）**已移除，不再随包分发**。

## 四、自愿开源（可选）

如后续愿意开放部分模块（例如连线内核 `LinkKit`、ECCO 判定器），可按 MIT/Apache-2.0 单独发布，
与 GPL 组件保持目录与进程隔离，避免交叉传染。

## 五、合规要点（自查）

1. GPL 组件必须**独立进程**：禁止改为 JNI 直接链接；
2. 随包/发布页必须能拿到**许可证正文**与**源码获取方式**；
3. 引擎二进制若有本地修改，必须一并提供对应源码 patch；
4. 私有模型/数据**不得分发**——这是与 GPL 无关、但更严格的限制。
