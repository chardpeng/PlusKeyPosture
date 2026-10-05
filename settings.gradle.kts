// 插件与依赖仓库配置。
//
// 关键点：Android Gradle Plugin (AGP) 不在 Gradle 官方插件仓库里，
// 而在 Google Maven 仓库。因此必须在 pluginManagement 里显式加 google()，
// 否则会报 "Plugin [id: 'com.android.application'] was not found"。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PlusKeyPosture"
include(":app")
