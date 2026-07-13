package com.org.refactor.plugin.model

data class ComponentInfo(
    val file: SourceFile,
    val className: String,
    val fqn: String,
    val packageName: String,
    val componentType: ComponentType,
    val declarationOffset: Int,
    val isTopLevel: Boolean,
    val isAbstract: Boolean = false,
    val isGenerated: Boolean = false,
)

enum class ComponentType {
    CLASS, INTERFACE, OBJECT, ENUM, ANNOTATION;
}
