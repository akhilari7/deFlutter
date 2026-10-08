package com.akhilari.deflutter.data.models

data class FunctionEntry(
    val name: String = "",
    val absAddr: String = "",
    val offset: String = "",
    val xrefAddr: String = ""
) {
    /**
     * Checks if search query matches name or offset
     */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        val qNoUnderscore = q.replace("_", "")
        val nameLower = name.lowercase()
        val nameNoUnderscore = nameLower.replace("_", "")

        return nameLower.contains(q) ||
               (qNoUnderscore.length >= 3 && nameNoUnderscore.contains(qNoUnderscore)) ||
               offset.lowercase().contains(q) ||
               absAddr.lowercase().contains(q)
    }
}
