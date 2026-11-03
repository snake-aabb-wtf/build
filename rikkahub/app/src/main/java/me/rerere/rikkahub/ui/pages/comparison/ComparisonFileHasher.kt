package me.rerere.rikkahub.ui.pages.comparison

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.comparison.FileHasher
import java.io.File
import java.security.MessageDigest

/**
 * 按文件内容给本地附件算 SHA-256。
 *
 * 只处理 `file://`：网络图片、`content://` 之类交给调用方退回 URL 比较。同一批对比里同一张图会被反复指纹
 * 到，所以按 (规范路径, 大小, 修改时间) 缓存；读不到的也缓存，避免对着一堆坏链接反复开文件。
 */
class ComparisonFileHasher : FileHasher {
    private data class CacheKey(val path: String, val length: Long, val lastModified: Long)

    /** 缓存里允许有「读不到」的 null，和「没缓存过」区分开。 */
    private class CacheEntry(val hash: String?)

    /** 简单的 LRU：超过 [MAX_CACHE_ENTRIES] 条后丢掉最久未用的，长会话不会把内存吃满。 */
    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    private val lock = Any()

    override suspend fun hash(url: String): String? {
        if (!url.startsWith("file://")) return null
        return withContext(Dispatchers.IO) {
            val file = fileOf(url) ?: return@withContext null
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.path)
            val length = file.length()
            val key = CacheKey(
                path = canonical,
                length = length,
                lastModified = file.lastModified(),
            )
            cached(key)?.let { return@withContext it.hash }

            // 不论大小都按内容哈希：大小/修改时间只是缓存键，不能冒充内容哈希，否则两个
            // 不同文件可能因为在同一毫秒被写成同样大小而被误合并。大文件只影响耗时。
            val hash = try {
                file.sha256()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            put(key, hash)
            hash
        }
    }

    private fun cached(key: CacheKey): CacheEntry? = synchronized(lock) { cache[key] }

    private fun put(key: CacheKey, value: String?) {
        synchronized(lock) { cache[key] = CacheEntry(value) }
    }

    private fun fileOf(url: String): File? {
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: return null
        return File(path)
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 512
    }
}

/** 8KB 读缓冲：大文件也不会一次读进内存。 */
private const val HASH_BUFFER_SIZE = 8 * 1024

private suspend fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        while (true) {
            // 每个分块都检查取消：几 GB 的附件也能在页面退出时立刻停下
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
