package com.org.refactor.plugin.model

data class RefactorPlan(
    val suffix: String,
    val componentRenames: List<ComponentRename>,
    val symbolRenames: List<SymbolRename>,
    val fileRenames: List<FileRename>,
    val totalClasses: Int,
    val totalSymbols: Int,
    val totalReferences: Int,
)

data class ComponentRename(
    val oldName: String,
    val newName: String,
    val fqn: String,
    val componentType: ComponentType,
    val sourceFile: String,
    val checked: Boolean = true,
)

data class SymbolRename(
    val oldName: String,
    val newName: String,
    val fqn: String,
    val kind: SymbolKind,
    val sourceFile: String,
    val checked: Boolean = true,
)

data class FileRename(
    val oldPath: String,
    val newPath: String,
    val newFileName: String,
    val reason: String,
)
