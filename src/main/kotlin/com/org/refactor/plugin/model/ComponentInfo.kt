package com.org.refactor.plugin.model

data class ComponentInfo(
    val file: SourceFile,
    val className: String,
    val superClass: String,
    val superClassShort: String,
    val packageName: String,
    val componentType: ComponentType,
    val isAbstract: Boolean = false,
    val isGenerated: Boolean = false,
)

enum class ComponentType {
    ACTIVITY, FRAGMENT, DIALOG, DIALOG_FRAGMENT, BOTTOM_SHEET_DIALOG_FRAGMENT;
}
