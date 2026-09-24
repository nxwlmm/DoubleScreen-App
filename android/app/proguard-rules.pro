# ============================================================================
#  DoubleScreen · Release 混淆规则（R8）
#
#  为什么需要这份文件：
#  AGP 8.x 默认启用 R8 **full mode**，它比旧的 compat mode 激进得多 ——
#  会做类合并、更彻底的成员裁剪，并**假设"代码里没有反射"**。
#  低端老设备上 Release 闪退，绝大多数不是"混淆把类名改错了"，
#  而是下面三类：
#    1. 只被反射/字符串引用到的类被整类裁剪 → NoClassDefFoundError
#    2. 枚举被去优化（unboxing）成 int 常量 → 反射调 valueOf 时崩
#    3. 泛型签名（Signature）被剥离 → 任何依赖它的序列化/反射失败
#  因此规则的重点不是"防止改名"，而是"防止被删"和"保留元数据"。
#
#  与 takagen99/Box 的教训对应：
#  Box 曾因 AGP 升级后 proguard 规则未同步验证，导致 release 构建出问题只能回退版本。
#  本文件的每一条 keep 都对应一个真实会被裁掉的东西，不是保险起见全保留。
# ============================================================================

# ---------------------------------------------------------------------------
# 0. 全局属性：元数据比类名重要
# ---------------------------------------------------------------------------
# Signature 是最容易被忽略的一条：没有它，泛型信息丢失，
# StateFlow<T>、List<SourceEntry> 这类在反射场景下会变成裸类型。
-keepattributes Signature
-keepattributes InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes SourceFile, LineNumberTable

# 把堆栈里的源文件名抹成常量，但保留行号 —— 线上崩溃日志还能定位到行
-renamesourcefileattribute SourceFile


# ---------------------------------------------------------------------------
# 1. NanoHTTPD —— 局域网投源服务的底层
# ---------------------------------------------------------------------------
# 它内部通过**类名字符串**查找 Handler 与临时文件管理器工厂，
# 并在 socket 层做反射式的连接处理。这些引用 R8 静态分析看不到。
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }
-dontwarn fi.iki.elonen.**
-dontwarn org.nanohttpd.**

# ClientHandler / AsyncRunner 会被线程池以 Runnable 形式持有，
# 成员名被改无妨，但类本身与方法签名必须保留（我们自定义了 FixedPoolAsyncRunner）
-keepclassmembers class * implements fi.iki.elonen.NanoHTTPD$AsyncRunner { *; }


# ---------------------------------------------------------------------------
# 2. OkHttp / Okio —— 源探测与故障轮换的网络层
# ---------------------------------------------------------------------------
# OkHttp 自带 consumer rules，但它对**自定义拦截器**的保护依赖
# "实现类被直接引用"。我们的 FakeBackend（测试用）与未来可能加的
# 日志/鉴权拦截器都是通过 addInterceptor 注册的实例，一旦被裁就静默失效。
-keepclassmembers class * implements okhttp3.Interceptor {
    <methods>;
}
-keep class * implements okhttp3.Interceptor { *; }

# WebSocket 契约类：OkHttp 内部用反射构造监听器（当前未用，但监听器接口一旦引入即是坑）
-keep class okhttp3.WebSocketListener { *; }
-keep class okhttp3.WebSocket { *; }
-keepclassmembers class * extends okhttp3.WebSocketListener { *; }

# OkHttp 的可选平台适配（缺失只影响性能，不影响功能，避免 R8 因缺失引用而报错）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**


# ---------------------------------------------------------------------------
# 3. ZXing —— 投源面板的二维码生成
# ---------------------------------------------------------------------------
# QRCodeWriter 内部用 **反射查表** 处理编码模式与纠错级别，
# 并且 EncodeHintType 是以 Map 形式传入的（key 为枚举实例）。
# R8 若把枚举去优化或用不到的编码器裁掉，运行时就是
# "找不到编码器" 或 "IllegalArgumentException: No encoder"。
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**


# ---------------------------------------------------------------------------
# 4. 数据实体与 UI 状态 —— 跨模块契约
# ---------------------------------------------------------------------------
# 这些类通过 StateFlow 在 core-source 与 app 之间流转，并且：
#   · SourceEntry / SourceUiState 是 data class → copy() / componentN() 可能被反射使用
#   · SourceKind / PingLevel / SourceStatus 是枚举 → JSON 里按 name() 存储与还原
#     （SourceJsonCodec 用 SourceKind.valueOf(...)，枚举值被裁掉就会崩在解析上）
# 同时它们被 app 模块直接引用，跨模块时 R8 的可见性分析更保守但更易出错，
# 显式 keep 掉这层契约成本极低（几十个类），收益是永远不会出现
# "Release 版一打开配置列表就闪退"。
-keep class com.mediaplayer.core.source.model.** { *; }
-keep class com.mediaplayer.core.source.ui.** { *; }

# 枚举的 values() / valueOf() —— 这是 Release 崩溃的经典来源
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ---------------------------------------------------------------------------
# 5. Android 组件与框架契约
# ---------------------------------------------------------------------------
# Activity / Application 由系统按清单里的**类名字符串**实例化，
# 被改名或裁掉就是"点图标闪退"。AGP 有默认规则，这里显式声明避免版本差异。
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends androidx.fragment.app.Fragment

# ViewModel：通过 ViewModelProvider.Factory 创建。
# 我们的 SourceViewModel.Factory 是显式 new，但父类 ViewModel 的
# 无参构造被系统在部分路径下反射调用，保留构造器即可。
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ViewBinding：AGP 生成的绑定类通过静态 inflate/bind 使用，
# 虽然都是直接调用，但保留签名可以避免 R8 在类合并时把
# 不同布局的同名绑定类错误合并。
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(android.view.LayoutInflater);
    public static *** bind(android.view.View);
}
-keepclassmembers class * extends androidx.viewbinding.ViewBinding {
    <init>(...);
}

# 自定义 View / DialogFragment 的恢复依赖无参构造
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}


# ---------------------------------------------------------------------------
# 6. Kotlin / 协程
# ---------------------------------------------------------------------------
# 协程的状态机与 DebugProbes 依赖字段名与反射式探测
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlinx.coroutines.**

# DebugProbes 的产物已在 build.gradle.kts 的 packaging 里排除，
# 这里再 dotwarn 一次，避免某些 AGP 版本仍尝试解析它
-dontwarn kotlinx.coroutines.debug.**

# Kotlin 反射（Intrinsics 的 null 检查依赖它）
-keep class kotlin.jvm.internal.** { *; }


# ---------------------------------------------------------------------------
# 7. 保留行号与调试信息（Release 也要能看堆栈）
# ---------------------------------------------------------------------------
# 注意：不做 -keep class com.mediaplayer.** 这种全保留 ——
# 那等于关掉混淆，APK 体积与 dex 方法数都会失控。
# 只保留"反射会碰到的"部分，业务逻辑照常混淆。
