package com.akhilari.deflutter.data.symbols

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

data class DartSymbolParam(
    val idx: Int = 0,
    val name: String = "",
    val type: String = "dynamic",
    val reg: String = "",
    val slot: String = ""
)

data class DartSymbolClass(
    val cid: String,
    val name: String,
    val lib: String = "",
    val fields: Map<String, String> = emptyMap()
)

data class DartSymbolFunction(
    val offset: String,
    val name: String,
    val className: String = "",
    val method: String = "",
    val lib: String = "",
    val params: List<DartSymbolParam> = emptyList()
)

object SymbolManager {
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _symbolStats = MutableStateFlow<String?>(null)
    val symbolStats: StateFlow<String?> = _symbolStats.asStateFlow()

    private val _loadedPackage = MutableStateFlow<String>("")
    val loadedPackage: StateFlow<String> = _loadedPackage.asStateFlow()

    private val _activeSnapshot = MutableStateFlow<com.akhilari.deflutter.data.models.DartSnapshot?>(null)
    val activeSnapshot: StateFlow<com.akhilari.deflutter.data.models.DartSnapshot?> = _activeSnapshot.asStateFlow()

    fun setActiveSnapshot(snap: com.akhilari.deflutter.data.models.DartSnapshot?) {
        _activeSnapshot.value = snap
    }

    fun setLoadedPackage(pkg: String) {
        _loadedPackage.value = pkg
    }

    private val classByCid = mutableMapOf<String, DartSymbolClass>()
    private val funcByOffset = mutableMapOf<String, DartSymbolFunction>()
    private val funcByName = mutableMapOf<String, DartSymbolFunction>()
    private var activeHitFunction: DartSymbolFunction? = null

    val functionsCount: Int get() = funcByOffset.size
    val classesCount: Int get() = classByCid.size

    fun loadFromFile(file: File): Result<Pair<Int, Int>> {
        return try {
            file.inputStream().buffered(65536).use { stream ->
                loadFromStream(stream)
            }
        } catch (t: Throwable) {
            try {
                val tempPath = "/data/local/tmp/fi_symbols_${System.currentTimeMillis()}.json"
                val res = com.akhilari.deflutter.data.shell.RootShell.execSuSync("cp '${file.absolutePath}' '$tempPath' && chmod 666 '$tempPath'")
                if (res.exitCode == 0) {
                    val tempFile = File(tempPath)
                    val parseRes = tempFile.inputStream().buffered(65536).use { stream ->
                        loadFromStream(stream)
                    }
                    tempFile.delete()
                    return parseRes
                }
            } catch (_: Exception) {}
            Result.failure(t)
        }
    }

