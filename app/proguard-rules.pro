# 黄昏象棋 —— release 混淆规则（当前 build.gradle 中 isMinifyEnabled = false，
# 此文件仅作为占位以满足 AGP 对 proguardFiles 的路径引用；如需开启混淆再补充 keep 规则）。

# ONNX Runtime JNI 反射入口
-keep class ai.onnxruntime.** { *; }
