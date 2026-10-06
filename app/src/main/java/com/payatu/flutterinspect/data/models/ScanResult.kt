package com.payatu.flutterinspect.data.models

data class DartSnapshot(
    val valid: Boolean = false,
    val magic: String = "",
    val kind: String = "",
    val hash: String = "",
    val features: String = "",
    val numClusters: Long = 0,
    val numObjects: Long = 0,
    val numBaseObjects: Long = 0,
    val instructionsOffset: String = "",
    val isolateDataAddr: String = "",
    val isolateInstructionsAddr: String = "",
    val symbolsCount: Int = 0,
    val symbols: List<String> = emptyList()
)

data class ScanResult(
    val process: String = "",
    val lib: String = "",
    val libBase: String = "",
    val count: Int = 0,
    val functions: List<FunctionEntry> = emptyList(),
    val sourcePath: String = "",
    val snapshot: DartSnapshot? = null
)