    fun loadFromJson(jsonStr: String): Result<Pair<Int, Int>> {
        return try {
            jsonStr.byteInputStream(Charsets.UTF_8).use { stream ->
                loadFromStream(stream)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun loadFromStream(inputStream: InputStream): Result<Pair<Int, Int>> {
        return try {
            val reader = JsonReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val newClasses = mutableMapOf<String, DartSymbolClass>()
            val newFuncs = mutableMapOf<String, DartSymbolFunction>()
            val newFuncsByName = mutableMapOf<String, DartSymbolFunction>()
            val stringPool = mutableMapOf<String, String>()

            fun intern(s: String): String {
                if (s.isEmpty()) return ""
                return stringPool.getOrPut(s) { s }
            }

            reader.beginObject()
            while (reader.hasNext()) {
                val rootKey = reader.nextName()
                when (rootKey) {
                    "classes" -> {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val cidKey = reader.nextName()
                                if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                    var name = "Unknown"
                                    var lib = ""
                                    val fields = mutableMapOf<String, String>()

                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "name" -> name = if (reader.peek() != JsonToken.NULL) reader.nextString() else { reader.nextNull(); "Unknown" }
                                            "lib" -> lib = if (reader.peek() != JsonToken.NULL) intern(reader.nextString()) else { reader.nextNull(); "" }
                                            "fields" -> {
                                                if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                    reader.beginObject()
                                                    while (reader.hasNext()) {
                                                        val fKey = reader.nextName().lowercase().removePrefix("0x")
                                                        val fVal = if (reader.peek() != JsonToken.NULL) intern(reader.nextString()) else { reader.nextNull(); "" }
                                                        fields[fKey] = fVal
                                                    }
                                                    reader.endObject()
                                                } else {
                                                    reader.skipValue()
                                                }
                                            }
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()

                                    val normKey = cidKey.lowercase().removePrefix("0x")
                                    val sc = DartSymbolClass(
                                        cid = "0x$normKey",
                                        name = name,
                                        lib = lib,
                                        fields = fields
                                    )
                                    newClasses[normKey] = sc
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()
                        } else {
                            reader.skipValue()
                        }
                    }
                    "functions" -> {
                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val offKey = reader.nextName()
                                if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                    var name = ""
                                    var cls = ""
                                    var mth = ""
                                    var lib = ""
                                    var paramsList: MutableList<DartSymbolParam>? = null

                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "name" -> name = if (reader.peek() != JsonToken.NULL) reader.nextString() else { reader.nextNull(); "" }
                                            "class" -> cls = if (reader.peek() != JsonToken.NULL) intern(reader.nextString()) else { reader.nextNull(); "" }
                                            "method" -> mth = if (reader.peek() != JsonToken.NULL) intern(reader.nextString()) else { reader.nextNull(); "" }
                                            "lib" -> lib = if (reader.peek() != JsonToken.NULL) intern(reader.nextString()) else { reader.nextNull(); "" }
                                            "params" -> {
                                                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                                    reader.beginArray()
                                                    while (reader.hasNext()) {
                                                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                            var pIdx = 0
                                                            var pName = ""
                                                            var pType = "dynamic"
                                                            var pReg = ""
                                                            var pSlot = ""
                                                            reader.beginObject()
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName()) {
                                                                    "idx" -> pIdx = if (reader.peek() != JsonToken.NULL) reader.nextInt() else { reader.nextNull(); 0 }
                                                                    "name" -> pName = if (reader.peek() != JsonToken.NULL) reader.nextString() else { reader.nextNull(); "" }
                                                                    "type" -> pType = if (reader.peek() != JsonToken.NULL) intern(reader.nextString()) else { reader.nextNull(); "dynamic" }
                                                                    "reg" -> pReg = if (reader.peek() != JsonToken.NULL) intern(reader.nextString()) else { reader.nextNull(); "" }
                                                                    "slot" -> pSlot = if (reader.peek() != JsonToken.NULL) intern(reader.nextString()) else { reader.nextNull(); "" }
                                                                    else -> reader.skipValue()
                                                                }
                                                            }
                                                            reader.endObject()
                                                            if (paramsList == null) paramsList = mutableListOf()
                                                            paramsList.add(DartSymbolParam(pIdx, pName, pType, pReg, pSlot))
                                                        } else {
                                                            reader.skipValue()
                                                        }
                                                    }
                                                    reader.endArray()
                                                } else {
                                                    reader.skipValue()
                                                }
                                            }
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()

                                    val normOff = offKey.lowercase().removePrefix("0x")
                                    val sf = DartSymbolFunction(
                                        offset = "0x$normOff",
                                        name = name,
                                        className = cls,
                                        method = mth,
                                        lib = lib,
                                        params = paramsList ?: emptyList()
                                    )
                                    newFuncs[normOff] = sf

                                    if (name.isNotBlank()) {
                                        newFuncsByName[name.lowercase()] = sf
                                    }
                                    if (cls.isNotBlank() && mth.isNotBlank()) {
                                        newFuncsByName["${cls.lowercase()}.${mth.lowercase()}"] = sf
                                    }
                                    if (mth.isNotBlank()) {
                                        newFuncsByName.putIfAbsent(mth.lowercase(), sf)
                                    }
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()
                        } else {
                            reader.skipValue()
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            reader.close()
            stringPool.clear()

            synchronized(this) {
                classByCid.clear()
                classByCid.putAll(newClasses)
                funcByOffset.clear()
                funcByOffset.putAll(newFuncs)
                funcByName.clear()
                funcByName.putAll(newFuncsByName)
                activeHitFunction = null
                _isLoaded.value = true
                _symbolStats.value = "${newFuncs.size} funcs, ${newClasses.size} classes"
            }

            com.akhilari.deflutter.server.TraceLogBroadcaster.redecorateBuffer()

            Result.success(Pair(newFuncs.size, newClasses.size))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun clear() {
        synchronized(this) {
            classByCid.clear()
            funcByOffset.clear()
            funcByName.clear()
            activeHitFunction = null
            _isLoaded.value = false
            _symbolStats.value = null
            _loadedPackage.value = ""
            _activeSnapshot.value = null
        }
    }

    fun resolveFunction(offset: String): String? {
        val norm = offset.lowercase().removePrefix("0x")
        return funcByOffset[norm]?.name
    }

    fun findFunction(nameOrOffset: String): DartSymbolFunction? {
        val trimmed = nameOrOffset.trim().trim('[', ']', '(', ')')
        val norm = trimmed.lowercase().removePrefix("0x")
        val decAsHex = trimmed.toLongOrNull()?.toString(16)?.lowercase()
        return funcByOffset[norm]
            ?: (if (decAsHex != null) funcByOffset[decAsHex] else null)
            ?: funcByName[trimmed.lowercase()]
            ?: funcByName[trimmed]
            ?: funcByName.values.firstOrNull { it.name.equals(trimmed, ignoreCase = true) || it.method.equals(trimmed, ignoreCase = true) }
    }

    fun resolveClass(cid: String): DartSymbolClass? {
        val norm = cid.lowercase().removePrefix("0x")
        return classByCid[norm]
    }

    fun resolveField(cid: String, offset: String): String? {
        val cls = resolveClass(cid) ?: return null
        val normOff = offset.lowercase().removePrefix("0x")
        return cls.fields[normOff] ?: cls.fields["0x$normOff"]
    }

    fun getAllFunctions(limit: Int = 20000): List<DartSymbolFunction> {
        return synchronized(this) {
            if (limit <= 0) funcByOffset.values.toList()
            else funcByOffset.values.asSequence().take(limit).toList()
        }
    }

    data class PagedResult<T>(
        val total: Int,
        val page: Int,
        val pageSize: Int,
        val totalPages: Int,
        val items: List<T>
    )

    fun queryFunctionsPaged(query: String, page: Int = 1, pageSize: Int = 50): PagedResult<DartSymbolFunction> {
        val safePage = if (page < 1) 1 else page
        val safePageSize = if (pageSize < 1) 50 else pageSize.coerceAtMost(500)
        val offset = (safePage - 1) * safePageSize

        return synchronized(this) {
            val seq = if (query.isBlank()) {
                funcByOffset.values.asSequence()
            } else {
                val q = query.lowercase().trim()
                val qNoUnderscore = q.replace("_", "")
                funcByOffset.values.asSequence().filter { sf ->
                    sf.offset.contains(q) ||
                    sf.name.lowercase().contains(q) ||
                    (qNoUnderscore.length >= 3 && sf.name.lowercase().replace("_", "").contains(qNoUnderscore)) ||
                    sf.className.lowercase().contains(q) ||
                    sf.method.lowercase().contains(q) ||
                    sf.lib.lowercase().contains(q)
                }
            }

            // Materialize count or compute without full array retention
            val matchingList = seq.toList()
            val total = matchingList.size
            val totalPages = if (total == 0) 1 else ((total + safePageSize - 1) / safePageSize)
            val items = if (offset >= total) {
                emptyList()
            } else {
                matchingList.subList(offset, (offset + safePageSize).coerceAtMost(total))
            }

            PagedResult(
                total = total,
                page = safePage,
                pageSize = safePageSize,
                totalPages = totalPages,
                items = items
            )
        }
    }

    fun searchFunctions(query: String, limit: Int = 20000): List<DartSymbolFunction> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase().trim()
        val qNoUnderscore = q.replace("_", "")
        return synchronized(this) {
            funcByOffset.values.asSequence()
                .filter { sf ->
                    sf.offset.contains(q) ||
                    sf.name.lowercase().contains(q) ||
                    (qNoUnderscore.length >= 3 && sf.name.lowercase().replace("_", "").contains(qNoUnderscore)) ||
                    sf.className.lowercase().contains(q) ||
                    sf.method.lowercase().contains(q) ||
                    sf.lib.lowercase().contains(q)
                }
                .take(limit)
                .toList()
        }
    }

    fun searchClasses(query: String, limit: Int = 50): List<DartSymbolClass> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase().trim()
        return synchronized(this) {
            classByCid.values.asSequence()
                .filter { sc ->
                    sc.cid.lowercase().contains(q) ||
                    sc.name.lowercase().contains(q) ||
                    sc.lib.lowercase().contains(q)
                }
                .take(limit)
                .toList()
        }
    }

