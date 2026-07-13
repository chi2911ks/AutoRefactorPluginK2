package com.org.refactor.plugin.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.org.refactor.plugin.model.ProjectIndex
import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.SourceFile
import com.org.refactor.plugin.model.SymbolInfo
import com.org.refactor.plugin.model.SymbolKind
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/** Collects renameable Kotlin declarations from all writable project source files. */
class UniversalSymbolCollector(private val project: Project) {

    fun collectAll(index: ProjectIndex, options: RefactorOptions): List<SymbolInfo> =
        ReadAction.compute<List<SymbolInfo>, RuntimeException> {
            index.allKotlinFiles.flatMap { collectFile(it, options) }
        }

    private fun collectFile(sourceFile: SourceFile, options: RefactorOptions): List<SymbolInfo> {
        if (isGenerated(sourceFile.absolutePath)) return emptyList()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(sourceFile.absolutePath)
            ?: return emptyList()
        if (!virtualFile.isWritable) return emptyList()
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
            ?: return emptyList()

        val declarations = mutableListOf<KtNamedDeclaration>()
        if (options.refactorFunctions) {
            declarations += file.collectDescendantsOfType<KtNamedFunction> {
                val name = it.name
                !name.isNullOrBlank() && !isCallback(name) && !it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
            }
        }
        if (options.refactorVariables) {
            declarations += file.collectDescendantsOfType<KtProperty> { !it.name.isNullOrBlank() }
            declarations += file.collectDescendantsOfType<KtParameter> {
                it.hasValOrVar() && !it.name.isNullOrBlank()
            }
        }

        return declarations
            .distinctBy { it.textRange.startOffset }
            .map { it.toSymbolInfo(sourceFile, file) }
    }

    private fun KtNamedDeclaration.toSymbolInfo(sourceFile: SourceFile, file: KtFile): SymbolInfo {
        val offset = textRange.startOffset
        val name = requireNotNull(name)
        val owner = "${sourceFile.absolutePath}#${parent.textRange.startOffset}"
        val kind = if (this is KtNamedFunction) SymbolKind.FUNCTION else SymbolKind.PROPERTY
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        val line = document?.getLineNumber(offset)?.plus(1) ?: -1
        return SymbolInfo(
            name = name,
            fqn = "${sourceFile.absolutePath}@$offset:$name",
            kind = kind,
            psiElementClass = javaClass.simpleName,
            sourceFile = sourceFile.absolutePath,
            lineNumber = line,
            declarationOffset = offset,
            parentClassFqn = owner,
        )
    }

    private fun isGenerated(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return "/build/" in normalized || "/generated/" in normalized
    }

    private fun isCallback(name: String): Boolean = name in callbacks

    private companion object {
        val callbacks = setOf(
            "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
            "onCreateView", "onViewCreated", "onDestroyView", "onAttach", "onDetach",
            "onActivityResult", "onRequestPermissionsResult", "onConfigurationChanged",
            "onSaveInstanceState", "onRestoreInstanceState", "onBackPressed",
            "onCreateOptionsMenu", "onOptionsItemSelected", "onNewIntent",
            "onPostCreate", "onPostResume", "onWindowFocusChanged", "onLowMemory",
            "onTrimMemory", "onCreateDialog", "handleOnBackPressed",
        )
    }
}
