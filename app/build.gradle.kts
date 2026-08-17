import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 读取 local.properties 中的签名配置（不提交，已被 .gitignore 排除）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
val keystorePath = localProps.getProperty("KEYSTORE_PATH")
val keystorePw = localProps.getProperty("KEYSTORE_PASSWORD")
val keyAliasName = localProps.getProperty("KEY_ALIAS")
val keyPw = localProps.getProperty("KEY_PASSWORD")

android {
    namespace = "com.answersearcher.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.answersearcher.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "LV1.2"
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystorePw
                keyAlias = keyAliasName
                keyPassword = keyPw
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    // 启用 ViewBinding
    buildFeatures {
        viewBinding = true
    }
    // 避免 POI 的重复类冲突
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt"
            )
        }
    }
}

dependencies {
    // Android 基础
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ML Kit 中文文字识别 (on-device, 低延迟)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // 题库为内置 CSV，使用轻量 CSV 解析，无需 Apache POI（避免 MethodHandle/minSdk 冲突）
    // 已移除豆包 AI 联网兜底，不再需要 OkHttp / Gson 依赖
}
