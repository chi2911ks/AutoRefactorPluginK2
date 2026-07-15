package com.org.refactor.plugin.scanner

import com.org.refactor.plugin.model.AndroidResourceFile
import com.org.refactor.plugin.model.AndroidResourceType

internal object AndroidResourceParser {
    fun parse(path: String, moduleName: String): AndroidResourceFile? {
        val normalized = path.replace('\\', '/')
        val match = RESOURCE_PATH.find(normalized) ?: return null
        val directory = match.groupValues[1]
        val fileName = match.groupValues[2]
        val type = when {
            directory == "layout" || directory.startsWith("layout-") -> AndroidResourceType.LAYOUT
            directory == "drawable" || directory.startsWith("drawable-") -> AndroidResourceType.DRAWABLE
            else -> return null
        }
        val lowerName = fileName.lowercase()
        if (type == AndroidResourceType.LAYOUT && !lowerName.endsWith(".xml")) return null
        if (type == AndroidResourceType.DRAWABLE && SUPPORTED_DRAWABLE_SUFFIXES.none(lowerName::endsWith)) {
            return null
        }
        val suffix = if (lowerName.endsWith(".9.png")) ".9.png" else ".${fileName.substringAfterLast('.')}"
        val resourceName = fileName.dropLast(suffix.length)
        if (!RESOURCE_NAME.matches(resourceName)) return null
        return AndroidResourceFile(
            absolutePath = normalized,
            moduleName = moduleName,
            type = type,
            resourceName = resourceName,
            qualifierDirectory = directory,
            fileSuffix = suffix,
        )
    }

    private val RESOURCE_PATH =
        Regex("/res/((?:layout|drawable)(?:-[^/]+)?)/([^/]+)$", RegexOption.IGNORE_CASE)
    private val RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")
    private val SUPPORTED_DRAWABLE_SUFFIXES =
        setOf(".xml", ".png", ".9.png", ".webp", ".jpg", ".jpeg", ".gif")
}
