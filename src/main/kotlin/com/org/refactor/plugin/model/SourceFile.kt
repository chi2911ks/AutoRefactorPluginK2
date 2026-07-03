package com.org.refactor.plugin.model

data class SourceFile(
    val virtualFilePath: String,
    val absolutePath: String,
    val moduleName: String,
    val fileType: FileType,
    val packageName: String? = null,
)

enum class FileType {
    KOTLIN, JAVA, XML_LAYOUT, XML_MANIFEST, XML_NAVIGATION, GRADLE, OTHER
}
