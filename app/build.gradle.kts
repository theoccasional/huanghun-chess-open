import org.gradle.api.tasks.bundling.Zip

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pikafish.chess"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 28
        versionCode = 344
        versionName = "2.9.308"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // ★★★ 操作包 / 辎重包 分离 ★★★
    //   slim = 轻量操作包：不打包大件资产（引擎权重/识别模型/棋库），大件统一从「辎重包」读取；
    //   full = 全量辎重包：自带全部大件，可完全独立运行，并额外注册「黄昏象棋辎重包」入口当主机。
    //   两者共用同一套读取代码（HeavyStore：辎重包优先、内置 assets 兜底），对上层业务透明。
    //
    //   ★★★ 各自独立包名（applicationId）—— 两台 APK 互不覆盖、互不卸载 ★★★
    //   slim 走主包名 com.pikafish.chess（操作包，装在手机上玩棋）；
    //   full 走独立包名 com.pikafish.chess.box（辎重包，当主机发码）。
    //   同一台手机上两个都装也各是各的：删掉辎重包不影响操作包，反之亦然——
    //   它们通过「配对码」互相联系，但安装/卸载彼此独立。
    flavorDimensions += "pack"
    productFlavors {
        create("slim") {
            dimension = "pack"
            applicationId = "com.pikafish.chess"
        }
        create("full") {
            dimension = "pack"
            // ★★★ 统一化（A 方案）：单包＝全量本体，走主包名 com.pikafish.chess，一个图标。
            applicationId = "com.pikafish.chess"
        }
    }

    // ★ Android 11+ (API 30+, targetSdk 34) 要求：
    //   1. resources.arsc 必须未压缩且4字节对齐
    //   2. .so 文件必须未压缩且4字节对齐（否则安装报错 -124）
    //   3. extractNativeLibs="true" + useLegacyPackaging=true → 安装时提取.so到lib目录
    //   4. noCompress 确保.so和.arsc在APK内未压缩（满足Android 11+要求）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // ★ 确保 resources.arsc 和 .so 文件都不被压缩（满足 Android 11+ 对齐要求）
    // ★ .onnx 模型改为允许 ZIP 压缩存储在 APK 内 —— 首次启动由 Splash 预解压
    //   到 filesDir 并展示真实进度条。压缩后 APK 体积显著减小（onnx 约省 30%）。
    androidResources {
        noCompress.clear()
        noCompress.addAll(listOf("arsc", "so"))
    }

    // ★ 开源版：签名文件与口令不进仓库。
    //   把自己的 keystore 放到工程根目录、命名 pikafish-release.jks 即可；
    //   口令可用环境变量 HHXQ_STORE_PASSWORD / HHXQ_KEY_PASSWORD / HHXQ_KEY_ALIAS 覆盖。
    //   没有 keystore 时只产出未签名包，构建不会失败。
    val keystoreFile = rootProject.file("pikafish-release.jks")
    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("HHXQ_STORE_PASSWORD") ?: "pikafish123"
                keyAlias = System.getenv("HHXQ_KEY_ALIAS") ?: "pikafish"
                keyPassword = System.getenv("HHXQ_KEY_PASSWORD") ?: "pikafish123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // ★ 核心库脱糖：junrar 8.x（唯一支持 RAR5 的版本）内部要用 java.nio.file.*，
    //   Android 上 java.nio.file 需要 API 26+，开启脱糖后 minSdk 24 也能跑。
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // ★ 单元测试（仅 JVM，不影响 APK）：让 android.* 桩方法返回默认值，
    //   这样 ChessGame 这类含 android 依赖的类也能在纯 JVM 上跑对拍/探针测试。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ★《清新人生手册》完整性：把手册目录登记为测试输入。
//   否则文件被删改时 Gradle 会把 test 任务判成 UP-TO-DATE 直接跳过，
//   校验形同虚设。登记后只要手册有任何一个字节变化，测试必然重跑并失败。
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets/manual").withPropertyName("manualAssets").optional()
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // ★ ML Kit 中文文字识别（内嵌模型，不需要Google Play Services）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // ★ ONNX Runtime — 深度学习棋盘识别模型推理引擎
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    // ★ Activity Result API
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    // ★ 「观战」连线（YK 源码逐字移植）的引擎驱动 GameViewModel(AndroidViewModel) 依赖：
    //   viewModelScope/AndroidViewModel 来自 lifecycle-viewmodel-ktx；
    //   其可观察状态用 Compose 的 mutableStateOf（仅 runtime，无需 Compose 编译器）。
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.compose.runtime:runtime:1.5.4")
    // ★ AndroidSVG — 支持个性化皮肤包里的 .svg 棋子文件
    implementation("com.caverock:androidsvg:1.4")
    // ★ junrar — 个性化皮肤包支持 RAR / RAR5（含嵌套 RAR）。8.x 是唯一支持 RAR5 的版本。
    implementation("com.github.junrar:junrar:8.1.1")
    // ★ slf4j-api — 内嵌的 CHM 解析器（org.apache.tika.parser.microsoft.chm）用它做日志；
    //   junrar 只把它声明为 runtime 依赖，编译期需显式引入。
    implementation("org.slf4j:slf4j-api:2.0.17")
    // ★ aircompressor — QYL 棋谱（启阳象棋 .qyl）容器内逐局 zstd 帧的【纯 Java】解压。
    //   为什么不用 zstd-jni：zstd-jni 的 .so 是 glibc(linux) 构建（NEEDED libc.so.6），
    //   在 Android(bionic) 上根本 load 不起来；且 .so 作为 Java 资源会被 AGP 打包时剔除，
    //   真机上必然解压失败。aircompressor 是纯 Java（Java 8 字节码），全机型可用。
    //   ★ 但 aircompressor 0.27 依赖 sun.misc.Unsafe（Android 隐藏 API 黑名单）——真机会崩，
    //   故这里改为【内联源码】：源码已置于 app/src/main/java/io/airlift/compress/zstd/，
    //   UnsafeUtil 换成了纯 byte[] 实现（见该文件注释），不再依赖任何 jar。
    // ★ 核心库脱糖实现（NIO 变体：提供 java.nio.file.*，junrar 依赖）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
    // ★ ECCO VM 对拍测试（仅单元测试，不影响 APK）
    testImplementation("junit:junit:4.13.2")
}

// ★★★ 辎重包打包任务 ★★★
// 把「大件资产」单独打成一个 ZIP 文件，内部条目路径与 assets 相对路径一一对应
// （models/pose_model.onnx、engine/pikafish.nnue、ccstockfish/entry.nnue、
//   fairy/xiangqi.nnue、link/*.onnx、qipu_cbl/…）。
// 同一份文件：手机上由操作包（slim）直接读取；电脑上任何解压工具都能打开；
// 不同设备之间整份拷贝即可 —— 不必为不同设备维护多套格式。
val heavyPack by tasks.registering(Zip::class) {
    group = "build"
    description = "打包辎重包（引擎权重 / 识别模型 / 棋库合集）"
    from("src/full/assets")
    archiveFileName.set("黄昏象棋_辎重包.zip")
    destinationDirectory.set(layout.buildDirectory.dir("heavy"))
}
