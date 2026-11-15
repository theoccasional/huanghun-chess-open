# NOTICE · 黄昏象棋公开版

本工程以 **GNU GPL v3.0** 发布，完整条款见 [LICENSE](LICENSE)。

随包第三方组件与各自的许可证、源码获取方式，见
[app/src/main/assets/licenses/THIRD_PARTY.md](app/src/main/assets/licenses/THIRD_PARTY.md)。

## 许可证正文（随包）

- `app/src/main/assets/licenses/GPL-3.0.txt` — GNU GPL v3.0
- `app/src/main/assets/licenses/LGPL-3.0.txt` — GNU LGPL v3.0（微思象棋播放器 vschess）
- `app/src/main/assets/licenses/NNUE-LICENSE.md` — 引擎权重许可（仅限合法用途，未经许可不得商用）

## 主要第三方

- Pikafish / Fairy-Stockfish / CCStockfish / EleEye / Chameleon / NewGG / UFX — GPL，独立进程
- 微思象棋播放器（vschess）— LGPL-3.0，https://github.com/FastLight126/vschess
- yk_xiangqi 连线内核 — GPL-3.0，https://github.com/zyk17/yk_xiangqi
- jQuery — MIT；ONNX Runtime — MIT；AndroidX / Kotlin — Apache-2.0；ML Kit — Google SDK 条款

## 素材

- 随包棋谱：仅五羊杯 29 本、古谱 33 本、名手对局 9 本，均为纯着法记录；来源与处置见
  [ASSET_LICENSES.md](ASSET_LICENSES.md)。
- 背景音乐：三首自制背景乐（`snd/bgm1.mp3`、`snd/bgm2.mp3`、`snd/bgm3.mp3`），AI 生成 + 轻度调整，提示词不含任何既有曲目名称；界面中性编号。
- 识别模型：`models/` 为作者自有、不随源码仓库分发；`link/` 模型随仓库提供，仅供本工程使用。

## 说明

本文件不是法律意见。公开分发前，请按 THIRD_PARTY 与 ASSET_LICENSES 的清单逐项确认；
NNUE 权重不得用于商业用途（未经许可），不得用于线上比赛作弊。