    suspend fun autoLoadForPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false

        // Fetch or refresh snapshot metadata for this package
        val scanRes = com.akhilari.deflutter.data.shell.RootShell.loadScanResult(packageName).getOrNull()
        if (scanRes?.snapshot != null && scanRes.snapshot.valid) {
            _activeSnapshot.value = scanRes.snapshot
        } else if (_loadedPackage.value != packageName) {
            _activeSnapshot.value = null
        }

        if (_isLoaded.value && _loadedPackage.value == packageName) {
            return true
        }

        // If switching from another package, clear old symbols to prevent state bleed
        if (_loadedPackage.value.isNotBlank() && _loadedPackage.value != packageName) {
            clear()
            // Re-apply active snapshot if found for new package
            if (scanRes?.snapshot != null && scanRes.snapshot.valid) {
                _activeSnapshot.value = scanRes.snapshot
            }
        }

        val candidates = mutableListOf<String>()
        candidates.add("/sdcard/Download/FlutterInspect/$packageName/symbols.json")
        candidates.add("/data/local/tmp/${packageName}_symbols.json")
        candidates.add("/data/local/tmp/$packageName/symbols.json")

        for (path in candidates) {
            val f = java.io.File(path)
            if (f.exists()) {
                try {
                    val res = loadFromFile(f)
                    if (res.isSuccess) {
                        _loadedPackage.value = packageName
                        return true
                    }
                } catch (_: Throwable) {}
            }
        }

