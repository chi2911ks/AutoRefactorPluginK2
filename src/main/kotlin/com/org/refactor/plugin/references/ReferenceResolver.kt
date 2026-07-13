package com.org.refactor.plugin.references

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.org.refactor.plugin.model.*
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

data class ResolvedReference(
    val symbolName: String, val sourceFile: String, val targetFile: String,
    val lineNumber: Int, val referenceType: ReferenceType, val elementText: String,
)

enum class ReferenceType { CODE_REFERENCE, IMPORT, OVERRIDE, XML_REFERENCE, MANIFEST_REFERENCE, NAVIGATION }

class ReferenceResolver(private val project: Project) {

    fun resolveReferences(symbol: SymbolInfo): List<ResolvedReference> {
        return ReadAction.compute<List<ResolvedReference>, RuntimeException> {
            doResolve(symbol)
        }
    }

    private fun doResolve(symbol: SymbolInfo): List<ResolvedReference> {
        val scope = GlobalSearchScope.projectScope(project)
        val psiElement = findElement(symbol) ?: return emptyList()
        val refs = mutableListOf<ResolvedReference>()

        for (ref in ReferencesSearch.search(psiElement, scope)) {
            val el = ref.element
            val path = el.containingFile?.virtualFile?.path ?: continue
            refs.add(ResolvedReference(
                symbolName = symbol.name, sourceFile = symbol.sourceFile,
                targetFile = path, lineNumber = getLine(el),
                referenceType = classify(el), elementText = el.text.take(200),
            ))
        }
        return refs
    }

    private fun findElement(symbol: SymbolInfo): PsiElement? {
        val vFile = LocalFileSystem.getInstance().findFileByPath(symbol.sourceFile) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return null

        if (symbol.kind == SymbolKind.CLASS) {
            return JavaPsiFacade.getInstance(project)
                .findClass(symbol.fqn, GlobalSearchScope.allScope(project))
        }

        return psiFile.collectDescendantsOfType<KtNamedDeclaration>()
            .firstOrNull {
                it.textRange.startOffset == symbol.declarationOffset &&
                    it.name == symbol.name
            }
    }

    private fun classify(el: PsiElement): ReferenceType = when (el.parent) {
        is PsiImportStatementBase -> ReferenceType.IMPORT
        is PsiReferenceList -> ReferenceType.OVERRIDE
        else -> ReferenceType.CODE_REFERENCE
    }

    private fun getLine(el: PsiElement): Int {
        val doc = PsiDocumentManager.getInstance(project)
            .getDocument(el.containingFile) ?: return -1
        return doc.getLineNumber(el.textRange.startOffset) + 1
    }
}
