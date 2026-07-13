package com.org.refactor.plugin.plan

import com.org.refactor.plugin.model.ComponentInfo
import com.org.refactor.plugin.model.ComponentRename
import com.org.refactor.plugin.model.FileRename
import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.RefactorPlan
import com.org.refactor.plugin.model.SymbolInfo
import com.org.refactor.plugin.model.SymbolKind
import com.org.refactor.plugin.model.SymbolRename
import java.io.File

class RefactorPlanGenerator(private val options: RefactorOptions) {

    fun generate(
        components: List<ComponentInfo>,
        symbols: List<SymbolInfo>,
        allKotlinPaths: List<String>,
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
        val shuffleFilePaths = when {
            !options.hasShuffleOperation -> emptyList()
            options.hasRefactorOperation -> renameTargetFiles
            else -> allKotlinPaths.distinct()
        }

        return RefactorPlan(
            options = options,
            componentRenames = componentRenames,
            symbolRenames = symbolRenames,
            fileRenames = fileRenames,
            shuffleFilePaths = shuffleFilePaths,
            totalClasses = componentRenames.size,
            totalSymbols = symbolRenames.size,
            totalReferences = 0,
        )
    }

    internal fun transformName(oldName: String): String {
        val remove = options.suffixToRemove
        val add = options.suffixToAdd
        if (add.isNotEmpty() && oldName.endsWith(add) && (remove.isEmpty() || !oldName.endsWith(remove))) {
            return oldName
        }
        val base = if (remove.isNotEmpty() && oldName.endsWith(remove)) {
            oldName.dropLast(remove.length)
        } else {
            oldName
        }
        return base + add
    }

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
