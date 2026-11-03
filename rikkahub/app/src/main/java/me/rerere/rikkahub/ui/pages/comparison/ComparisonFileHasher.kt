package me.rerere.rikkahub.ui.pages.comparison

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.comparison.FileHasher
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 按文件内容给本地附件算 SHA-256。
 *
 * 只处理 `file://`：网络图片、`content://` 之类交给调用方退回 URL 比较。同一批对比里同一张图会被反复指纹
 * 到，所以按 (规范路径, 大小, 修改时间) 缓存；读不到的也缓存，避免对着一堆坏链接反复开文件。
 */
class ComparisonFileHasher : FileHasher {
    private data class CacheKey(val path: String, val length: Long, val lastModified: Long)

    private class CacheEntry(val hash: String?)

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    override suspend fun hash(url: String): String? {
        if (!url.startsWith("file://")) return null
        return withContext(Dispatchers.IO) {
            val file = fileOf(url) ?: return@withContext null
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.path)
            val key = CacheKey(
                path = canonical,
                length = file.length(),
                lastModified = file.lastModified(),
            )
            cache[key]?.let { return@withContext it.hash }

            val hash = runCatching { file.sha256() }.getOrNull()
            cache[key] = CacheEntry(hash)
            hash
        }
    }

    private fun fileOf(url: String): File? {
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: return null
        return File(path)
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
