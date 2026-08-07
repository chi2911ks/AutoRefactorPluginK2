package com.org.refactor.plugin.plan

import com.org.refactor.plugin.model.ComponentInfo
import com.org.refactor.plugin.model.ComponentRename
import com.org.refactor.plugin.model.FileRename
import com.org.refactor.plugin.model.AndroidResourceFile
import com.org.refactor.plugin.model.AndroidResourceType
import com.org.refactor.plugin.model.ModuleSelection
import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.RefactorPlan
import com.org.refactor.plugin.model.ResourceFileRename
import com.org.refactor.plugin.model.ResourceRename
import com.org.refactor.plugin.model.SymbolInfo
import com.org.refactor.plugin.model.SymbolKind
import com.org.refactor.plugin.model.SymbolRename
import com.org.refactor.plugin.model.TypeAliasInfo
import com.org.refactor.plugin.model.TypeAliasRename
import com.org.refactor.plugin.model.StringResourceInfo
import com.org.refactor.plugin.model.StringResourceRename
import com.org.refactor.plugin.model.StringResourceVariant
import com.org.refactor.plugin.model.ValueXmlFileGroup
import com.org.refactor.plugin.model.ValueXmlFileInfo
import com.org.refactor.plugin.model.ValueXmlFileVariant
import java.io.File

class RefactorPlanGenerator(private val options: RefactorOptions) {

    fun generate(
        components: List<ComponentInfo>,
        symbols: List<SymbolInfo>,
        allKotlinPaths: List<String>,
        resources: List<AndroidResourceFile> = emptyList(),
        typeAliases: List<TypeAliasInfo> = emptyList(),
        strings: List<StringResourceInfo> = emptyList(),
        valueXmlFiles: List<ValueXmlFileInfo> = emptyList(),
    ): RefactorPlan {
        val componentRenames = if (options.refactorClasses) {
            components.mapNotNull(::classRename)
        } else {
            emptyList()
        }

        val symbolRenames = symbols.mapNotNull { symbol ->
            val selected = when (symbol.kind) {
                SymbolKind.FUNCTION -> options.refactorFunctions
                SymbolKind.PROPERTY, SymbolKind.FIELD -> options.refactorVariables
                else -> false
            }
            if (!selected) return@mapNotNull null
            val newName = transformName(symbol.name)
            if (newName == symbol.name) return@mapNotNull null
            SymbolRename(
                oldName = symbol.name,
                newName = newName,
                fqn = symbol.fqn,
                kind = symbol.kind,
                sourceFile = symbol.sourceFile,
                declarationOffset = symbol.declarationOffset,
                ownerScope = symbol.parentClassFqn ?: symbol.sourceFile,
            )
        }

        val fileRenames = componentRenames.mapNotNull { rename ->
            val component = components.firstOrNull {
                it.fqn == rename.fqn && it.declarationOffset == rename.declarationOffset
            } ?: return@mapNotNull null
            if (!component.isTopLevel) return@mapNotNull null
            val source = File(component.file.absolutePath)
            if (source.nameWithoutExtension != component.className) return@mapNotNull null
            val newFileName = "${rename.newName}.${source.extension}"
            FileRename(
                oldPath = component.file.absolutePath,
                newPath = File(source.parentFile, newFileName).path.replace('\\', '/'),
                newFileName = newFileName,
                reason = "class renamed: ${rename.oldName} -> ${rename.newName}",
            )
        }.distinctBy { it.oldPath }

        val renameTargetFiles = (componentRenames.map { it.sourceFile } + symbolRenames.map { it.sourceFile })
            .distinct()
        val resourceRenames = buildResourceRenames(resources)
        val typeAliasRenames = buildTypeAliasRenames(typeAliases)
        val stringResourceRenames = buildStringResourceRenames(strings)
        val shuffleFilePaths = when {
            !options.hasShuffleOperation -> emptyList()
            options.hasRefactorOperation -> renameTargetFiles
            else -> allKotlinPaths.distinct()
        }

        return RefactorPlan(
            options = options,
            componentRenames = componentRenames,
            symbolRenames = symbolRenames,
            typeAliasRenames = typeAliasRenames,
            stringResourceRenames = stringResourceRenames,
            valueXmlFileGroups = buildValueXmlFileGroups(valueXmlFiles),
            resourceRenames = resourceRenames,
            fileRenames = fileRenames,
            shuffleFilePaths = shuffleFilePaths,
            totalClasses = componentRenames.size,
            totalSymbols = symbolRenames.size,
            totalReferences = 0,
            totalTypeAliases = typeAliasRenames.size,
        )
    }

