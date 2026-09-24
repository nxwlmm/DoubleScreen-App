package com.mediaplayer.core.source.data

import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * 源配置的持久化底座。
 *
 * ## 为什么不是 Room
 * 配置列表的数据量上限是**几十条**，且没有查询、关联、迁移需求。
 * Room 会带来注解处理器、sqlite 驱动、编译期代码生成，以及必须维护的
 * Migration 链——对一个 JSON 文件就能解决的场景，这是纯粹的负担。
 *
 * 抽成接口的另一个目的是**可测性**：单元测试注入 [InMemoryStore]，
 * 就能在纯 JVM 下断言"是否真的落盘了"，不需要 Robolectric 或临时文件。
 */
interface SourceStore {

    /** 读取全量内容；文件不存在或内容为空时返回 null。 */
    suspend fun read(): String?

    /** 覆盖写入。实现必须保证**原子性**：要么写成功，要么旧内容完好。 */
    suspend fun write(content: String)

    /** 删除持久化内容。 */
    suspend fun clear()
}

/**
 * 基于 [AtomicFile] 的实现，写入应用私有目录。
 *
 * [AtomicFile] 是 Android 平台自带的能力（API 17+）：写临时文件 → fsync → rename。
 * rename 在同一文件系统内是原子的，因此**断电/进程被杀时不会留下半截 JSON**。
 * 这一点比自己 `File.writeText()` 关键得多——后者写一半被杀，下次启动就解析失败，
 * 用户会以为"所有源都丢了"。
 *
 * 注意：本类不做节流/合并写。调用方（[SourceRepository]）已用 Mutex 串行化，
 * 且写操作只在用户增删改时触发，频率极低。
 */
class AtomicFileStore(file: File) : SourceStore {

    private val atomicFile = AtomicFile(file)

    /** 便于日志排查。 */
    val path: String get() = atomicFile.baseFile.absolutePath

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = atomicFile.readFully()
            if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            null // 首次启动，属于正常路径，不打日志
        } catch (e: IOException) {
            Log.w(TAG, "read failed, treated as empty: ${e.message}")
            null
        }
    }

    override suspend fun write(content: String) = withContext(Dispatchers.IO) {
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
            atomicFile.finishWrite(stream) // 提交：rename 生效，旧内容被替换
        } catch (e: IOException) {
            // failWrite 会删掉临时文件并保留原有内容，不能让异常逃逸到调用方之上
            stream?.let { runCatching { atomicFile.failWrite(it) } }
            Log.e(TAG, "write failed, previous content preserved", e)
            throw e
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { atomicFile.delete() }
        Unit
    }

    private companion object {
        const val TAG = "AtomicFileStore"
    }
}

/**
 * 内存实现，用于单元测试。
 *
 * 额外暴露 [writeCount] 与 [lastWritten]，让测试可以直接断言
 * "无痕模式下一次都没写过盘"这类契约，而不是靠副作用间接推断。
 */
class InMemoryStore(initial: String? = null) : SourceStore {

    private val mutex = Mutex()

    @Volatile
    var writeCount: Int = 0
        private set

    @Volatile
    var clearCount: Int = 0
        private set

    @Volatile
    var lastWritten: String? = null
        private set

    private var content: String? = initial

    /** 当前持久化的内容快照，测试用。 */
    val persisted: String? get() = content

    override suspend fun read(): String? = mutex.withLock { content }

    // 参数名必须与接口 `write(content: String)` 保持一致 ——
    // 否则 Kotlin 会报 "超类型中对应的参数名为 content，用命名参数调用时可能有问题" 的警告。
    // 类里已有同名字段，故用 this.content 显式限定。
    override suspend fun write(content: String) = mutex.withLock {
        this.content = content
        lastWritten = content
        writeCount++
        Unit
    }

    override suspend fun clear() = mutex.withLock {
        content = null
        clearCount++
        Unit
    }
}
