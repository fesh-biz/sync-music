package biz.fesh.syncmusic.desktop

import biz.fesh.syncmusic.shared.Direction
import biz.fesh.syncmusic.shared.ErrorResponse
import biz.fesh.syncmusic.shared.FolderList
import biz.fesh.syncmusic.shared.PairRequest
import biz.fesh.syncmusic.shared.PairResponse
import biz.fesh.syncmusic.shared.PhoneStorage
import biz.fesh.syncmusic.shared.Protocol
import biz.fesh.syncmusic.shared.SyncProgress
import biz.fesh.syncmusic.shared.UploadOffset
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import io.ktor.server.routing.routing
import java.net.ServerSocket

class SyncServer(private val app: AppController) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start(port: Int) {
        // Fail fast and deterministically if the port is taken.
        ServerSocket(port).close()
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.start(wait = false)
    }

    fun stop() {
        try {
            engine?.stop(300, 1_000)
        } catch (_: Throwable) {
        }
    }

    private suspend fun ApplicationCall.requireDevice(): PairedDevice? {
        val header = request.headers[HttpHeaders.Authorization].orEmpty()
        val token = header.removePrefix("Bearer").trim()
        val device = if (token.isNotEmpty()) app.deviceByToken(token) else null
        if (device == null) {
            respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            return null
        }
        app.touchDevice(device.id)
        return device
    }

    /** The phone -> PC folder named in ?folder=, or null after responding with an error. */
    /** A folder the phone still knows but which was removed from the list on the PC. */
    private suspend fun ApplicationCall.respondFolderRemoved() =
        respond(HttpStatusCode.Gone, ErrorResponse("Папку прибрано на комп'ютері"))

    private suspend fun ApplicationCall.receivingFolder(): FolderRuntime? {
        val runtime = app.folderRuntime(request.queryParameters["folder"] ?: "")
        when {
            runtime == null -> respondFolderRemoved()
            runtime.folder.direction != Direction.TO_PC ->
                respond(HttpStatusCode.Forbidden, ErrorResponse("Папка «${runtime.folder.name}» не приймає файли з телефона"))
            !File(runtime.folder.path).isDirectory ->
                respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Папка «${runtime.folder.name}» на комп'ютері недоступна"))
            else -> return runtime
        }
        return null
    }

    private fun Application.module() {
        install(ContentNegotiation) { json(Protocol.json) }
        install(PartialContent)
        install(AutoHeadResponse)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                AppLog.w("Request ${call.request.local.uri} failed", cause)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "error"))
            }
        }

        routing {
            get("/") {
                call.respondText(landingPage(), ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }

            get("/app.apk") {
                val bytes = ApkResource.bytes
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("APK is not bundled into this build"))
                    return@get
                }
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "SyncMusic.apk").toString(),
                )
                call.respondBytes(bytes, ContentType.parse("application/vnd.android.package-archive"))
            }

            get("/api/info") {
                call.respond(app.serverInfo())
            }

            post("/api/pair") {
                val request = call.receive<PairRequest>()
                when (val result = app.pair(request)) {
                    is PairResult.Ok -> call.respond(PairResponse(result.token, app.settings.value.serverId, app.computerName))
                    PairResult.WrongPin -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("Невірний код"))
                }
            }

            get("/api/folders") {
                val device = call.requireDevice() ?: return@get
                call.respond(FolderList(app.folderInfos(device.id)))
            }

            // Storages and folders of the phone, for choosing the phone folder in the PC app.
            post("/api/phone/storage") {
                val device = call.requireDevice() ?: return@post
                val storage = call.receive<PhoneStorage>()
                app.onPhoneStorage(device.id, storage)
                call.respond(HttpStatusCode.NoContent)
            }

            // Without ?folder= (version 1 phones) the first PC -> phone folder is used.
            get("/api/manifest") {
                call.requireDevice() ?: return@get
                val folderId = call.request.queryParameters["folder"]
                val runtime = app.folderRuntime(folderId)
                if (runtime == null) {
                    if (folderId != null) call.respondFolderRemoved()
                    else call.respond(HttpStatusCode.NotFound, ErrorResponse("Папку не знайдено"))
                    return@get
                }
                if (!File(runtime.folder.path).isDirectory) {
                    // Never hand out an empty list because of a missing folder: the phone would delete everything.
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Папка «${runtime.folder.name}» на комп'ютері недоступна"))
                    return@get
                }
                call.respond(runtime.library.manifest(app.settings.value.serverId))
            }

            get("/api/file") {
                call.requireDevice() ?: return@get
                val folderId = call.request.queryParameters["folder"]
                val runtime = app.folderRuntime(folderId)
                if (runtime == null && folderId != null) {
                    // Stops a sync that was running when the folder was removed (newer phones stop at once).
                    call.respondFolderRemoved()
                    return@get
                }
                val path = call.request.queryParameters["path"]
                val file = if (runtime != null && path != null && runtime.folder.direction == Direction.TO_PHONE) {
                    runtime.library.fileFor(path)
                } else {
                    null
                }
                if (file == null || !file.isFile) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
                    return@get
                }
                call.request.headers[HttpHeaders.Range]?.let { range -> AppLog.i("Resume $path from $range") }
                call.respond(LocalFileContent(file, ContentType.Application.OctetStream))
            }

            get("/api/upload/offset") {
                call.requireDevice() ?: return@get
                val runtime = call.receivingFolder() ?: return@get
                val path = call.request.queryParameters["path"].orEmpty()
                val size = call.request.queryParameters["size"]?.toLongOrNull() ?: 0L
                val offset = withContext(Dispatchers.IO) { Transfers.offset(File(runtime.folder.path), path, size) }
                call.respond(UploadOffset(offset))
            }

            put("/api/upload") {
                call.requireDevice() ?: return@put
                val runtime = call.receivingFolder() ?: return@put
                val params = call.request.queryParameters
                val path = params["path"].orEmpty()
                val size = params["size"]?.toLongOrNull()
                val modified = params["modified"]?.toLongOrNull() ?: 0L
                val offset = params["offset"]?.toLongOrNull() ?: 0L
                if (size == null || size < 0 || offset < 0 || offset > size) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad parameters"))
                    return@put
                }
                if (offset > 0) AppLog.i("Upload resume $path from $offset")
                val root = File(runtime.folder.path)
                val outcome = Transfers.lockFor(runtime.folder.id + "/" + path).withLock {
                    withContext(Dispatchers.IO) {
                        call.receiveChannel().toInputStream().use { input ->
                            Transfers.receive(root, path, size, modified, offset, input)
                        }
                    }
                }
                when (outcome) {
                    UploadOutcome.Done -> {
                        runtime.library.invalidate()
                        call.respond(UploadOffset(size, complete = true))
                    }
                    is UploadOutcome.Partial -> call.respond(UploadOffset(outcome.received))
                    is UploadOutcome.Conflict -> call.respond(HttpStatusCode.Conflict, UploadOffset(outcome.have))
                    UploadOutcome.NoSpace -> call.respond(HttpStatusCode.InsufficientStorage, ErrorResponse("На комп'ютері недостатньо місця"))
                    is UploadOutcome.Rejected -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message))
                }
            }

            // Files on the PC are never deleted by the phone. Older phone versions may still ask:
            // they get 403 and keep going (they treat it as "the PC kept the file").
            delete("/api/file") {
                call.requireDevice() ?: return@delete
                AppLog.i("Refused to delete ${call.request.queryParameters["path"]} on the PC (deleting on the PC is disabled)")
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Видалення файлів на комп'ютері вимкнене"))
            }

            post("/api/progress") {
                val device = call.requireDevice() ?: return@post
                val progress = call.receive<SyncProgress>()
                app.onProgress(device.id, progress)
                call.respond(HttpStatusCode.NoContent)
            }

            // Used by a second launch of the app to bring the existing window to front.
            post("/local/show") {
                val remote = call.request.local.remoteAddress.removePrefix("/")
                val loopback = remote == "localhost" || try {
                    java.net.InetAddress.getByName(remote).isLoopbackAddress
                } catch (_: Throwable) {
                    false
                }
                if (loopback) {
                    app.requestShowWindow()
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
                }
            }
        }
    }

    private fun landingPage(): String {
        val hasApk = ApkResource.available
        val button = if (hasApk) {
            """<a class="btn" href="/app.apk">Завантажити застосунок для Android</a>
               <p class="hint">Після завантаження відкрийте файл і дозвольте встановлення з цього джерела.</p>"""
        } else {
            """<p class="hint">У цю збірку не вкладено APK. Завантажте його зі сторінки релізів на GitHub.</p>"""
        }
        return """
            <!doctype html>
            <html lang="uk"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Sync Music</title>
            <style>
              body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;padding:32px 20px;background:#f4f5fb;color:#1c1b29;text-align:center}
              .card{max-width:420px;margin:0 auto;background:#fff;border-radius:20px;padding:28px 22px;box-shadow:0 6px 24px rgba(40,40,90,.08)}
              h1{font-size:24px;margin:0 0 8px} p{line-height:1.5}
              .btn{display:block;margin:22px 0 10px;padding:16px;border-radius:14px;background:#4f46e5;color:#fff;text-decoration:none;font-weight:600;font-size:17px}
              .hint{color:#5f5d73;font-size:14px}
            </style></head>
            <body><div class="card">
              <h1>Sync Music</h1>
              <p>Комп'ютер «${app.computerName}» готовий синхронізувати музику з телефоном.</p>
              $button
            </div></body></html>
        """.trimIndent()
    }
}