        // No package-specific symbols file found -> ensure clean state for this package
        _loadedPackage.value = packageName
        return false
    }

    fun DartSymbolFunction.toFunctionEntry(): com.akhilari.deflutter.data.models.FunctionEntry {
        return com.akhilari.deflutter.data.models.FunctionEntry(
            name = this.name.ifBlank { if (this.className.isNotBlank()) "${this.className}.${this.method}" else this.offset },
            absAddr = "libapp.so+${this.offset}",
            offset = this.offset,
            xrefAddr = "Blutter Symbol"
        )
    }

    fun decorateLogLine(line: String): String {
        if (!_isLoaded.value) return line
        var decorated = line

        // 1. Universally decorate ANY cid=0x... (Instance(cid=0x...), Uint8List(cid=0x...), Bytes(cid=0x...), etc.)
        val universalCidRegex = Regex("""\bcid=(0x[0-9a-fA-F]+)(?=[,\)\s])(?!\s*:\s*[A-Za-z0-9_])""")
        decorated = universalCidRegex.replace(decorated) { match ->
            val cid = match.groupValues[1]
            val cls = resolveClass(cid)
            if (cls != null && cls.name.isNotBlank()) {
                "cid=$cid: ${cls.name}"
            } else {
                match.value
            }
        }

        // 3. Decorate HIT: <offset or name> and track active function
        val hitRegex = Regex("""(HIT:\s+)(0x[0-9a-fA-F]+|[A-Za-z0-9_$.:]+)(?:\s*\(([^)]+)\))?""")
        decorated = hitRegex.replace(decorated) { match ->
            val prefix = match.groupValues[1]
            val token = match.groupValues[2]
            val existingParen = match.groupValues[3].trim()
            val fn = findFunction(token)
            if (fn != null) {
                synchronized(this) { activeHitFunction = fn }
                val rawOff = fn.offset.lowercase().removePrefix("0x")
                val offsetHex = "0x$rawOff"
                "$prefix${fn.name} ($offsetHex)"
            } else if (existingParen.isNotBlank()) {
                val hexParen = if (existingParen.startsWith("0x", ignoreCase = true)) {
                    "0x" + existingParen.removePrefix("0x").removePrefix("0X").lowercase()
                } else {
                    val dec = existingParen.toLongOrNull()
                    if (dec != null) "0x" + dec.toString(16).lowercase() else "0x" + existingParen.lowercase()
                }
                "$prefix$token ($hexParen)"
            } else {
                match.value
            }
        }

        // 4. Decorate arguments (Arg0, Arg1, Arg2, Arg3, Arg4) with parameter names & types
        val currentFn = synchronized(this) { activeHitFunction }
        if (currentFn != null && currentFn.params.isNotEmpty()) {
            val argRegex = Regex("""\b(Arg0(?:\s*\(this/recv\))?|Arg\d+)(?:\s*\((0x[0-9a-fA-F]+)\))?(\s*:)""")
            decorated = argRegex.replace(decorated) { aMatch ->
                val rawArg = aMatch.groupValues[1]
                val ptrInfo = aMatch.groupValues[2]
                val sep = aMatch.groupValues[3]
                val idx = if (rawArg.contains("Arg0")) 0 else Regex("""\d+""").find(rawArg)?.value?.toIntOrNull() ?: -1
                val p = currentFn.params.firstOrNull { it.idx == idx }
                    ?: if (idx > 0) currentFn.params.firstOrNull { it.reg == "r${idx + 1}" || it.reg == "x$idx" } else null
                if (p != null) {
                    val label = if (p.name == "this" && p.type.isNotBlank() && p.type != "dynamic") {
                        "this: ${p.type}"
                    } else if (p.name.isNotBlank()) {
                        p.name
                    } else {
                        "arg$idx"
                    }
                    if (ptrInfo.isNotBlank()) {
                        "Arg$idx ($label, $ptrInfo)$sep"
                    } else {
                        "Arg$idx ($label)$sep"
                    }
                } else {
                    aMatch.value
                }
            }

            // Stack slot parameters: e.g. [FP-0x60] or [FP+0x18]
            val slotRegex = Regex("""(\[FP[+-]0x[0-9a-fA-F]+\])(?:\s*\((0x[0-9a-fA-F]+)\))?(\s*:)""")
            decorated = slotRegex.replace(decorated) { sMatch ->
                val rawSlot = sMatch.groupValues[1].lowercase()
                val ptrInfo = sMatch.groupValues[2]
                val sep = sMatch.groupValues[3]
                val p = currentFn.params.firstOrNull { it.slot.isNotBlank() && rawSlot.contains(it.slot.lowercase().removePrefix("fp")) }
                if (p != null && p.name.isNotBlank() && p.name != "_") {
                    val label = p.name
                    if (ptrInfo.isNotBlank()) {
                        "$rawSlot ($label, $ptrInfo)$sep"
                    } else {
                        "$rawSlot ($label)$sep"
                    }
                } else {
                    sMatch.value
                }
            }
        }

        // 5. Decorate RETURN: <offset or name>
        val retRegex = Regex("""(RETURN:\s+)(0x[0-9a-fA-F]+|[A-Za-z0-9_$.:]+)""")
        decorated = retRegex.replace(decorated) { match ->
            val prefix = match.groupValues[1]
            val token = match.groupValues[2]
            val fn = findFunction(token)
            if (fn != null) {
                if (token.startsWith("0x", ignoreCase = true)) {
                    "$prefix$token [${fn.name}]"
                } else {
                    "$prefix${fn.name}"
                }
            } else {
                match.value
            }
        }

        // 6. Decorate Instance field offsets with variable names from Blutter symbols:
        // Matches `+0x<hex>:` or `fld+0x<hex>=` or `fld+0x<hex>:`
        // Finds the closest preceding cid=(0x<cid>) to resolve the class field name.
        val fieldOffsetRegex = Regex("""(?:\+|fld\+)(0x[0-9a-fA-F]+)([:=])""")
        val cidSearchRegex = Regex("""cid=(0x[0-9a-fA-F]+)""")
        decorated = fieldOffsetRegex.replace(decorated) { fMatch ->
            val off = fMatch.groupValues[1]
            val sep = fMatch.groupValues[2]
            val precedingText = decorated.substring(0, fMatch.range.first)
            val lastCidMatch = cidSearchRegex.findAll(precedingText).lastOrNull()
            if (lastCidMatch != null) {
                val cid = lastCidMatch.groupValues[1]
                val fieldName = resolveField(cid, off)
                if (fieldName != null) {
                    return@replace "[$fieldName (+$off)]$sep"
                }
            }
            fMatch.value
        }

        return decorated
    }
}
