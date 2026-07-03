package com.org.refactor.plugin.verification

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.project.Project
import com.org.refactor.plugin.model.ComponentRename

data class VerificationResult(
    val passed: Boolean, val psiErrors: List<String>,
    val duplicateSymbols: List<String>, val brokenImports: List<String>,
    val checksRun: Int, val checksPassed: Int,
)

class VerificationEngine(private val project: Project) {

    fun verify(renamed: List<ComponentRename>): VerificationResult {
        return ReadAction.compute<VerificationResult, RuntimeException> {
            doVerify(renamed)
        }
    }

    private fun doVerify(renamed: List<ComponentRename>): VerificationResult {
        val psiErrors = mutableListOf<String>()
        val dups = mutableListOf<String>()
        val badImports = mutableListOf<String>()

        val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.projectScope(project))

        for (vf in ktFiles) {
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            for (e in PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)) {
                psiErrors.add("PSI ${vf.name}:${line(e)}: ${e.errorDescription}")
            }
        }

        val counts = mutableMapOf<String, MutableList<String>>()
        for (vf in ktFiles) {
            val psi = PsiManager.getInstance(project).findFile(vf) as? PsiClassOwner ?: continue
            for (c in psi.classes) {
                counts.getOrPut(c.name ?: "") { mutableListOf() }.add(vf.path)
            }
        }
        val newNames = renamed.map { it.newName }.toSet()
        for ((n, files) in counts) {
            if (files.size > 1 && n in newNames) dups.add("Dup '$n': ${files.joinToString()}")
        }

        for (vf in ktFiles) {
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            for (imp in psi.children.filterIsInstance<PsiImportList>().flatMap { it.allImportStatements.toList() }) {
                if (!imp.isOnDemand && imp.importReference != null &&
                    (imp.importReference as? PsiPolyVariantReference)?.multiResolve(false)?.isEmpty() != false)
                    badImports.add("Import ${vf.name}:${line(imp)}: ${imp.importReference?.canonicalText ?: imp.text}")
            }
        }

        val run = 3
        val ok = listOf(psiErrors.isEmpty(), dups.isEmpty(), badImports.isEmpty()).count { it }
        return VerificationResult(psiErrors.isEmpty() && dups.isEmpty() && badImports.isEmpty(),
            psiErrors, dups, badImports, run, ok)
    }

    private fun line(e: PsiElement): Int {
        val d = PsiDocumentManager.getInstance(project).getDocument(e.containingFile) ?: return -1
        return d.getLineNumber(e.textRange.startOffset) + 1
    }
}
