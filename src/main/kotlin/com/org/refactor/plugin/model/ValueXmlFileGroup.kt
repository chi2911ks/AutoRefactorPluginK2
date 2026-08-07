package com.org.refactor.plugin.model

data class ValueXmlFileInfo(
    val moduleName: String,
    val fileName: String,
    val sourceFile: String,
    val valuesDirectory: String,
    val isWritable: Boolean = true,
)

data class ValueXmlFileGroup(
    val moduleName: String,
    val fileName: String,
    val variants: List<ValueXmlFileVariant>,
    val checked: Boolean = true,
)

data class ValueXmlFileVariant(
    val sourceFile: String,
    val valuesDirectory: String,
    val isWritable: Boolean = true,
)
