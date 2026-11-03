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

            // 大文件按 (大小, 修改时间) 判断，不整份读完：比较结果依旧稳定，也不会卡住加载
            if (length > MAX_HASH_BYTES) {
                val statHash = "stat:$length:${key.lastModified}"
                put(key, statHash)
                return@withContext statHash
            }

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
        const val MAX_HASH_BYTES = 32L * 1024 * 1024
    }
}

private suspend fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            // 大文件（<=32MB）也能在页面退出时立刻停下
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
