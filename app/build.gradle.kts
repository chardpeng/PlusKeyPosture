plugins {
    // AGP 9.0 内置 Kotlin 支持，无需再声明 org.jetbrains.kotlin.android
    id("com.android.application")
}

android {
    namespace = "com.cong.pluskeyposture"
    // 本机 SDK 只装了 android-37.0，因此 compileSdk 用 37
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.cong.pluskeyposture"
        minSdk = 33          // ColorOS 15 / Android 13+，一加15 出厂 Android 15
        targetSdk = 34
        versionCode = 17
        versionName = "1.7.0"
    }

    // 模块本体是系统进程里的 hook，不做混淆
    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug key 签名，方便 LSPosed 直接加载；正式发布请换自己的 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // Xposed API：只在编译期可见，运行时由 LSPosed 框架提供，绝不能用 implementation。
    // 该 API 不在 Maven Central / Google Maven 上，因此这里用本地 jar（已放在 app/libs/）。
    // 来源：https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
    compileOnly(files("libs/xposed-api-82.jar"))

    // 设置界面改用 framework 内置的 android.preference.PreferenceActivity，
    // 因此不再需要 androidx.appcompat / androidx.core —— 砍掉后 APK 体积大幅下降。
}
