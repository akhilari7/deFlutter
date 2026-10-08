package com.akhilari.deflutter.data.shell

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.akhilari.deflutter.data.models.DartSnapshot
import com.akhilari.deflutter.data.models.FunctionEntry
import com.akhilari.deflutter.data.models.InspectConfig
import com.akhilari.deflutter.data.models.InstalledApp
import com.akhilari.deflutter.data.models.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

object RootShell {

    private const val PRIMARY_CONFIG_PATH = "/data/adb/flutterlens/config.json"
    private const val FALLBACK_CONFIG_PATH = "/data/local/tmp/flutterlens_config.json"

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    suspend fun execSu(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("FlutterInspect", "execSu CMD: $command")
            // Use su -mm to enter global mount namespace (crucial for accessing other apps' /data/data)
            val process = try {
                ProcessBuilder("su", "-mm").redirectErrorStream(false).start()
            } catch (_: Exception) {
                ProcessBuilder("su").redirectErrorStream(false).start()
            }
            
            // Write command followed by exit to su stdin
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(command)
                writer.newLine()
                writer.write("exit")
                writer.newLine()
                writer.flush()
            }

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText).trim()
            val code = process.waitFor()

            android.util.Log.d("FlutterInspect", "execSu EXIT: $code | STDOUT: ${stdout.take(100)} | STDERR: $stderr")
            CommandResult(code, stdout, stderr)
        } catch (e: Exception) {
            android.util.Log.e("FlutterInspect", "execSu EXCEPTION: ${e.message}", e)
            CommandResult(-1, "", e.message ?: "Execution failed")
        }
    }

    fun execSuSync(command: String): CommandResult {
        return try {
            val process = try {
                ProcessBuilder("su", "-mm").redirectErrorStream(false).start()
            } catch (_: Exception) {
                ProcessBuilder("su").redirectErrorStream(false).start()
            }
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(command)
                writer.newLine()
                writer.write("exit")
                writer.newLine()
                writer.flush()
            }
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText).trim()
            val code = process.waitFor()
            CommandResult(code, stdout, stderr)
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Execution failed")
        }
    }

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        val res = execSu("id")
        val ok = res.exitCode == 0 && res.stdout.contains("uid=0")
        if (ok) {
            execSu("setenforce 0")
        }
        ok
    }

    suspend fun saveConfig(config: InspectConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            execSu("setenforce 0")
            execSu("mkdir -p /data/adb/flutterlens")
            val json = config.toJsonString()

            val proc = try {
                ProcessBuilder("su", "-mm").start()
            } catch (_: Exception) {
                ProcessBuilder("su").start()
            }
            proc.outputStream.bufferedWriter().use { writer ->
                writer.write("cat << 'EOF' > $PRIMARY_CONFIG_PATH\n$json\nEOF\n")
                writer.write("chmod 666 $PRIMARY_CONFIG_PATH\n")
                writer.write("cat << 'EOF' > $FALLBACK_CONFIG_PATH\n$json\nEOF\n")
                writer.write("chmod 666 $FALLBACK_CONFIG_PATH\n")
                if (config.process.isNotBlank()) {
                    val appCache = "/data/data/${config.process}/cache"
                    writer.write("mkdir -p $appCache\n")
                    writer.write("chmod 777 $appCache\n")
                    writer.write("touch $appCache/flutterlens.json 2>/dev/null; chmod 666 $appCache/flutterlens.json 2>/dev/null\n")
                    writer.write("cat << 'EOF' > $appCache/flutterlens_config.json\n$json\nEOF\n")
                    writer.write("chmod 666 $appCache/flutterlens_config.json\n")
                }
                writer.write("exit\n")
                writer.flush()
            }
            proc.waitFor()
            Result.success("Config saved to $PRIMARY_CONFIG_PATH & $FALLBACK_CONFIG_PATH")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadCurrentConfig(): InspectConfig? = withContext(Dispatchers.IO) {
        val res1 = execSu("cat $PRIMARY_CONFIG_PATH")
        if (res1.exitCode == 0 && res1.stdout.isNotBlank()) {
            return@withContext InspectConfig.fromJsonString(res1.stdout)
        }
        val res2 = execSu("cat $FALLBACK_CONFIG_PATH")
        if (res2.exitCode == 0 && res2.stdout.isNotBlank()) {
            return@withContext InspectConfig.fromJsonString(res2.stdout)
        }
        null
    }

    suspend fun readRootFile(path: String): String? = withContext(Dispatchers.IO) {
        val res = execSu("cat '$path'")
        if (res.exitCode == 0 && res.stdout.isNotBlank()) res.stdout else null
    }

    suspend fun loadScanResult(packageName: String): Result<ScanResult> = withContext(Dispatchers.IO) {
        val safePkg = packageName.replace(":", "_")
        val possiblePaths = listOf(
            "/data/data/$packageName/cache/flutterlens.json",
            "/data/user/0/$packageName/cache/flutterlens.json",
            "/data/data/$packageName/files/flutterlens.json",
            "/data/user/0/$packageName/files/flutterlens.json",
            "/data/local/tmp/flutterlens_$safePkg.json",
            "/data/local/tmp/flutterlens.json"
        )

        for (path in possiblePaths) {
            val res = execSu("cat $path")
            if (res.exitCode == 0 && res.stdout.isNotBlank()) {
                try {
                    val root = JSONObject(res.stdout)
                    val proc = root.optString("process", "")
                    if (proc.isNotBlank() && proc != packageName) {
                        continue
                    }
                    val lib = root.optString("lib", "libapp.so")
                    val libBase = root.optString("lib_base", "0x0")
                    val count = root.optInt("count", 0)

                    val funcs = mutableListOf<FunctionEntry>()
                    val arr = root.optJSONArray("functions")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val fObj = arr.getJSONObject(i)
                            funcs.add(
                                FunctionEntry(
                                    name = fObj.optString("name", "unknown"),
                                    absAddr = fObj.optString("abs_addr", "0x0"),
                                    offset = fObj.optString("offset", "0x0"),
                                    xrefAddr = fObj.optString("xref_addr", "0x0")
                                )
                            )
                        }
                    }

                    var snapshot: DartSnapshot? = null
                    val snapObj = root.optJSONObject("snapshot")
                    if (snapObj != null && snapObj.optBoolean("valid", false)) {
                        val symList = mutableListOf<String>()
                        val symArr = snapObj.optJSONArray("symbols")
                        if (symArr != null) {
                            for (s in 0 until symArr.length()) {
                                symList.add(symArr.getString(s))
                            }
                        }
                        snapshot = DartSnapshot(
                            valid = true,
                            magic = snapObj.optString("magic", ""),
                            kind = snapObj.optString("kind", ""),
                            hash = snapObj.optString("hash", ""),
                            features = snapObj.optString("features", ""),
                            numClusters = snapObj.optLong("num_clusters", 0L),
                            numObjects = snapObj.optLong("num_objects", 0L),
                            numBaseObjects = snapObj.optLong("num_base_objects", 0L),
                            instructionsOffset = snapObj.optString("instructions_offset", ""),
                            isolateDataAddr = snapObj.optString("isolate_data_addr", ""),
                            isolateInstructionsAddr = snapObj.optString("isolate_instructions_addr", ""),
                            symbolsCount = snapObj.optInt("symbols_count", symList.size),
                            symbols = symList
                        )
                    }

                    return@withContext Result.success(
                        ScanResult(
                            process = proc,
                            lib = lib,
                            libBase = libBase,
                            count = if (count > 0) count else funcs.size,
                            functions = funcs,
                            sourcePath = path,
                            snapshot = snapshot
                        )
                    )
                } catch (e: Exception) {
                    return@withContext Result.failure(e)
                }
            }
        }
        Result.failure(Exception("No result JSON found for $packageName in app cache or /data/local/tmp."))
    }

    suspend fun restartTargetApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        val stopCmd = "am force-stop $packageName"
        execSu(stopCmd)
        // Launch app
        val launchCmd = "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        val launchRes = execSu(launchCmd)
        if (launchRes.exitCode == 0) {
            Result.success("Restarted $packageName")
        } else {
            Result.failure(Exception("Failed to launch $packageName: ${launchRes.stderr}"))
        }
    }

    data class FridaSession(
        val job: Job,
        val process: Process
    ) {
        val isAlive: Boolean get() = process.isAlive

        fun stop() {
            try {
                process.destroy()
            } catch (_: Exception) {}
            job.cancel()
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                execSu("pkill -9 frida-inject")
            }
        }
    }

    suspend fun isFridaInjectAvailable(): Boolean = withContext(Dispatchers.IO) {
        val res = execSu("test -x /data/local/tmp/frida-inject && echo 'OK'")
        res.exitCode == 0 && res.stdout.contains("OK")
    }

    suspend fun startFridaInterceptor(
        packageName: String,
        scriptContent: String,
        isAttachMode: Boolean = false,
        onLogLine: (String) -> Unit
    ): Result<FridaSession> = withContext(Dispatchers.IO) {
        try {
            // 1. Write hook script via piped stdin (handles large scripts and quotes safely)
            val writeProc = ProcessBuilder("su").start()
            writeProc.outputStream.bufferedWriter().use { writer ->
                writer.write("cat << 'EOF' > /data/local/tmp/flutterlens_hook.js\n")
                writer.write(scriptContent)
                writer.write("\nEOF\n")
                writer.write("chmod 666 /data/local/tmp/flutterlens_hook.js\n")
                writer.write("exit\n")
                writer.flush()
            }
            writeProc.waitFor()

            // 2. Kill any stale frida-inject
            execSu("pkill -9 frida-inject")

            // 3. Build execution command based on mode
            val injectCmd: String
            if (isAttachMode) {
                // Find PID of running target app without killing it
                val pidRes = execSu("pidof $packageName")
                val pid = pidRes.stdout.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
                if (pid.isNullOrBlank() || pid.toIntOrNull() == null) {
                    return@withContext Result.failure(
                        IllegalStateException("App '$packageName' is not running! Launch the app first before attaching.")
                    )
                }
                injectCmd = "/data/local/tmp/frida-inject -p $pid -s /data/local/tmp/flutterlens_hook.js"
            } else {
                // In spawn mode, force-stop target app to ensure clean spawn
                execSu("am force-stop $packageName")
                injectCmd = "/data/local/tmp/frida-inject -f $packageName -s /data/local/tmp/flutterlens_hook.js"
            }

            val process = ProcessBuilder("su", "-c", injectCmd).redirectErrorStream(true).start()

            val job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = process.inputStream.bufferedReader()
                    while (process.isAlive) {
                        val raw = reader.readLine() ?: break
                        if (raw.contains("ptrace pokedata", ignoreCase = true) || raw.contains("I/O error", ignoreCase = true)) {
                            onLogLine("[-] $raw")
                            onLogLine("[!] Android kernel blocked live ptrace write (EIO due to shared ART zygote memfd).")
                            onLogLine("[💡] Fix: Switch mode to 'SPAWN (RECOMMENDED)' to trace $packageName reliably from app startup!")
                            continue
                        }
                        // Clean frida JSON messages if formatted as {"type":"send","payload":...}
                        val cleaned = try {
                            if (raw.trimStart().startsWith("{\"type\":")) {
                                val obj = JSONObject(raw)
                                if (obj.optString("type") == "send") {
                                    val payload = obj.opt("payload")
                                    payload?.toString() ?: raw
                                } else {
                                    raw
                                }
                            } else {
                                raw
                            }
                        } catch (_: Exception) {
                            raw
                        }
                        onLogLine(cleaned)
                    }
                } catch (_: Exception) {}
            }

            Result.success(FridaSession(job, process))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class DobbySession(
        val job: Job,
        val process: Process
    ) {
        val isAlive: Boolean get() = process.isAlive
        fun stop() {
            try {
                process.destroy()
            } catch (_: Exception) {}
            job.cancel()
        }
    }

    suspend fun startDobbyTracer(
        packageName: String,
        hooks: List<com.akhilari.deflutter.data.models.HookTargetConfig>,
        onLogLine: (String) -> Unit
    ): Result<DobbySession> = withContext(Dispatchers.IO) {
        try {
            // 1. Update config with hooks for live Zygisk sync
            val curCfg = loadCurrentConfig() ?: InspectConfig(process = packageName)
            val updatedCfg = curCfg.copy(
                process = packageName,
                hooks = hooks
            )
            saveConfig(updatedCfg)

            // 2. Clear logcat buffer for tracer tag
            execSu("logcat -c -b main")

            // 3. Stream logcat for FlutterInspect-Tracer tag
            val logcatCmd = "logcat -v tag -s FlutterInspect-Tracer:V"
            val process = ProcessBuilder("su", "-c", logcatCmd).redirectErrorStream(true).start()

            val job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = process.inputStream.bufferedReader()
                    while (process.isAlive) {
                        val raw = reader.readLine() ?: break
                        val clean = if (raw.contains("FlutterInspect-Tracer")) {
                            raw.substringAfter("FlutterInspect-Tracer:").trimStart(' ', '\t', ':')
                        } else {
                            raw
                        }
                        onLogLine(clean)
                    }
                } catch (_: Exception) {}
            }

            Result.success(DobbySession(job, process))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearDobbyHooks(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val curCfg = loadCurrentConfig() ?: InspectConfig(process = packageName)
            val emptyCfg = curCfg.copy(process = packageName.ifBlank { curCfg.process }, hooks = emptyList())
            saveConfig(emptyCfg)
            if (packageName.isNotBlank()) {
                val appCache = "/data/data/$packageName/cache/flutterlens_config.json"
                val appCacheEmpty = emptyCfg.copy(process = packageName)
                val json = appCacheEmpty.toJsonString().replace("\"", "\\\"").replace("\n", " ")
                execSu("echo \"$json\" > $appCache && chmod 666 $appCache")
            }
            Result.success("Dobby hooks cleared")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRunningAppPid(packageName: String): Int? = withContext(Dispatchers.IO) {
        val pidRes = execSu("pidof $packageName")
        val pidStr = pidRes.stdout.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
        pidStr?.toIntOrNull()
    }

    suspend fun scanRunningAppWithFrida(
        packageName: String,
        keywords: List<String>
    ): Result<List<FunctionEntry>> = withContext(Dispatchers.IO) {
        try {
            val pid = getRunningAppPid(packageName)
                ?: return@withContext Result.failure(IllegalStateException("Target app $packageName is not running"))

            val keywordsJson = keywords.map { "\"${it.replace("\"", "\\\"").lowercase()}\"" }.joinToString(",")
            val scannerJs = """
                (function() {
                    try {
                        var appMod = Process.findModuleByName("libapp.so");
                        if (!appMod) {
                            console.log("FLUTTERINSPECT_SCAN_RESULT:" + JSON.stringify({error: "libapp.so not loaded"}));
                            return;
                        }

                        var isoDataAddr = null;
                        var isoInsnAddr = null;

                        var exps = appMod.enumerateExports();
                        for (var i = 0; i < exps.length; i++) {
                            var s = exps[i];
                            if (s.name === "_kDartIsolateSnapshotData" || s.name === "kDartIsolateSnapshotData") {
                                isoDataAddr = s.address;
                            } else if (s.name === "_kDartIsolateSnapshotInstructions" || s.name === "kDartIsolateSnapshotInstructions") {
                                isoInsnAddr = s.address;
                            }
                        }

                        if (!isoDataAddr || !isoInsnAddr) {
                            console.log("FLUTTERINSPECT_SCAN_RESULT:" + JSON.stringify({error: "Snapshot exports not found"}));
                            return;
                        }

                        var range = Process.getRangeByAddress(isoDataAddr);
                        var dataSize = range.base.add(range.size).sub(isoDataAddr).toInt32();

                        var rawBuf = isoDataAddr.readByteArray(dataSize);
                        var u8 = new Uint8Array(rawBuf);
                        var view = new DataView(rawBuf);

                        var bestTableOff = -1;
                        var maxConsecutive = 0;

                        for (var off = 0; off + 800 <= dataSize; off += 8) {
                            var ioff = view.getUint32(off, true);
                            var fref = view.getUint32(off + 4, true);

                            if (ioff > 0 && ioff < 0x4000 && fref > 0 && fref < dataSize) {
                                var prev = ioff;
                                var count = 1;
                                for (var k = 1; off + (k + 1) * 8 <= dataSize; ++k) {
                                    var c_ioff = view.getUint32(off + k * 8, true);
                                    var c_fref = view.getUint32(off + k * 8 + 4, true);
                                    if (c_ioff < prev || c_ioff > 0x4000000 || c_fref >= dataSize || c_fref === 0) {
                                        break;
                                    }
                                    prev = c_ioff;
                                    count++;
                                }
                                if (count > maxConsecutive) {
                                    maxConsecutive = count;
                                    bestTableOff = off;
                                }
                                if (count > 1) {
                                    off += (count - 1) * 8;
                                }
                            }
                        }

                        var targetKeywords = [$keywordsJson];
                        var cleanKeywords = targetKeywords.map(function(k) { return k.toLowerCase().replace(/_/g, ""); });

                        var results = [];
                        var nameOccurrences = {};
                        var seenAddrs = {};

                        for (var off = bestTableOff; off + 8 <= dataSize; off += 8) {
                            var insn_off = view.getUint32(off, true);
                            var fn_ref = view.getUint32(off + 4, true);

                            if (insn_off > 0x4000000 || fn_ref >= dataSize || (off > bestTableOff && insn_off === 0)) {
                                break;
                            }

                            var chunkStart = fn_ref > 64 ? fn_ref - 64 : 0;
                            var chunkLen = Math.min(dataSize - chunkStart, 256);
                            var chars = [];
                            for (var ci = 0; ci < chunkLen; ci++) {
                                chars.push(String.fromCharCode(u8[chunkStart + ci]));
                            }
                            var chunkStr = chars.join("");
                            var chunkClean = chunkStr.toLowerCase().replace(/_/g, "");

                            for (var ki = 0; ki < cleanKeywords.length; ki++) {
                                var kw = cleanKeywords[ki];
                                if (kw.length >= 2 && chunkClean.indexOf(kw) !== -1) {
                                    var absAddr = isoInsnAddr.add(insn_off);
                                    var absHex = absAddr.toString();
                                    if (!seenAddrs[absHex]) {
                                        seenAddrs[absHex] = true;

                                        var fnName = targetKeywords[ki];
                                        var count = (nameOccurrences[fnName] || 0) + 1;
                                        nameOccurrences[fnName] = count;
                                        var displayName = count > 1 ? fnName + " #" + count : fnName;

                                        var offsetFromBase = absAddr.sub(appMod.base);
                                        results.push({
                                            name: displayName,
                                            abs_addr: absAddr.toString(),
                                            offset: offsetFromBase.toString(),
                                            xref_addr: absAddr.toString(),
                                            lib_name: "libapp.so"
                                        });
                                    }
                                    break;
                                }
                            }
                        }

                        console.log("FLUTTERINSPECT_SCAN_RESULT:" + JSON.stringify({count: results.length, functions: results}));
                    } catch (err) {
                        console.log("FLUTTERINSPECT_SCAN_RESULT:" + JSON.stringify({error: err.toString()}));
                    }
                })();
            """.trimIndent()

            val writeProc = ProcessBuilder("su").start()
            writeProc.outputStream.bufferedWriter().use { writer ->
                writer.write("cat << 'EOF' > /data/local/tmp/flutterlens_live_scanner.js\n")
                writer.write(scannerJs)
                writer.write("\nEOF\n")
                writer.write("chmod 666 /data/local/tmp/flutterlens_live_scanner.js\n")
                writer.write("exit\n")
                writer.flush()
            }
            writeProc.waitFor()

            val scanProc = ProcessBuilder("su").start()
            val marker = "FLUTTERINSPECT_SCAN_RESULT:"
            var foundLine: String? = null

            val scanJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                scanProc.outputStream.bufferedWriter().use { writer ->
                    writer.write("/data/local/tmp/frida-inject -p $pid -s /data/local/tmp/flutterlens_live_scanner.js\n")
                    writer.flush()
                }
            }

            withContext(Dispatchers.IO) {
                val reader = scanProc.inputStream.bufferedReader()
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) {
                        val l = reader.readLine() ?: break
                        if (l.contains(marker)) {
                            foundLine = l
                            break
                        }
                    } else {
                        kotlinx.coroutines.delay(40)
                    }
                }
            }

            scanProc.destroyForcibly()
            execSu("pkill -9 frida-inject")
            scanJob.cancel()

            val line = foundLine
                ?: return@withContext Result.failure(Exception("Scanner did not return results marker within timeout"))

            val jsonStr = line.substringAfter(marker).trim()
            val root = JSONObject(jsonStr)
            if (root.has("error")) {
                return@withContext Result.failure(Exception(root.getString("error")))
            }

            val funcs = mutableListOf<FunctionEntry>()
            val arr = root.optJSONArray("functions")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val fObj = arr.getJSONObject(i)
                    funcs.add(
                        FunctionEntry(
                            name = fObj.optString("name", "unknown"),
                            absAddr = fObj.optString("abs_addr", "0x0"),
                            offset = fObj.optString("offset", "0x0"),
                            xrefAddr = fObj.optString("xref_addr", "0x0")
                        )
                    )
                }
            }

            Result.success(funcs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun mergeScanResultsIntoCache(packageName: String, newFuncs: List<FunctionEntry>) = withContext(Dispatchers.IO) {
        try {
            val cachePath = "/data/data/$packageName/cache/flutterlens.json"
            val existingRes = loadScanResult(packageName).getOrNull()
            val combined = ((existingRes?.functions ?: emptyList()) + newFuncs)
                .distinctBy { it.offset }

            val root = JSONObject()
            root.put("process", packageName)
            root.put("lib", existingRes?.lib ?: "libapp.so")
            root.put("lib_base", existingRes?.libBase ?: "0x0")
            root.put("count", combined.size)

            val arr = org.json.JSONArray()
            for (f in combined) {
                val obj = JSONObject()
                obj.put("name", f.name)
                obj.put("abs_addr", f.absAddr)
                obj.put("offset", f.offset)
                obj.put("xref_addr", f.xrefAddr)
                arr.put(obj)
            }
            root.put("functions", arr)

            if (existingRes?.snapshot != null && existingRes.snapshot.valid) {
                val snapObj = JSONObject()
                val s = existingRes.snapshot
                snapObj.put("valid", true)
                snapObj.put("magic", s.magic)
                snapObj.put("kind", s.kind)
                snapObj.put("hash", s.hash)
                snapObj.put("features", s.features)
                snapObj.put("num_clusters", s.numClusters)
                snapObj.put("num_objects", s.numObjects)
                snapObj.put("num_base_objects", s.numBaseObjects)
                snapObj.put("instructions_offset", s.instructionsOffset)
                snapObj.put("symbols_count", s.symbolsCount)
                val symArr = org.json.JSONArray()
                s.symbols.forEach { symArr.put(it) }
                snapObj.put("symbols", symArr)
                root.put("snapshot", snapObj)
            }

            val jsonStr = root.toString(2).replace("\"", "\\\"").replace("\n", " ")
            execSu("echo \"$jsonStr\" > $cachePath && chmod 666 $cachePath && echo \"$jsonStr\" > /data/local/tmp/flutterlens.json")
        } catch (_: Exception) {}
    }

    suspend fun loadInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val list = mutableListOf<InstalledApp>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !pkg.packageName.contains("flutter")) continue

            val appName = pm.getApplicationLabel(appInfo).toString()
            var isFlutter = false

            // Check nativeLibraryDir or APK for libflutter.so or libapp.so
            try {
                val libDir = File(appInfo.nativeLibraryDir)
                if (libDir.exists() && (File(libDir, "libflutter.so").exists() || File(libDir, "libapp.so").exists())) {
                    isFlutter = true
                } else if (appInfo.sourceDir != null) {
                    ZipFile(File(appInfo.sourceDir)).use { zip ->
                        isFlutter = zip.getEntry("lib/arm64-v8a/libflutter.so") != null ||
                                    zip.getEntry("lib/arm64-v8a/libapp.so") != null ||
                                    zip.getEntry("assets/flutter_assets/") != null
                    }
                }
            } catch (_: Exception) {}

            val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }

            list.add(
                InstalledApp(
                    packageName = pkg.packageName,
                    appName = appName,
                    isFlutter = isFlutter,
                    icon = icon
                )
            )
        }

        // Sort Flutter apps first, then alphabetically
        list.sortedWith(compareByDescending<InstalledApp> { it.isFlutter }.thenBy { it.appName })
    }

    suspend fun exportNativeLibs(
        context: Context,
        packageName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (packageName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("No target package specified"))
            }

            val outDir = "/sdcard/Download/FlutterInspect/$packageName"
            execSu("mkdir -p $outDir")

            val pm = context.packageManager
            var nativeLibDir: String? = null
            var sourceApk: String? = null

            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                nativeLibDir = appInfo.nativeLibraryDir
                sourceApk = appInfo.sourceDir
            } catch (_: Exception) {}

            // Option 1: Direct copy from nativeLibraryDir
            if (!nativeLibDir.isNullOrBlank()) {
                execSu("cp $nativeLibDir/libapp.so $outDir/ 2>/dev/null; cp $nativeLibDir/libflutter.so $outDir/ 2>/dev/null; chmod 666 $outDir/* 2>/dev/null")
            }

            // Option 2: Fallback extract from APK
            val check1 = execSu("ls $outDir/libapp.so")
            if (check1.exitCode != 0 && sourceApk != null) {
                execSu("unzip -p $sourceApk lib/arm64-v8a/libapp.so > $outDir/libapp.so 2>/dev/null; unzip -p $sourceApk lib/arm64-v8a/libflutter.so > $outDir/libflutter.so 2>/dev/null; chmod 666 $outDir/* 2>/dev/null")
            }

            // Option 3: Fallback find in /data/app
            val check2 = execSu("ls $outDir/libapp.so")
            if (check2.exitCode != 0) {
                val findRes = execSu("find /data/app -name libapp.so | grep $packageName | head -n 1")
                val foundPath = findRes.stdout.trim()
                if (foundPath.isNotBlank()) {
                    val dir = foundPath.substringBeforeLast("/")
                    execSu("cp $dir/libapp.so $outDir/ && cp $dir/libflutter.so $outDir/ && chmod 666 $outDir/*")
                }
            }

            val verify = execSu("ls -l $outDir/libapp.so")
            if (verify.exitCode == 0 && verify.stdout.contains("libapp.so")) {
                Result.success(outDir)
            } else {
                Result.failure(Exception("Could not locate or export libapp.so for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLiveModuleBase(packageName: String, moduleName: String = "libapp.so"): String? {
        if (packageName.isBlank()) return null
        return try {
            val cmd = "pid=\$(pidof $packageName 2>/dev/null | awk '{print \$1}'); if [ -n \"\$pid\" ]; then grep '$moduleName' /proc/\$pid/maps 2>/dev/null | head -n 1 | cut -d'-' -f1; fi"
            val res = execSu(cmd).stdout.trim()
            if (res.isNotBlank() && res.matches(Regex("^[0-9a-fA-F]+$"))) {
                "0x" + res.lowercase()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
