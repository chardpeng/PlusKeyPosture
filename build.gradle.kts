// 顶层构建脚本
//
// 环境说明（本机实测）：
//   - Android Studio 2026.1.4，捆绑 JDK 25
//   - Android SDK: platforms/android-37.0, build-tools/36.0.0
//   - JDK 25 需要 Gradle 9.1+，因此这里用 AGP 9.0.0 + Gradle 9.1
//
// 注意：AGP 9.0 起内置 Kotlin 支持，不再需要单独声明 kotlin-android 插件，
// 声明了反而会报 "Cannot add extension with name 'kotlin'"。
plugins {
    id("com.android.application") version "9.0.0" apply false
}
