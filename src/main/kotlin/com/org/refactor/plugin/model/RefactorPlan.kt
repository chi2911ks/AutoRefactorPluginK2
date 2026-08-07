package com.org.refactor.plugin.model

data class RefactorPlan(
    val options: RefactorOptions,
    val componentRenames: List<ComponentRename>,
    val symbolRenames: List<SymbolRename>,
    val typeAliasRenames: List<TypeAliasRename> = emptyList(),
    val stringResourceRenames: List<StringResourceRename> = emptyList(),
    val valueXmlFileGroups: List<ValueXmlFileGroup> = emptyList(),
    val resourceRenames: List<ResourceRename> = emptyList(),
    val fileRenames: List<FileRename>,
    val shuffleFilePaths: List<String>,
    val totalClasses: Int,
    val totalSymbols: Int,
    val totalReferences: Int,
    val totalTypeAliases: Int = typeAliasRenames.size,
) {
    val suffix: String
        get() = options.suffixToAdd
}

data class TypeAliasRename(
    val oldName: String,
    val newName: String,
    val fqn: String,
    val sourceFile: String,
    val declarationOffset: Int,
    val ownerScope: String,
    val checked: Boolean = true,
    val skipReason: String? = null,
)

data class StringResourceRename(
    val type: AndroidResourceType = AndroidResourceType.STRING,
    val moduleName: String,
    val oldName: String,
    val newName: String,
    val variants: List<StringResourceVariant>,
    val checked: Boolean = false,
    val selectable: Boolean = true,
    val skipReason: String? = null,
    val blockedByValueFileSelection: Boolean = false,
    val checkedBeforeValueFileExclusion: Boolean? = null,
)

data class StringResourceVariant(
    val sourceFile: String,
    val valuesDirectory: String,
)

data class ResourceRename(
    val type: AndroidResourceType,
    val moduleName: String,
    val oldName: String,
    val newName: String,
    val variants: List<ResourceFileRename>,
    val oldBindingName: String? = null,
    val newBindingName: String? = null,
    val checked: Boolean = true,
    val skipReason: String? = null,
)

data class ResourceFileRename(
    val oldPath: String,
    val newPath: String,
    val newFileName: String,
    val qualifierDirectory: String,
)

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
