package com.org.refactor.plugin.executor

import com.org.refactor.plugin.model.ResourceRename

internal object ResourceTextUpdater {
    data class Update(val text: String, val replacements: Int)

    fun update(text: String, extension: String?, renames: List<ResourceRename>): Update {
        val codeResourceNames = renames.associate { rename ->
            val oldName = if (rename.type.name == "STYLE") rename.oldName.replace('.', '_') else rename.oldName
            val newName = if (rename.type.name == "STYLE") rename.newName.replace('.', '_') else rename.newName
            (rename.type.name.lowercase() to oldName) to newName
        }
        val xmlResourceNames = renames.associate { rename ->
            (rename.type.name.lowercase() to rename.oldName) to rename.newName
        }
        var updated = text
        var count = 0
        replace(updated, CODE_REFERENCE) { match ->
            val newName = codeResourceNames[match.groupValues[2] to match.groupValues[4]]
            if (newName == null) {
                match.value
            } else {
                count++
                match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + newName
            }
        }.let { updated = it }
        replace(updated, XML_REFERENCE) { match ->
            val newName = xmlResourceNames[match.groupValues[2] to match.groupValues[4]]
            if (newName == null) {
                match.value
            } else {
                count++
                match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + newName
            }
        }.let { updated = it }
        replace(updated, XML_STYLE_PARENT) { match ->
            val newName = xmlResourceNames["style" to match.groupValues[2]]
            if (newName == null) {
                match.value
            } else {
                count++
                match.groupValues[1] + newName + match.groupValues[3]
            }
        }.let { updated = it }

        if (extension.equals("kt", ignoreCase = true)) {
            val bindings = renames.mapNotNull { rename ->
                val oldName = rename.oldBindingName
                val newName = rename.newBindingName
                if (oldName == null || newName == null || oldName == newName) null else oldName to newName
            }.toMap()
            replace(updated, BINDING_IDENTIFIER) { match ->
                bindings[match.value]?.also { count++ } ?: match.value
            }.let { updated = it }
        }
        return Update(updated, count)
    }

    fun containsOldReference(text: String, extension: String?, rename: ResourceRename): Boolean =
        containsOldReference(text, extension, listOf(rename))

    fun containsOldReference(text: String, extension: String?, renames: List<ResourceRename>): Boolean {
        if (renames.isEmpty()) return false
        return update(text, extension, renames).replacements > 0
    }

    private fun replace(
        text: String,
        pattern: Regex,
        transform: (MatchResult) -> CharSequence,
    ): String = pattern.replace(text, transform)

    private val CODE_REFERENCE = Regex(
        "(\\bR\\s*\\.\\s*)(layout|drawable|string|color|style)(\\s*\\.\\s*)([A-Za-z][A-Za-z0-9_]*)",
    )
    private val XML_REFERENCE = Regex("(@)(layout|drawable|string|color|style)(/)([A-Za-z][A-Za-z0-9_.]*)")
    private val XML_STYLE_PARENT = Regex(
        "(\\bparent\\s*=\\s*[\"'])(?!@|android:)([A-Za-z][A-Za-z0-9_.]*)([\"'])",
    )
    private val BINDING_IDENTIFIER = Regex("(?<![A-Za-z0-9_])[A-Z][A-Za-z0-9]*Binding(?![A-Za-z0-9_])")
}
