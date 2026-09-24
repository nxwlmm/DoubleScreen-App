// 顶层构建脚本：只声明插件、不 apply，由子模块按需启用
// 显式声明 application 插件可以固定版本，避免子模块各自解析出不同版本
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
