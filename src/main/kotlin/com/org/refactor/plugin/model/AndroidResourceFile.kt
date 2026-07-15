package com.org.refactor.plugin.model

data class AndroidResourceFile(
    val absolutePath: String,
    val moduleName: String,
    val type: AndroidResourceType,
    val resourceName: String,
    val qualifierDirectory: String,
    /** Includes the leading dot, for example `.xml` or `.9.png`. */
    val fileSuffix: String,
)

enum class AndroidResourceType {
    DRAWABLE,
    LAYOUT,
    STRING,
    COLOR,
    STYLE,
}
