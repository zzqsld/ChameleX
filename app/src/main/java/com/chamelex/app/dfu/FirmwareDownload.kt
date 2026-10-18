package com.chamelex.app.dfu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub release 信息。 */
data class FirmwareRelease(
    val tagName: String,
    val publishedAt: String,
    val body: String,
    val assets: List<FirmwareAsset>,
)

data class FirmwareAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
)

private const val RELEASE_API =
    "https://api.github.com/repos/RfidResearchGroup/ChameleonUltra/releases/latest"

/** 获取官方最新 release（GitHub API 要求 User-Agent）。 */
suspend fun fetchLatestRelease(): FirmwareRelease = withContext(Dispatchers.IO) {
    val conn = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
        setRequestProperty("User-Agent", "ChameleX-Android")
        setRequestProperty("Accept", "application/vnd.github+json")
        connectTimeout = 15_000
        readTimeout = 15_000
    }
    try {
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val assets = mutableListOf<FirmwareAsset>()
        val arr = json.getJSONArray("assets")
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            assets.add(
                FirmwareAsset(
                    name = a.getString("name"),
                    downloadUrl = a.getString("browser_download_url"),
                    size = a.getLong("size"),
                )
            )
        }
        FirmwareRelease(
            tagName = json.getString("tag_name"),
            publishedAt = json.optString("published_at"),
            body = json.optString("body"),
            assets = assets,
        )
    } finally {
        conn.disconnect()
    }
}

/**
 * 下载固件文件（跟随重定向，可经协程取消）。
 *
 * @param onProgress (已下载字节, 总字节(-1=未知))
 */
suspend fun downloadFirmware(
    asset: FirmwareAsset,
    dest: File,
    onProgress: suspend (downloaded: Long, total: Long) -> Unit,
) = withContext(Dispatchers.IO) {
    val conn = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
        setRequestProperty("User-Agent", "ChameleX-Android")
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
    }
    val tmp = File(dest.parentFile, dest.name + ".part")
    try {
        val total = conn.contentLengthLong.let { if (it > 0) it else asset.size }
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var downloaded = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    downloaded += n
                    onProgress(downloaded, total)
                }
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    } catch (e: Exception) {
        tmp.delete()
        throw e
    } finally {
        conn.disconnect()
    }
}

/** 基本校验：是 zip 且含 DFU manifest.json。 */
fun isValidDfuZip(file: File): Boolean = runCatching {
    java.util.zip.ZipFile(file).use { zip ->
        zip.getEntry("manifest.json") != null
    }
}.getOrDefault(false)
