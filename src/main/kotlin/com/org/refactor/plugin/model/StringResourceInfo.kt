package com.org.refactor.plugin.model

data class StringResourceInfo(
    val name: String,
    val type: AndroidResourceType = AndroidResourceType.STRING,
    val moduleName: String,
    val sourceFile: String,
    val valuesDirectory: String,
    val isWritable: Boolean = true,
)
