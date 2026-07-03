package com.org.refactor.plugin.plan

import com.org.refactor.plugin.model.*

class RefactorPlanGenerator(private val suffix: String) {

    fun generate(
        components: List<ComponentInfo>,
        symbols: List<SymbolInfo>,
    ): RefactorPlan {
        val componentRenames = mutableListOf<ComponentRename>()
        val symbolRenames = mutableListOf<SymbolRename>()
        val fileRenames = mutableListOf<FileRename>()

        val symbolsByClass = symbols.groupBy { it.parentClassFqn }

        for (component in components) {
            val fqn = "${component.packageName}.${component.className}"

            // ⛔ Idempotency: skip if already renamed
            if (component.className.endsWith(suffix)) continue

            val newClassName = component.className + suffix

            componentRenames.add(ComponentRename(
                oldName = component.className,
                newName = newClassName,
                fqn = fqn,
                componentType = component.componentType,
                sourceFile = component.file.absolutePath,
            ))

            // File rename if filename matches class name
            val fileName = component.file.absolutePath.substringAfterLast('/')
            val baseName = fileName.substringBeforeLast('.')
            if (baseName == component.className) {
                val newFileName = fileName.replace(component.className, newClassName)
                val newPath = component.file.absolutePath.replace(fileName, newFileName)
                fileRenames.add(FileRename(
                    oldPath = component.file.absolutePath,
                    newPath = newPath,
                    newFileName = newFileName,
                    reason = "class renamed: ${component.className} -> $newClassName",
                ))
            }

            // Symbol renames for this component
            val classSymbols = symbolsByClass[fqn] ?: emptyList()
            for (symbol in classSymbols) {
                symbolRenames.add(SymbolRename(
                    oldName = symbol.name,
                    newName = symbol.name + suffix,
                    fqn = symbol.fqn,
                    kind = symbol.kind,
                    sourceFile = symbol.sourceFile,
                ))
            }
        }

        return RefactorPlan(
            suffix = suffix,
            componentRenames = componentRenames,
            symbolRenames = symbolRenames,
            fileRenames = fileRenames,
            totalClasses = componentRenames.size,
            totalSymbols = symbolRenames.size,
            totalReferences = 0,
        )
    }
}
