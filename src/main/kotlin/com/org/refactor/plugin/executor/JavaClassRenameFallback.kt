package com.org.refactor.plugin.executor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch

/** Repairs a Java class that RenameProcessor silently left unchanged. */
internal object JavaClassRenameFallback {

    fun rename(project: Project, declaration: PsiClass, newName: String): Boolean {
        if (!declaration.isValid || declaration.name == null) return false
        if (declaration.name == newName) return true
        // Explicit constructor declarations need Java's full rename processor. Both a plain
        // abstract class and an interface can be safely repaired without that extra edit.
        if (declaration.constructors.isNotEmpty()) return false

        val references = ReferencesSearch.search(declaration, GlobalSearchScope.projectScope(project))
            .findAll()
            .filter { it.element.isPhysical }
        if (!declaration.containingFile.virtualFile.isWritable ||
            references.any { it.element.containingFile?.virtualFile?.isWritable != true }
        ) return false

        val byFile = references.groupBy { it.element.containingFile.virtualFile.path }
        WriteCommandAction.runWriteCommandAction(project) {
            for (fileReferences in byFile.values) {
                for (reference in fileReferences.sortedByDescending { it.getRangeInElementStart() }) {
                    if (reference.element.isValid) reference.handleElementRename(newName)
                }
            }
            declaration.setName(newName)
        }
        return declaration.isValid && declaration.name == newName
    }

    private fun PsiReference.getRangeInElementStart(): Int =
        element.textRange.startOffset + rangeInElement.startOffset
}
