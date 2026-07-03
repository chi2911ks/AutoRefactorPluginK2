package com.org.refactor.plugin.model

data class SymbolInfo(
    val name: String,
    val fqn: String,
    val kind: SymbolKind,
    val psiElementClass: String,
    val sourceFile: String,
    val lineNumber: Int,
    val isOverride: Boolean = false,
    val isAndroidCallback: Boolean = false,
    val parentClassFqn: String? = null,
)

enum class SymbolKind {
    CLASS, FUNCTION, PROPERTY, FIELD, CONSTRUCTOR,
    COMPANION_OBJECT_MEMBER, NESTED_CLASS, ANONYMOUS_CLASS
}
