package com.payatu.flutterinspect.server

import android.content.Context
import com.payatu.flutterinspect.data.models.HookTargetConfig
import com.payatu.flutterinspect.data.shell.RootShell
import com.payatu.flutterinspect.data.symbols.SymbolManager
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Duration

/**
 * Embedded Ktor/CIO Web Server providing HTTP REST API and real-time WebSocket
 * log streaming for workstation desktop browsers on port 8088.
 */
object WebTerminalServer {

    private const val PORT = 8088
    private var engine: ApplicationEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private var cachedHtml: String? = null
    private lateinit var appContext: Context

    fun getPort(): Int = PORT

    fun start(context: Context): Result<Unit> {
        if (_isRunning.value) return Result.success(Unit)

        appContext = context.applicationContext

        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")

        return try {
            // Pre-load web app HTML from assets
            cachedHtml = try {
                context.assets.open("web/index.html").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                null
            }

            val server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(15)
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                install(CORS) {
                    anyHost()
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Authorization)
                    allowHeader("X-Package-Name")
                    allowMethod(HttpMethod.Options)
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Put)
                    allowMethod(HttpMethod.Delete)
                }

                routing {
                    // Serve Web Terminal Single-Page Application
                    get("/") {
                        val html = cachedHtml ?: run {
                            try {
                                context.assets.open("web/index.html").bufferedReader().use { it.readText() }
                            } catch (_: Exception) {
                                "<html><body><h3>FlutterInspect Web Terminal</h3><p>assets/web/index.html missing.</p></body></html>"
                            }
                        }
                        call.respondText(html, ContentType.Text.Html)
                    }

                    // Serve Favicon & Web static assets
                    get("/favicon.svg") {
                        try {
                            val bytes = context.assets.open("web/favicon.svg").use { it.readBytes() }
                            call.respondBytes(bytes, ContentType.parse("image/svg+xml"))
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }

                    // REST APIs
                    route("/api") {
                        // 1. Status & Active State
                        get("/status") {
                            val root = JSONObject()
                            root.put("target", TraceLogBroadcaster.activeTarget.value)
                            root.put("isHookRunning", TraceLogBroadcaster.isHookRunning.value)
                            root.put("engine", TraceLogBroadcaster.activeEngine.value)
                            root.put("symbolsLoaded", SymbolManager.isLoaded.value)
                            root.put("functionsCount", SymbolManager.functionsCount)
                            root.put("classesCount", SymbolManager.classesCount)
                            root.put("symbolStats", SymbolManager.symbolStats.value ?: "Not loaded")
                            root.put("port", PORT)
                            root.put("connectedClients", _connectedClients.value)

                            val hooksArr = JSONArray()
                            TraceLogBroadcaster.activeHooks.value.forEach { h ->
                                val hObj = JSONObject()
                                hObj.put("offset", String.format("0x%x", h.offset))
                                hObj.put("name", h.name)
                                hObj.put("params", JSONArray(h.params))
                                hooksArr.put(hObj)
                            }
                            root.put("activeHooks", hooksArr)

                            call.respondText(root.toString(), ContentType.Application.Json)
                        }

                        // 2. Query Symbols / Functions
                        get("/functions") {
                            val query = call.parameters["q"] ?: ""
                            val limit = call.parameters["limit"]?.toIntOrNull() ?: 1500
                            val functions = if (query.isBlank()) {
                                SymbolManager.getAllFunctions(limit)
                            } else {
                                SymbolManager.searchFunctions(query, limit)
                            }

                            val arr = JSONArray()
                            functions.forEach { f ->
                                val fObj = JSONObject()
                                fObj.put("offset", f.offset)
                                fObj.put("name", f.name)
                                fObj.put("className", f.className)
                                fObj.put("method", f.method)
                                fObj.put("lib", f.lib)

                                val pArr = JSONArray()
                                f.params.forEach { p ->
                                    val pObj = JSONObject()
                                    pObj.put("idx", p.idx)
                                    pObj.put("name", p.name)
                                    pObj.put("type", p.type)
                                    pObj.put("reg", p.reg)
                                    pObj.put("slot", p.slot)
                                    pArr.put(pObj)
                                }
                                fObj.put("params", pArr)
                                arr.put(fObj)
                            }

                            call.respondText(arr.toString(), ContentType.Application.Json)
                        }

                        // 2b. Dart VM Snapshot Info & Discovered Packages
                        get("/snapshot") {
                            val pkg = call.parameters["pkg"]
                                ?: call.request.headers["X-Package-Name"]
                                ?: TraceLogBroadcaster.activeTarget.value

                            var snap = SymbolManager.activeSnapshot.value
                            if (snap == null || !snap.valid || (pkg.isNotBlank() && SymbolManager.loadedPackage.value != pkg)) {
                                val scanRes = RootShell.loadScanResult(pkg).getOrNull()
                                if (scanRes?.snapshot != null && scanRes.snapshot.valid) {
                                    snap = scanRes.snapshot
                                    SymbolManager.setActiveSnapshot(snap)
                                }
                            }

                            val root = JSONObject()
                            if (snap != null && snap.valid) {
                                root.put("valid", true)
                                root.put("package", pkg)
                                root.put("magic", snap.magic)
                                root.put("kind", snap.kind)
                                root.put("hash", snap.hash)
                                root.put("features", snap.features)
                                root.put("numClusters", snap.numClusters)
                                root.put("numObjects", snap.numObjects)
                                root.put("numBaseObjects", snap.numBaseObjects)
                                root.put("instructionsOffset", snap.instructionsOffset)
                                root.put("isolateDataAddr", snap.isolateDataAddr)
                                root.put("isolateInstructionsAddr", snap.isolateInstructionsAddr)
                                root.put("symbolsCount", snap.symbolsCount)
                                root.put("symbols", JSONArray(snap.symbols))
                            } else {
                                root.put("valid", false)
                                root.put("package", pkg)
                                root.put("message", "No snapshot metadata found for '$pkg'. Run a discovery scan or restart target app.")
                            }
                            call.respondText(root.toString(), ContentType.Application.Json)
                        }

                        // 3. Active Hooks management
                        get("/hooks") {
                            val arr = JSONArray()
                            TraceLogBroadcaster.activeHooks.value.forEach { h ->
                                val hObj = JSONObject()
                                hObj.put("offset", String.format("0x%x", h.offset))
                                hObj.put("name", h.name)
                                hObj.put("params", JSONArray(h.params))
                                arr.put(hObj)
                            }
                            call.respondText(arr.toString(), ContentType.Application.Json)
                        }

                        post("/hooks") {
                            val body = call.receiveText()
                            try {
                                val json = JSONObject(body)
                                val action = json.optString("action", "add")
                                val targetProc = TraceLogBroadcaster.activeTarget.value

                                if (action == "clear") {
                                    TraceLogBroadcaster.updateActiveHooks(emptyList())
                                    TraceLogBroadcaster.updateHookState(false)
                                    RootShell.clearDobbyHooks(targetProc)
                                    TraceLogBroadcaster.postLog("[*] Hooks cleared via Web Terminal API.")
                                    call.respondText(JSONObject().put("success", true).put("message", "Hooks cleared").toString(), ContentType.Application.Json)
                                } else {
                                    val rawOff = json.optString("offset", "")
                                    val name = json.optString("name", "Target")
                                    val off = rawOff.removePrefix("0x").toLongOrNull(16) ?: rawOff.toLongOrNull()

                                    if (off != null) {
                                        val symFunc = SymbolManager.findFunction(rawOff)
                                        val paramNames = symFunc?.params?.map { it.name } ?: emptyList()

                                        val newHook = HookTargetConfig(
                                            offset = off,
                                            name = name,
                                            traceReturn = true,
                                            params = paramNames
                                        )

                                        val currentList = TraceLogBroadcaster.activeHooks.value.filter { it.offset != off }.toMutableList()
                                        currentList.add(newHook)

                                        TraceLogBroadcaster.updateActiveHooks(currentList)
                                        TraceLogBroadcaster.updateHookState(true, "DOBBY")

                                        val dispatchRes = RootShell.startDobbyTracer(
                                            packageName = targetProc,
                                            hooks = currentList,
                                            onLogLine = { line ->
                                                TraceLogBroadcaster.postLog(line)
                                            }
                                        )

                                        TraceLogBroadcaster.postLog("[✓] Dispatched hook for $name ($rawOff) via Web Terminal")
                                        val resp = JSONObject()
                                        resp.put("success", dispatchRes.isSuccess)
                                        resp.put("message", if (dispatchRes.isSuccess) "Hooked $name ($rawOff)" else "Failed to dispatch hook")
                                        call.respondText(resp.toString(), ContentType.Application.Json)
                                    } else {
                                        call.respondText(JSONObject().put("success", false).put("error", "Invalid offset").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                                    }
                                }
                            } catch (e: Exception) {
                                call.respondText(JSONObject().put("success", false).put("error", e.message).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                            }
                        }

                        // 4. Upload Blutter Symbols JSON (Streaming direct-to-disk, zero memory bloat)
                        post("/upload-symbols") {
                            val pkg = call.parameters["pkg"]
                                ?: call.request.headers["X-Package-Name"]
                                ?: TraceLogBroadcaster.activeTarget.value

                            try {
                                val dir = File("/sdcard/Download/FlutterInspect${if (pkg.isNotBlank()) "/$pkg" else ""}")
                                dir.mkdirs()
                                val targetFile = File(dir, "symbols.json")
                                val fallbackFile = File("/sdcard/Download/FlutterInspect/symbols.json")

                                // Stream directly to disk using 64KB buffer
                                call.receiveStream().use { input ->
                                    targetFile.outputStream().buffered(65536).use { output ->
                                        input.copyTo(output)
                                    }
                                }

                                if (targetFile.length() == 0L) {
                                    call.respondText(JSONObject().put("success", false).put("error", "Empty payload").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                                    return@post
                                }

                                // Also save generic fallback
                                try {
                                    targetFile.copyTo(fallbackFile, overwrite = true)
                                } catch (_: Exception) {}

                                val parseRes = SymbolManager.loadFromFile(targetFile)
                                if (parseRes.isSuccess) {
                                    val (fCount, cCount) = parseRes.getOrThrow()
                                    if (pkg.isNotBlank()) {
                                        SymbolManager.setLoadedPackage(pkg)
                                        TraceLogBroadcaster.updateTarget(pkg)
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val currentCfg = RootShell.loadCurrentConfig() ?: com.payatu.flutterinspect.data.models.InspectConfig()
                                                val updatedCfg = currentCfg.copy(process = pkg)
                                                RootShell.saveConfig(updatedCfg)
                                            } catch (_: Exception) {}
                                        }
                                    }
                                    TraceLogBroadcaster.postLog("[✓] Web Terminal: Successfully uploaded and applied $fCount functions, $cCount classes for '$pkg'")

                                    val resp = JSONObject()
                                    resp.put("success", true)
                                    resp.put("functions", fCount)
                                    resp.put("classes", cCount)
                                    resp.put("message", "Loaded $fCount functions and $cCount classes from uploaded symbols.json")
                                    call.respondText(resp.toString(), ContentType.Application.Json)
                                } else {
                                    val err = parseRes.exceptionOrNull()?.message ?: "Invalid JSON"
                                    call.respondText(JSONObject().put("success", false).put("error", err).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                                }
                            } catch (t: Throwable) {
                                call.respondText(JSONObject().put("success", false).put("error", t.message ?: "Upload failed").toString(), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }

                        // 4b. Load Symbols directly from on-device file (instant sync from ADB push)
                        post("/load-symbols") {
                            val pkg = call.parameters["pkg"]
                                ?: call.request.headers["X-Package-Name"]
                                ?: TraceLogBroadcaster.activeTarget.value

                            val path = call.parameters["path"]
                                ?: if (pkg.isNotBlank()) "/sdcard/Download/FlutterInspect/$pkg/symbols.json" else "/sdcard/Download/FlutterInspect/symbols.json"

                            val file = File(path)
                            if (!file.exists()) {
                                call.respondText(JSONObject().put("success", false).put("error", "Symbols file not found at: $path").toString(), ContentType.Application.Json, HttpStatusCode.NotFound)
                                return@post
                            }

                            try {
                                val parseRes = SymbolManager.loadFromFile(file)
                                if (parseRes.isSuccess) {
                                    val (fCount, cCount) = parseRes.getOrThrow()
                                    if (pkg.isNotBlank()) {
                                        SymbolManager.setLoadedPackage(pkg)
                                        TraceLogBroadcaster.updateTarget(pkg)
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val currentCfg = RootShell.loadCurrentConfig() ?: com.payatu.flutterinspect.data.models.InspectConfig()
                                                val updatedCfg = currentCfg.copy(process = pkg)
                                                RootShell.saveConfig(updatedCfg)
                                            } catch (_: Exception) {}
                                        }
                                    }
                                    TraceLogBroadcaster.postLog("[✓] Web Terminal: Loaded $fCount functions, $cCount classes for '$pkg' from '$path'")

                                    val resp = JSONObject()
                                    resp.put("success", true)
                                    resp.put("functions", fCount)
                                    resp.put("classes", cCount)
                                    resp.put("message", "Loaded $fCount functions and $cCount classes from $path")
                                    call.respondText(resp.toString(), ContentType.Application.Json)
                                } else {
                                    val err = parseRes.exceptionOrNull()?.message ?: "Failed parsing symbols"
                                    call.respondText(JSONObject().put("success", false).put("error", err).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                                }
                            } catch (t: Throwable) {
                                call.respondText(JSONObject().put("success", false).put("error", t.message ?: "Load failed").toString(), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }

                        // 4c. Set Active Target Process
                        post("/set-target") {
                            val pkg = call.parameters["pkg"] ?: call.request.headers["X-Package-Name"] ?: ""
                            if (pkg.isBlank()) {
                                call.respondText(JSONObject().put("success", false).put("error", "Missing package name").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                                return@post
                            }
                            TraceLogBroadcaster.updateTarget(pkg)
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val currentCfg = RootShell.loadCurrentConfig() ?: com.payatu.flutterinspect.data.models.InspectConfig()
                                    val updatedCfg = currentCfg.copy(process = pkg)
                                    RootShell.saveConfig(updatedCfg)
                                } catch (_: Exception) {}
                            }
                            call.respondText(JSONObject().put("success", true).put("target", pkg).toString(), ContentType.Application.Json)
                        }

                        // 5. Clear Console
                        post("/clear-console") {
                            TraceLogBroadcaster.clear()
                            call.respondText(JSONObject().put("success", true).toString(), ContentType.Application.Json)
                        }

                        // 6. Installed Applications List
                        get("/apps") {
                            val apps = try {
                                RootShell.loadInstalledApps(appContext)
                            } catch (_: Exception) {
                                emptyList()
                            }
                            val arr = JSONArray()
                            apps.forEach { app ->
                                val o = JSONObject()
                                o.put("packageName", app.packageName)
                                o.put("appName", app.appName)
                                o.put("isFlutter", app.isFlutter)
                                arr.put(o)
                            }
                            call.respondText(arr.toString(), ContentType.Application.Json)
                        }

                        // 7. Trigger export of native libs (libapp.so & libflutter.so)
                        post("/export-libs") {
                            val target = call.parameters["pkg"]
                                ?: call.request.headers["X-Package-Name"]
                                ?: TraceLogBroadcaster.activeTarget.value

                            if (target.isBlank()) {
                                call.respondText(
                                    JSONObject().put("success", false).put("error", "No package specified").toString(),
                                    ContentType.Application.Json,
                                    HttpStatusCode.BadRequest
                                )
                                return@post
                            }

                            val res = RootShell.exportNativeLibs(appContext, target)
                            if (res.isSuccess) {
                                val outDir = res.getOrThrow()
                                TraceLogBroadcaster.updateTarget(target)
                                TraceLogBroadcaster.postLog("[✓] Exported native libraries for '$target' to $outDir")
                                val resp = JSONObject()
                                    .put("success", true)
                                    .put("package", target)
                                    .put("path", outDir)
                                    .put("message", "Exported libapp.so to $outDir")
                                call.respondText(resp.toString(), ContentType.Application.Json)
                            } else {
                                val err = res.exceptionOrNull()?.message ?: "Failed to export libraries"
                                call.respondText(
                                    JSONObject().put("success", false).put("error", err).toString(),
                                    ContentType.Application.Json,
                                    HttpStatusCode.InternalServerError
                                )
                            }
                        }

                        // 8. Download libapp.so directly to browser
                        get("/download-libapp") {
                            val target = call.parameters["pkg"]
                                ?: TraceLogBroadcaster.activeTarget.value

                            if (target.isBlank()) {
                                call.respondText("Error: No package specified and no active target set.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                                return@get
                            }

                            val outDir = File("/sdcard/Download/FlutterInspect/$target")
                            val libFile = File(outDir, "libapp.so")

                            // If not exported yet, export it on-the-fly via RootShell
                            if (!libFile.exists() || libFile.length() == 0L) {
                                val exportRes = RootShell.exportNativeLibs(appContext, target)
                                if (exportRes.isFailure) {
                                    call.respondText("Error: Unable to locate or export libapp.so for $target: ${exportRes.exceptionOrNull()?.message}", ContentType.Text.Plain, HttpStatusCode.NotFound)
                                    return@get
                                }
                            }

                            if (libFile.exists() && libFile.canRead()) {
                                call.response.header(
                                    HttpHeaders.ContentDisposition,
                                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "${target}_libapp.so").toString()
                                )
                                call.respondFile(libFile)
                            } else {
                                // Fallback: read via root shell if permissions are restricted
                                val tempLocal = File(appContext.cacheDir, "${target}_libapp.so")
                                val cpRes = RootShell.execSu("cp /sdcard/Download/FlutterInspect/$target/libapp.so ${tempLocal.absolutePath} && chmod 666 ${tempLocal.absolutePath}")
                                if (cpRes.exitCode == 0 && tempLocal.exists()) {
                                    call.response.header(
                                        HttpHeaders.ContentDisposition,
                                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "${target}_libapp.so").toString()
                                    )
                                    call.respondFile(tempLocal)
                                } else {
                                    call.respondText("Error reading libapp.so file: permission denied or file missing", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                                }
                            }
                        }

                        // 9. Download blutter2symbols.py Python Script
                        get("/download-script") {
                            val scriptContent = try {
                                appContext.assets.open("scripts/blutter2symbols.py").bufferedReader().use { it.readText() }
                            } catch (_: Exception) {
                                try {
                                    File("/data/local/tmp/blutter2symbols.py").readText()
                                } catch (_: Exception) {
                                    null
                                }
                            }

                            if (scriptContent != null) {
                                call.response.header(
                                    HttpHeaders.ContentDisposition,
                                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "blutter2symbols.py").toString()
                                )
                                call.respondText(scriptContent, ContentType.parse("text/x-python"))
                            } else {
                                call.respondText("Error: blutter2symbols.py asset not found on device.", ContentType.Text.Plain, HttpStatusCode.NotFound)
                            }
                        }
                    }

                    // 6. WebSocket Live Trace Stream
                    webSocket("/ws/trace") {
                        _connectedClients.value += 1
                        try {
                            // Send initial backlog
                            val initial = JSONObject()
                            initial.put("type", "history")
                            initial.put("lines", JSONArray(TraceLogBroadcaster.getRecentLogs()))
                            send(Frame.Text(initial.toString()))

                            // Send current status
                            val statusMsg = JSONObject()
                            statusMsg.put("type", "status")
                            statusMsg.put("target", TraceLogBroadcaster.activeTarget.value)
                            statusMsg.put("functionsCount", SymbolManager.functionsCount)
                            statusMsg.put("classesCount", SymbolManager.classesCount)
                            send(Frame.Text(statusMsg.toString()))

                            // Stream incoming logs
                            TraceLogBroadcaster.logsFlow.collectLatest { line ->
                                val logMsg = JSONObject()
                                logMsg.put("type", "log")
                                logMsg.put("line", line)
                                send(Frame.Text(logMsg.toString()))
                            }
                        } catch (_: Exception) {
                            // Client disconnected
                        } finally {
                            _connectedClients.value = (_connectedClients.value - 1).coerceAtLeast(0)
                        }
                    }
                }
            }

            server.start(wait = false)
            engine = server
            _isRunning.value = true
            TraceLogBroadcaster.postLog("[★] FlutterInspect Web Terminal server started on port $PORT (http://0.0.0.0:$PORT)")
            Result.success(Unit)
        } catch (e: Exception) {
            _isRunning.value = false
            Result.failure(e)
        }
    }

    fun stop() {
        try {
            engine?.stop(1000, 2000)
            engine = null
            _isRunning.value = false
            _connectedClients.value = 0
            TraceLogBroadcaster.postLog("[*] Web Terminal server stopped.")
        } catch (_: Exception) {}
    }

    /**
     * Resolves local IPv4 addresses (e.g. WiFi 192.168.x.x) for easy direct browser access.
     */
    fun getDeviceIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        ips.add(addr.hostAddress ?: "")
                    }
                }
            }
        } catch (_: Exception) {}
        return ips.filter { it.isNotBlank() }
    }
}
