plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mediaplayer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mediaplayer.app"
        minSdk = 24          // Android 7.0，覆盖仍在服役的 armeabi-v7a 老盒子
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    /**
     * 双形态产物：单一代码库产出两个 APK。
     * - `:app:assembleLeanbackRelease` → 电视版
     * - `:app:assembleMobileRelease`   → 手机版
     *
     * flavor 的源集目录约定为 `src/<flavor>/`，因此 UI 天然隔离：
     * `src/leanback` 只放遥控器焦点体系，`src/mobile` 只放触屏手势体系，
     * 两者都只依赖 `src/main` 的共用业务层，互不可见。
     */
    flavorDimensions += "form"
    productFlavors {
        create("leanback") {
            dimension = "form"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
            // 电视端不声明触摸屏特性，避免被手机应用市场误判
            manifestPlaceholders["usesTouchscreen"] = "false"
            manifestPlaceholders["usesLeanback"] = "true"
        }
        create("mobile") {
            dimension = "form"
            manifestPlaceholders["usesTouchscreen"] = "true"
            manifestPlaceholders["usesLeanback"] = "false"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation(project(":core-source"))     // 共用业务层：ViewModel / Repository / 引擎

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // `by viewModels()` / `by activityViewModels()` 的宿主。appcompat 不会传递引入它们
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)

    implementation(libs.zxing.core)              // 局域网投源面板的二维码
}
