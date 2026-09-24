pluginManagement {
    repositories {
        google()
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

rootProject.name = "DoubleScreen"

// 共用业务层：本模块不感知设备形态（leanback / mobile flavor 均依赖它）
include(":core-source")
// 壳工程：双 flavor 产出电视版 / 手机版两个 APK
include(":app")
// include(":player")       // 播放抽象层，后续阶段接入
