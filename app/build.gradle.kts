import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 版本名可以重置，版本码始终递增，保证覆盖安装
 */
val appVersionName = "0.0.9"
val appVersionCode = 155

/**
 * 从根目录的 keystore.properties 读取签名，缺少时使用 debug 签名
 * 正式发布前配置自己的密钥，密钥和密码不入库
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null
// 固定签名只使用三项独立密钥配置（Base64、库密码、别名）；KEY 密码复用库密码。
// 默认 smoke 仍用临时调试签名，不得被发布为在线升级候选。
val traceFixedSigning = providers.gradleProperty("traceFixedSigning").orNull == "true"
val traceStoreFile = System.getenv("TRACE_SIGNING_STORE_FILE")
val traceStorePassword = System.getenv("TRACE_SIGNING_STORE_PASSWORD")
val traceKeyAlias = System.getenv("TRACE_SIGNING_KEY_ALIAS")
// PKCS12 private-key password equals the keystore password (CarDeck 3-secret model).
if (traceFixedSigning) {
    require(!traceStoreFile.isNullOrBlank() && !traceStorePassword.isNullOrBlank() &&
        !traceKeyAlias.isNullOrBlank()) {
        "TRACE_FIXED_SIGNING_MISSING: refusing unsigned/debug fallback"
    }
}

android {
    namespace = "io.github.shaonialife.buicklinkplus"
    // 保持当前 SDK 版本
    //noinspection GradleDependency
    compileSdk = 34
    // 固定原生库裁剪工具版本，保持构建结果一致
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.github.shaonialife.buicklinkplus"
        minSdk = 31
        //noinspection OldTargetApi
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "BUILD_ID", "\"buick-$appVersionName-$appVersionCode\"")
        // Android 正则兼容用例使用设备测试
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (traceFixedSigning) {
            create("traceFixed") {
                storeFile = rootProject.file(traceStoreFile!!)
                storePassword = traceStorePassword
                keyAlias = traceKeyAlias
                keyPassword = traceStorePassword
            }
        }
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 反射和 AIDL 的保留规则未补齐，暂不开混淆
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        debug {
            // debug 沿用正式包名，避免内置服务需要重新配对
            versionNameSuffix = "-debug"
        }
        // 独立取证包与已验证的 .buick 包并存，避免签名冲突或丢失已有配对信息
        create("trace") {
            initWith(getByName("release"))
            applicationIdSuffix = ".buick.trace"
            versionNameSuffix = "-trace-r4"
            signingConfig = signingConfigs.getByName(if (traceFixedSigning) "traceFixed" else "debug")
            resValue("string", "app_name", "Buick Link + Trace")
            matchingFallbacks += listOf("release")
        }
        // buick 使用独立包名，方便与原版并存
        create("buick") {
            initWith(getByName("release"))
            applicationIdSuffix = ".buick"
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Buick Link +")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        // 关于页直接读取构建版本
        buildConfig = true
    }

    packaging {
        // 排除密码库中重复的 JVM OSGi 描述
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }

    lint {
        // 检查全部 Lint 问题，不使用基线
        abortOnError = true
        warningsAsErrors = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    // 依赖固定版本，许可见 docs/dependencies.md
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("androidx.core:core-ktx:1.13.1")
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 车机桌面分页
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // AirPlay 配对和加密
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("org.bouncycastle:bcprov-jdk18on:1.81.1")
    // 通过本机无线 ADB 激活内置 shell 服务
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("org.conscrypt:conscrypt-android:2.8.0")
    //noinspection GradleDependency,NewerVersionAvailable
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81.1")

    testImplementation("junit:junit:4.13.2")
    // 为 JVM 测试提供 JSON 实现
    //noinspection GradleDependency,NewerVersionAvailable
    testImplementation("org.json:json:20240303")
    //noinspection GradleDependency,NewerVersionAvailable
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Once Gateway 的 WSS transport；不复用车机的绑定网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    //noinspection GradleDependency,NewerVersionAvailable
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
}
