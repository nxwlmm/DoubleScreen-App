plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mediaplayer.core.source"
    compileSdk = 34

    defaultConfig {
        // Android 7.0：对齐 FongMi/TV 的底线，覆盖仍在服役的 armeabi-v7a 老盒子
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 库模块不做混淆，交由宿主 App 统一处理（避免 consumer rules 冲突）
        }
    }

    testOptions {
        // 关键：本模块在日志路径上使用了 android.util.Log。
        // 单元测试里的 android.jar 是空壳实现，不开这项开关，任何 Log.x() 调用
        // 都会抛 "Method w in android.util.Log not mocked" 导致测试直接失败。
        // 开启后这些方法返回默认值（0/null），日志在单测中静默丢弃。
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 低端设备上 desugar 可减少 java.time 等 API 的反射开销
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        // ⚠️ 这里曾经写过 "-opt-in=kotlin.RequiresOptIn"，那是错的：
        // kotlin.RequiresOptIn 是用来**定义**标记注解的元注解，它本身不是 opt-in 标记，
        // 编译器会直接报 "Invalid argument for -opt-in"。
        // 本模块没有需要 opt-in 的实验性 API，因此不需要该参数。
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    // 排除模块内用不到的资源，压缩包体
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 对外暴露：上层（app / flavor）需要复用同一 OkHttpClient 与 ViewModel.Factory
    api(libs.okhttp)
    api(libs.androidx.lifecycle.viewmodel)

    implementation(libs.nanohttpd)   // 仅本模块内部使用，不对外暴露

    testImplementation(libs.junit)
    // 不引 kotlinx-coroutines-test：测试里的网络调用被拦截器短路，
    // 用 runBlocking + 真实时间轮询即可，省一个仅测试期的依赖
}
