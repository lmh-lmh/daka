plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.daka.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.daka.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 38
        versionName = "4.1-submenu"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 自用软件，沿用 debug 签名，免去配置 keystore
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // EXIF 读写（平台版 android.media.ExifInterface 写 GPS 能力弱）
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 备份/恢复选目录
    implementation("androidx.documentfile:documentfile:1.0.1")
    // 地图引擎（原工程已验证可用）
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
