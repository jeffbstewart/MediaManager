package net.stewart.mediamanager.tv.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import net.stewart.mediamanager.grpc.ImageRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Two-tier image cache: in-memory LRU (200 bitmaps) + disk LRU (500 files).
 * Cache key is a SHA-256 hash of the ImageRef fields.
 */
class ImageDiskCache(context: Context) {
    private val cacheDir = File(context.cacheDir, "grpc_images").also { it.mkdirs() }
    private val manifestFile = File(cacheDir, "manifest.json")

    private val memoryCache = LruCache<String, Bitmap>(200)

    private data class Entry(val etag: String, val contentType: String, val fileName: String)

    private val manifest = LinkedHashMap<String, Entry>(512, 0.75f, true) // access-order
    private val maxEntries = 500

    init {
        loadManifest()
    }

    /** Get from memory, then disk. Returns (bitmap, etag) or null. */
    fun get(ref: ImageRef): Pair<Bitmap, String>? {
        val key = cacheKey(ref)
        memoryCache.get(key)?.let { return it to (manifest[key]?.etag ?: "") }

        val entry = manifest[key] ?: return null
        val file = File(cacheDir, entry.fileName)
        if (!file.exists()) {
            manifest.remove(key)
            return null
        }
        val bitmap = BitmapFactory.decodeFile(file.path) ?: run {
            manifest.remove(key); file.delete(); return null
        }
        memoryCache.put(key, bitmap)
        return bitmap to entry.etag
    }

    /** Quick memory-only lookup for synchronous composable init. */
    fun getFromMemory(ref: ImageRef): Bitmap? {
        return memoryCache.get(cacheKey(ref))
    }

    fun etag(ref: ImageRef): String? = manifest[cacheKey(ref)]?.etag

    fun store(ref: ImageRef, data: ByteArray, contentType: String, etag: String) {
        val key = cacheKey(ref)
        val file = File(cacheDir, key)
        file.writeBytes(data)
        manifest[key] = Entry(etag, contentType, key)

        BitmapFactory.decodeByteArray(data, 0, data.size)?.let { memoryCache.put(key, it) }

        evictIfNeeded()
        saveManifest()
    }

    fun remove(ref: ImageRef) {
        val key = cacheKey(ref)
        manifest.remove(key)?.let { File(cacheDir, it.fileName).delete() }
        memoryCache.remove(key)
        saveManifest()
    }

    private fun cacheKey(ref: ImageRef): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ref.type.number.toByte())
        md.update(ref.titleId.toBigInteger().toByteArray())
        md.update(ref.tmdbPersonId.toBigInteger().toByteArray())
        md.update(ref.tmdbCollectionId.toBigInteger().toByteArray())
        md.update(ref.uuid.toByteArray())
        md.update(ref.cameraId.toBigInteger().toByteArray())
        if (ref.hasTmdbMedia()) {
            md.update(ref.tmdbMedia.tmdbId.toBigInteger().toByteArray())
            md.update(ref.tmdbMedia.mediaType.number.toByte())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun evictIfNeeded() {
        while (manifest.size > maxEntries) {
            val oldest = manifest.keys.firstOrNull() ?: break
            manifest.remove(oldest)?.let { File(cacheDir, it.fileName).delete() }
            memoryCache.remove(oldest)
        }
    }

    private fun saveManifest() {
        val arr = JSONArray()
        manifest.forEach { (key, entry) ->
            arr.put(JSONObject().apply {
                put("key", key)
                put("etag", entry.etag)
                put("contentType", entry.contentType)
                put("fileName", entry.fileName)
            })
        }
        manifestFile.writeText(arr.toString())
    }

    private fun loadManifest() {
        if (!manifestFile.exists()) return
        try {
            val arr = JSONArray(manifestFile.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.getString("key")
                val fileName = obj.getString("fileName")
                if (File(cacheDir, fileName).exists()) {
                    manifest[key] = Entry(
                        obj.getString("etag"),
                        obj.optString("contentType", "image/jpeg"),
                        fileName
                    )
                }
            }
        } catch (_: Exception) {
            // Corrupt manifest — start fresh
            manifest.clear()
        }
    }
}
