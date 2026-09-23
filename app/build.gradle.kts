import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 正式签名配置从仓库根的 keystore.properties 读取（该文件与 release.jks 均不入库）。
// 缺失时回退 debug 签名，保证 clone 本仓库的人仍能 assembleRelease 做本地调试。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "edu.gcp.schedule"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.gcp.schedule"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 包名隔离：debug 与 release 签名不同，同包名会互相覆盖安装（数据全丢）。
            // 加 .debug 后缀后是两个独立应用，可共存；应用名由 src/debug/res 覆盖为「城职课表 Debug」。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // 有正式 keystore 用它；否则回退 AGP debug 签名（仅本地调试，不可对外分发）。
            // 注意：debug 与 release 签名不同，二者无法互相覆盖安装。
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("keystore.properties 缺失，release 回退 debug 签名（仅限本地调试，勿对外分发）")
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // 「关于」卡片用 BuildConfig.VERSION_NAME 取版本，不再手写字符串
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 胖乖调试诊断用（BASIC 级，只打请求行不打 header，token/sign 不进日志）
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // 胖乖 Token 加密存储（DESIGN 4.16 决策 2）：EncryptedSharedPreferences，密钥在 Android Keystore
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // 桌面小组件（DESIGN §3.6 / §4.13）：Compose 风格 API，与项目技术栈一致。
    // 传递引入 work-runtime（Glance 内部排更新走 androidx.startup 默认初始化）与 core-remoteviews；
    // compose runtime 由 BOM 的 1.7.4 抬到 1.7.8（glance 的 requirement，同 1.7 线，Gradle 取高）。
    implementation("androidx.glance:glance-appwidget:1.2.0")
    // 小组件兜底刷新：WorkManager 15 分钟周期任务（进程被杀后仍能刷）
    implementation("androidx.work:work-runtime-ktx:2.7.1")

    // 非传递：只取图标 AAR，避免拉到需 compileSdk 36 的 androidx.core 1.17
    implementation("com.github.rikkahub:hugeicons-compose:1.4") {
        isTransitive = false
    }

    // 共享单车出码（DESIGN §3.9 / §4.18）：纯 Java 单 jar，无传递依赖；
    // 只用 core 的 QRCodeWriter，不引 zxing 的 Android 侧模块
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