    private fun buildValueXmlFileGroups(files: List<ValueXmlFileInfo>): List<ValueXmlFileGroup> =
        files.groupBy { file ->
            ValueXmlFileKey(ModuleSelection.logicalName(file.moduleName), file.fileName.lowercase())
        }.map { (key, variants) ->
            ValueXmlFileGroup(
                moduleName = key.moduleName,
                fileName = variants.first().fileName,
                variants = variants.map { variant ->
                    ValueXmlFileVariant(
                        sourceFile = variant.sourceFile,
                        valuesDirectory = variant.valuesDirectory,
                        isWritable = variant.isWritable,
                    )
                }.distinctBy { it.sourceFile }
                    .sortedWith(compareBy({ it.valuesDirectory }, { it.sourceFile })),
                checked = true,
            )
        }.sortedWith(compareBy({ it.moduleName }, { it.fileName.lowercase() }))

    private fun buildStringResourceRenames(strings: List<StringResourceInfo>): List<StringResourceRename> {
        if (!options.refactorStrings && !options.refactorColors && !options.refactorStyles) return emptyList()
        val enabledStrings = strings.filter {
            when (it.type) {
                AndroidResourceType.STRING -> options.refactorStrings
                AndroidResourceType.COLOR -> options.refactorColors
                AndroidResourceType.STYLE -> options.refactorStyles
                else -> false
            }
        }
        val groups = enabledStrings.groupBy { string ->
            StringResourceKey(ModuleSelection.logicalName(string.moduleName), string.type, string.name)
        }
        val existingKeys = groups.keys.toSet()
        return groups.entries.mapNotNull { (key, variants) ->
            val newName = transformValueResourceName(key.type, key.name)
            if (newName == key.name) return@mapNotNull null
            val collision = StringResourceKey(key.moduleName, key.type, newName) in existingKeys
            val readOnly = variants.any { !it.isWritable }
            val skipReason = when {
                collision -> "String resource '$newName' already exists"
                readOnly -> "At least one locale variant is read-only"
                else -> null
            }
            StringResourceRename(
                type = key.type,
                moduleName = key.moduleName,
                oldName = key.name,
                newName = newName,
                variants = variants.map { variant ->
                    StringResourceVariant(variant.sourceFile, variant.valuesDirectory)
                }.distinct().sortedWith(compareBy({ it.valuesDirectory }, { it.sourceFile })),
                checked = skipReason == null,
                selectable = skipReason == null,
                skipReason = skipReason,
            )
        }.sortedWith(compareBy({ it.moduleName }, { it.oldName }))
    }

    private fun buildTypeAliasRenames(typeAliases: List<TypeAliasInfo>): List<TypeAliasRename> {
        if (!options.refactorTypeAliases) return emptyList()
        val existingNames = typeAliases.groupBy { it.ownerScope }.mapValues { (_, aliases) ->
            aliases.map { it.name }.toSet()
        }
        val candidates = typeAliases.mapNotNull { alias ->
            val newName = transformName(alias.name)
            if (newName == alias.name) return@mapNotNull null
            val collision = newName in existingNames[alias.ownerScope].orEmpty()
            TypeAliasRename(
                oldName = alias.name,
                newName = newName,
                fqn = alias.fqn,
                sourceFile = alias.sourceFile,
                declarationOffset = alias.declarationOffset,
                ownerScope = alias.ownerScope,
                checked = !collision,
                skipReason = if (collision) "Typealias '$newName' already exists in ${alias.ownerScope}" else null,
            )
        }
        val duplicateTargets = candidates.filter { it.checked }
            .groupBy { it.ownerScope to it.newName }
            .filterValues { it.size > 1 }
            .keys
        return candidates.map { rename ->
            if (rename.ownerScope to rename.newName !in duplicateTargets) rename else rename.copy(
                checked = false,
                skipReason = "Multiple typealiases would be renamed to '${rename.newName}'",
            )
        }
    }

    private fun buildResourceRenames(resources: List<AndroidResourceFile>): List<ResourceRename> {
        val selected = resources.filter { resource ->
            when (resource.type) {
                AndroidResourceType.DRAWABLE -> options.refactorDrawables
                AndroidResourceType.LAYOUT -> options.refactorLayouts
                AndroidResourceType.STRING -> false
                AndroidResourceType.COLOR -> false
                AndroidResourceType.STYLE -> false
            }
        }
        val groups = selected.groupBy { resource ->
            ResourceKey(
                ModuleSelection.logicalName(resource.moduleName),
                resource.type,
                resource.resourceName,
            )
        }
        val existingKeys = groups.keys.toSet()

        return groups.entries.mapNotNull { (key, variants) ->
            val newName = transformResourceName(key.name)
            if (newName == key.name) return@mapNotNull null

            val files = variants.sortedBy { it.absolutePath }.map { resource ->
                val source = File(resource.absolutePath)
                val newFileName = newName + resource.fileSuffix
                ResourceFileRename(
                    oldPath = resource.absolutePath,
                    newPath = File(source.parentFile, newFileName).path.replace('\\', '/'),
                    newFileName = newFileName,
                    qualifierDirectory = resource.qualifierDirectory,
                )
            }
            val logicalCollision = ResourceKey(key.moduleName, key.type, newName) in existingKeys
            val fileCollision = files.any { file ->
                File(file.newPath).exists() && file.newPath !in files.map { it.oldPath }.toSet()
            }
            val readOnly = variants.any { !File(it.absolutePath).canWrite() }
            val skipReason = when {
                logicalCollision || fileCollision -> "Target resource '$newName' already exists"
                readOnly -> "At least one qualifier variant is read-only"
                else -> null
            }
            val hasViewBinding = key.type == AndroidResourceType.LAYOUT &&
                variants.any { resource -> !isViewBindingIgnored(resource.absolutePath) }
            ResourceRename(
                type = key.type,
                moduleName = key.moduleName,
                oldName = key.name,
                newName = newName,
                variants = files,
                oldBindingName = key.name.takeIf { hasViewBinding }
                    ?.let(::bindingClassName),
                newBindingName = newName.takeIf { hasViewBinding }
                    ?.let(::bindingClassName),
                checked = skipReason == null,
                skipReason = skipReason,
            )
        }.sortedWith(compareBy({ it.moduleName }, { it.type.name }, { it.oldName }))
    }

