package com.payatu.flutterinspect.data.models

import org.json.JSONArray
import org.json.JSONObject

data class HookTargetConfig(
    val offset: Long,
    val name: String,
    val traceReturn: Boolean = true,
    val params: List<String> = emptyList()
)

data class InspectConfig(
    val process: String = "",
    val mode: String = "keyword_search", // "keyword_search" or "full_enumerate"
    val keywords: List<String> = emptyList(),
    val targetLibs: List<String> = listOf("libapp.so"),
    val hooks: List<HookTargetConfig> = emptyList(),
    val discoveryEnabled: Boolean = false
) {
    fun toJsonString(): String {
        val root = JSONObject()

        // Write as "target_packages" array — matches what the C++ module_config parser reads
        val pkgArr = JSONArray()
        if (process.isNotBlank()) {
            pkgArr.put(process)
        }
        root.put("target_packages", pkgArr)

        // Also write "target_process" string for backwards compatibility / readability
        root.put("target_process", process)

        root.put("mode", mode)
        root.put("discovery_enabled", discoveryEnabled)

        val kwArr = JSONArray()
        keywords.forEach { kwArr.put(it) }
        root.put("keywords", kwArr)

        val libArr = JSONArray()
        targetLibs.forEach { libArr.put(it) }
        root.put("target_libs", libArr)

        val hookArr = JSONArray()
        hooks.forEach { h ->
            val hObj = JSONObject()
            hObj.put("offset", h.offset)
            hObj.put("name", h.name)
            hObj.put("trace_return", h.traceReturn)
            if (h.params.isNotEmpty()) {
                val pArr = JSONArray()
                h.params.forEach { pArr.put(it) }
                hObj.put("params", pArr)
            }
            hookArr.put(hObj)
        }
        root.put("hooks", hookArr)

        return root.toString(2)
    }

    companion object {
        fun fromJsonString(jsonStr: String): InspectConfig {
            return try {
                val obj = JSONObject(jsonStr)
                val targetPkgs = obj.optJSONArray("target_packages")
                val firstPkg = if (targetPkgs != null && targetPkgs.length() > 0) targetPkgs.optString(0, "") else ""
                val proc = obj.optString("target_process", obj.optString("process", firstPkg)).ifBlank { firstPkg }
                val m = obj.optString("mode", "keyword_search")
                val disc = obj.optBoolean("discovery_enabled", false)

                val kws = mutableListOf<String>()
                val kwArr = obj.optJSONArray("keywords")
                if (kwArr != null) {
                    for (i in 0 until kwArr.length()) {
                        kws.add(kwArr.getString(i))
                    }
                }

                val libs = mutableListOf<String>()
                val libArr = obj.optJSONArray("target_libs")
                if (libArr != null) {
                    for (i in 0 until libArr.length()) {
                        libs.add(libArr.getString(i))
                    }
                }
                if (libs.isEmpty()) libs.add("libapp.so")

                val hks = mutableListOf<HookTargetConfig>()
                val hookArr = obj.optJSONArray("hooks")
                if (hookArr != null) {
                    for (i in 0 until hookArr.length()) {
                        val hObj = hookArr.optJSONObject(i)
                        if (hObj != null) {
                            val pList = mutableListOf<String>()
                            val pArr = hObj.optJSONArray("params")
                            if (pArr != null) {
                                for (j in 0 until pArr.length()) {
                                    pList.add(pArr.getString(j))
                                }
                            }
                            hks.add(
                                HookTargetConfig(
                                    offset = hObj.optLong("offset", 0L),
                                    name = hObj.optString("name", "func"),
                                    traceReturn = hObj.optBoolean("trace_return", true),
                                    params = pList
                                )
                            )
                        }
                    }
                }

                InspectConfig(
                    process = proc,
                    mode = m,
                    keywords = kws,
                    targetLibs = libs,
                    hooks = hks,
                    discoveryEnabled = disc
                )
            } catch (e: Exception) {
                InspectConfig()
            }
        }
    }
}
