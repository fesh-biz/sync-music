package biz.fesh.syncmusic.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import biz.fesh.syncmusic.shared.ErrorResponse
import biz.fesh.syncmusic.shared.FolderInfo
import biz.fesh.syncmusic.shared.FolderList
import biz.fesh.syncmusic.shared.UploadOffset
import biz.fesh.syncmusic.shared.Manifest
import biz.fesh.syncmusic.shared.ManifestEntry
import biz.fesh.syncmusic.shared.PairRequest
import biz.fesh.syncmusic.shared.PairResponse
import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.shared.ServerInfo
import biz.fesh.syncmusic.shared.SyncProgress
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.RandomAccessFile
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class UnauthorizedException : IOException("Комп'ютер не впізнає цей телефон")
class PairingException(message: String) : IOException(message)
class FileGoneException(path: String) : IOException("Файл зник на комп'ютері: $path")
class StoppedException : IOException("stopped")
class FolderUnavailableException(message: String) : IOException(message)

sealed interface UploadResult {
    data object Complete : UploadResult
    data class Partial(val offset: Long) : UploadResult
    data class Conflict(val offset: Long) : UploadResult
    data object NoSpace : UploadResult
    data class Rejected(val message: String) : UploadResult
}

/** Finds the Wi-Fi (or Ethernet) network so LAN traffic never goes through mobile data or a VPN. */
object WifiNet {
    fun find(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        fun isLan(network: Network): Boolean {
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        cm.activeNetwork?.let { if (isLan(it)) return it }
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { isLan(it) }
    }

    fun broadcastAddresses(context: Context, network: Network?): List<InetAddress> {
        val result = LinkedHashSet<InetAddress>()
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val props = if (network != null) cm?.getLinkProperties(network) else null
            props?.linkAddresses?.forEach { la ->
                val address = la.address
                if (address is Inet4Address) {
                    val ip = address.address
                    val prefix = la.prefixLength
                    val value = ((ip[0].toInt() and 0xff) shl 24) or ((ip[1].toInt() and 0xff) shl 16) or
                        ((ip[2].toInt() and 0xff) shl 8) or (ip[3].toInt() and 0xff)
                    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                    val broadcast = value or mask.inv()
                    val bytes = byteArrayOf(
                        (broadcast ushr 24).toByte(),
                        (broadcast ushr 16).toByte(),
                        (broadcast ushr 8).toByte(),
                        broadcast.toByte(),
                    )
                    result += InetAddress.getByAddress(bytes)
                }
            }
        } catch (_: Throwable) {
        }
        try {
            result += InetAddress.getByName("255.255.255.255")
        } catch (_: Throwable) {
        }
        return result.toList()
    }
}

object Http {
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun client(network: Network?): OkHttpClient =
        if (network == null) base else base.newBuilder().socketFactory(network.socketFactory).build()
}

