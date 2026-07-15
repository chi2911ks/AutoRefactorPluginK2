package com.org.refactor.plugin.discovery

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.org.refactor.plugin.model.ProjectIndex
import com.org.refactor.plugin.model.TypeAliasInfo
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

class TypeAliasDiscoverer(private val project: Project) {
    fun discover(index: ProjectIndex): List<TypeAliasInfo> =
        ReadAction.compute<List<TypeAliasInfo>, RuntimeException> {
            index.allKotlinFiles.flatMap { source ->
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(source.absolutePath)
                    ?: return@flatMap emptyList()
                if (!virtualFile.isWritable) return@flatMap emptyList()
                val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
                    ?: return@flatMap emptyList()
                file.collectDescendantsOfType<KtTypeAlias>().mapNotNull { alias ->
                    val name = alias.name ?: return@mapNotNull null
                    val packageName = file.packageFqName.asString()
                    val ownerScope = alias.containingClassOrObject?.fqName?.asString()
                        ?: packageName.ifEmpty { "<root>" }
                    TypeAliasInfo(
                        name = name,
                        fqn = alias.fqName?.asString()
                            ?: listOf(packageName, name).filter { it.isNotEmpty() }.joinToString("."),
                        sourceFile = source.absolutePath,
                        declarationOffset = alias.textRange.startOffset,
                        ownerScope = ownerScope,
                    )
                }
            }.distinctBy { "${it.sourceFile}:${it.declarationOffset}" }
        }
}
