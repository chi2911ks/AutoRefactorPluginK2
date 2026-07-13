package com.org.refactor.plugin.model

data class RefactorPlan(
    val options: RefactorOptions,
    val componentRenames: List<ComponentRename>,
    val symbolRenames: List<SymbolRename>,
    val fileRenames: List<FileRename>,
    val shuffleFilePaths: List<String>,
    val totalClasses: Int,
    val totalSymbols: Int,
    val totalReferences: Int,
) {
    val suffix: String
        get() = options.suffixToAdd
}

data class ComponentRename(
    val oldName: String,
    val newName: String,
    val fqn: String,
    val componentType: ComponentType,
    val sourceFile: String,
    val declarationOffset: Int,
    val checked: Boolean = true,
)

data class SymbolRename(
    val oldName: String,
    val newName: String,
    val fqn: String,
    val kind: SymbolKind,
    val sourceFile: String,
    val declarationOffset: Int,
    val ownerScope: String,
    val checked: Boolean = true,
)

data class FileRename(
    val oldPath: String,
    val newPath: String,
    val newFileName: String,
    val reason: String,
)