class Api(
    private val http: OkHttpClient,
    val host: String,
    val port: Int,
    private val token: String? = null,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): HttpUrl.Builder =
        HttpUrl.Builder().scheme("http").host(host).port(port).addPathSegments(path)

    private fun request(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).apply { if (token != null) header("Authorization", "Bearer $token") }

    private fun <T> Response.parse(serializer: KSerializer<T>): T {
        val text = body?.string().orEmpty()
        return Protocol.json.decodeFromString(serializer, text)
    }

    private fun Response.errorText(): String = try {
        Protocol.json.decodeFromString(ErrorResponse.serializer(), body?.string().orEmpty()).error
    } catch (_: Throwable) {
        "HTTP $code"
    }

    private fun Response.check() {
        if (code == 401) throw UnauthorizedException()
        if (!isSuccessful) throw IOException(errorText())
    }

    fun info(): ServerInfo =
        http.newCall(request(url("api/info").build()).build()).execute().use { r ->
            r.check()
            r.parse(ServerInfo.serializer())
        }

    fun pair(pin: String, deviceId: String, deviceName: String): PairResponse {
        val body = Protocol.json.encodeToString(PairRequest.serializer(), PairRequest(pin, deviceId, deviceName)).toRequestBody(jsonType)
        return http.newCall(request(url("api/pair").build()).post(body).build()).execute().use { r ->
            if (r.code == 403) throw PairingException(r.errorText())
            r.check()
            r.parse(PairResponse.serializer())
        }
    }

    fun folders(): List<FolderInfo> =
        http.newCall(request(url("api/folders").build()).build()).execute().use { r ->
            r.check()
            r.parse(FolderList.serializer()).folders
        }

    /** Files of a PC folder. Throws [FolderUnavailableException] when the folder is missing on the PC. */
    fun manifest(folderId: String): Manifest {
        val client = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        val manifestUrl = url("api/manifest").addQueryParameter("folder", folderId).build()
        return client.newCall(request(manifestUrl).build()).execute().use { r ->
            if (r.code == 503 || r.code == 404) throw FolderUnavailableException(r.errorText())
            r.check()
            r.parse(Manifest.serializer())
        }
    }

    fun uploadOffset(folderId: String, path: String, size: Long): Long {
        val offsetUrl = url("api/upload/offset")
            .addQueryParameter("folder", folderId)
            .addQueryParameter("path", path)
            .addQueryParameter("size", size.toString())
            .build()
        return http.newCall(request(offsetUrl).build()).execute().use { r ->
            r.check()
            r.parse(UploadOffset.serializer()).offset
        }
    }

    /**
     * Sends [file] to the PC starting at [offset]. [onProgress] receives how many bytes of the
     * file the PC has so far.
     */
    fun upload(
        folderId: String,
        path: String,
        file: File,
        size: Long,
        modified: Long,
        offset: Long,
        onProgress: (Long) -> Unit,
        isStopped: () -> Boolean,
    ): UploadResult {
        val uploadUrl = url("api/upload")
            .addQueryParameter("folder", folderId)
            .addQueryParameter("path", path)
            .addQueryParameter("size", size.toString())
            .addQueryParameter("modified", modified.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = (size - offset).coerceAtLeast(0)
            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    val buffer = ByteArray(256 * 1024)
                    var remaining = size - offset
                    var sent = offset
                    onProgress(sent)
                    while (remaining > 0) {
                        if (isStopped()) throw StoppedException()
                        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n < 0) throw IOException("файл змінився під час надсилання")
                        sink.write(buffer, 0, n)
                        remaining -= n
                        sent += n
                        onProgress(sent)
                    }
                }
            }
        }
        val client = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        return client.newCall(request(uploadUrl).put(body).build()).execute().use { r ->
            when (r.code) {
                200 -> {
                    val result = r.parse(UploadOffset.serializer())
                    if (result.complete) UploadResult.Complete else UploadResult.Partial(result.offset)
                }
                401 -> throw UnauthorizedException()
                409 -> UploadResult.Conflict(r.parse(UploadOffset.serializer()).offset)
                507 -> UploadResult.NoSpace
                400 -> UploadResult.Rejected(r.errorText())
                403, 404, 503 -> throw FolderUnavailableException(r.errorText())
                else -> throw IOException("HTTP ${r.code}")
            }
        }
    }

    /** Asks the PC to delete a file this phone uploaded earlier. Returns false if the PC kept it. */
    fun deleteRemote(folderId: String, path: String, size: Long): Boolean {
        val deleteUrl = url("api/file")
            .addQueryParameter("folder", folderId)
            .addQueryParameter("path", path)
            .addQueryParameter("size", size.toString())
            .build()
        return http.newCall(request(deleteUrl).delete().build()).execute().use { r ->
            when (r.code) {
                204, 200 -> true
                409, 403 -> false
                401 -> throw UnauthorizedException()
                else -> throw IOException("HTTP ${r.code}")
            }
        }
    }

    fun sendProgress(progress: SyncProgress) {
        val body = Protocol.json.encodeToString(SyncProgress.serializer(), progress).toRequestBody(jsonType)
        val client = http.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
        client.newCall(request(url("api/progress").build()).post(body).build()).execute().use { it.check() }
    }

    /**
     * Downloads [entry] into [part], resuming from what is already there.
     * [onProgress] receives the number of bytes of this file present on disk.
     */
    fun download(folderId: String, entry: ManifestEntry, part: File, onProgress: (Long) -> Unit, isStopped: () -> Boolean) {
        var existing = if (part.isFile) part.length() else 0L
        if (existing > entry.size) {
            part.delete()
            existing = 0
        }
        if (existing == entry.size && entry.size > 0) {
            onProgress(existing)
            return
        }
        val fileUrl = url("api/file").addQueryParameter("folder", folderId).addQueryParameter("path", entry.path).build()
        val builder = request(fileUrl)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        http.newCall(builder.build()).execute().use { r ->
            when (r.code) {
                200 -> existing = 0
                206 -> Unit
                401 -> throw UnauthorizedException()
                404 -> throw FileGoneException(entry.path)
                416 -> {
                    part.delete()
                    throw IOException("Range not satisfiable")
                }
                else -> throw IOException("HTTP ${r.code}")
            }
            val body = r.body ?: throw IOException("Empty body")
            part.parentFile?.mkdirs()
            FileOutputStream(part, existing > 0).use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(256 * 1024)
                var total = existing
                onProgress(total)
                while (true) {
                    if (isStopped()) throw StoppedException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    onProgress(total)
                }
                out.flush()
            }
        }
    }
}
