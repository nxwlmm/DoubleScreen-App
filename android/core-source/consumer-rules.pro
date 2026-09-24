# core-source 消费方混淆规则
# 由本模块的调用方（app）合并执行。

# NanoHTTPD 内部通过类名字符串做扩展点查找，保留全部成员
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }

# 本模块的状态类被 Flow / 反射式序列化（如 JSON 日志）间接引用
-keep class com.mediaplayer.core.source.model.** { *; }

# OkHttp 在 Android 上的可选平台适配（缺失只影响性能，不影响功能）
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
