package com.org.refactor.plugin.verification

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiImportList
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.org.refactor.plugin.model.RefactorPlan

data class VerificationResult(
    val passed: Boolean, val psiErrors: List<String>,
    val duplicateSymbols: List<String>, val brokenImports: List<String>,
    val checksRun: Int, val checksPassed: Int,
)

class VerificationEngine(private val project: Project) {

    fun verify(plan: RefactorPlan): VerificationResult =
        ReadAction.compute<VerificationResult, RuntimeException> { doVerify(plan) }

    /** Verifies affected files in one pass instead of scanning every Kotlin file three times. */
    private fun doVerify(plan: RefactorPlan): VerificationResult {
        val psiErrors = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        val badImports = mutableListOf<String>()
        val classCounts = mutableMapOf<String, MutableList<String>>()
        val newClassNames = plan.componentRenames.map { it.newName }.toSet()
        val renameMap = plan.fileRenames.associate { it.oldPath to it.newPath }
        val affectedPaths = (
            plan.componentRenames.map { it.sourceFile } +
                plan.symbolRenames.map { it.sourceFile } +
                plan.shuffleFilePaths
            )
            .map { renameMap[it] ?: it }
            .distinct()

        for (path in affectedPaths) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path) ?: continue
            val psi = PsiManager.getInstance(project).findFile(virtualFile) ?: continue

            for (error in PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)) {
                psiErrors.add("PSI ${virtualFile.name}:${line(error)}: ${error.errorDescription}")
            }

            if (psi is PsiClassOwner) {
                for (psiClass in psi.classes) {
                    val name = psiClass.name ?: continue
                    if (name in newClassNames) {
                        classCounts.getOrPut(name) { mutableListOf() }.add(virtualFile.path)
                    }
                }
            }

            for (importList in psi.children.filterIsInstance<PsiImportList>()) {
                for (statement in importList.allImportStatements) {
                    val reference = statement.importReference ?: continue
                    if (!statement.isOnDemand &&
                        (reference as? PsiPolyVariantReference)?.multiResolve(false)?.isEmpty() != false
                    ) {
                        badImports.add("Import ${virtualFile.name}:${line(statement)}: ${reference.canonicalText}")
                    }
                }
            }
        }

        for ((name, files) in classCounts) {
            if (files.size > 1) duplicates.add("Dup '$name': ${files.joinToString()}")
        }

        val results = listOf(psiErrors.isEmpty(), duplicates.isEmpty(), badImports.isEmpty())
        return VerificationResult(
            passed = results.all { it },
            psiErrors = psiErrors,
            duplicateSymbols = duplicates,
            brokenImports = badImports,
            checksRun = results.size,
            checksPassed = results.count { it },
        )
    }

    private fun line(element: PsiElement): Int {
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return -1
        return document.getLineNumber(element.textRange.startOffset) + 1
    }
}