    internal fun transformResourceName(oldName: String): String {
        val removeText = options.suffixToRemove.lowercase()
        val add = normalizeResourceSegments(options.suffixToAdd.lowercase())
        val withoutText = removeAllIgnoreCase(oldName.lowercase(), removeText)
        val base = normalizeResourceSegments(withoutText)
        if (add.isEmpty()) return base
        if (base.equals(add, ignoreCase = true) || base.endsWith("_$add", ignoreCase = true)) {
            return base
        }
        return if (base.isEmpty()) add else "${base}_$add"
    }

    internal fun transformStringResourceName(oldName: String): String {
        val removeText = options.suffixToRemove.lowercase()
        val prefix = normalizeResourceSegments(options.suffixToAdd.lowercase())
        val base = normalizeResourceSegments(removeAllIgnoreCase(oldName.lowercase(), removeText))
        if (prefix.isEmpty()) return base
        if (base.equals(prefix, ignoreCase = true) || base.startsWith("${prefix}_", ignoreCase = true)) {
            return base
        }
        return if (base.isEmpty()) prefix else "${prefix}_$base"
    }

    internal fun transformValueResourceName(type: AndroidResourceType, oldName: String): String {
        if (type != AndroidResourceType.STYLE) return transformStringResourceName(oldName)
        val removeText = options.suffixToRemove
        val prefix = normalizeResourceSegments(options.suffixToAdd.lowercase())
        val base = removeAllIgnoreCase(oldName, removeText)
            .replace(Regex("_+"), "_")
            .trim('_')
        if (prefix.isEmpty()) return base
        if (base.equals(prefix, ignoreCase = true) || base.startsWith("${prefix}_", ignoreCase = true)) {
            return base
        }
        return if (base.isEmpty()) prefix else "${prefix}_$base"
    }

    internal fun bindingClassName(resourceName: String): String =
        resourceName.split('_')
            .filter { it.isNotEmpty() }
            .joinToString("") { segment -> segment.replaceFirstChar { it.uppercase() } } + "Binding"

    private fun isViewBindingIgnored(path: String): Boolean = try {
        Regex("tools:viewBindingIgnore\\s*=\\s*[\"']true[\"']", RegexOption.IGNORE_CASE)
            .containsMatchIn(File(path).readText())
    } catch (_: Exception) {
        false
    }

    private data class ResourceKey(
        val moduleName: String,
        val type: AndroidResourceType,
        val name: String,
    )

    private data class StringResourceKey(
        val moduleName: String,
        val type: AndroidResourceType,
        val name: String,
    )

    private data class ValueXmlFileKey(
        val moduleName: String,
        val fileName: String,
    )

    internal fun transformName(oldName: String): String {
        val remove = options.suffixToRemove
        val add = options.suffixToAdd
        val base = removeAllIgnoreCase(oldName, remove)
        if (add.isEmpty() || base.endsWith(add, ignoreCase = true)) return base
        return base + add
    }

    private fun removeAllIgnoreCase(value: String, textToRemove: String): String {
        if (textToRemove.isEmpty()) return value
        return Regex(Regex.escape(textToRemove), RegexOption.IGNORE_CASE).replace(value, "")
    }

    private fun normalizeResourceSegments(value: String): String =
        value.replace(Regex("_+"), "_").trim('_')

    private fun classRename(component: ComponentInfo): ComponentRename? {
        val newName = transformName(component.className)
        if (newName == component.className) return null
        return ComponentRename(
            oldName = component.className,
            newName = newName,
            fqn = component.fqn,
            componentType = component.componentType,
            sourceFile = component.file.absolutePath,
            declarationOffset = component.declarationOffset,
        )
    }
}
