package com.org.refactor.plugin.discovery

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiManager
import com.org.refactor.plugin.model.ComponentInfo
import com.org.refactor.plugin.model.ComponentType
import com.org.refactor.plugin.model.ProjectIndex
import com.org.refactor.plugin.model.SourceFile
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/** Discovers named Kotlin and Java class-like declarations in writable project sources. */
class ComponentDiscoverer(private val project: Project) {

    fun discover(index: ProjectIndex): List<ComponentInfo> =
        ReadAction.compute<List<ComponentInfo>, RuntimeException> {
            (index.allKotlinFiles.flatMap(::discoverFile) + index.allJavaFiles.flatMap(::discoverJavaFile))
                .distinctBy { it.fqn }
        }

    private fun discoverFile(sourceFile: SourceFile): List<ComponentInfo> {
        if (isGenerated(sourceFile.absolutePath)) return emptyList()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(sourceFile.absolutePath)
            ?: return emptyList()
        if (!virtualFile.isWritable) return emptyList()
        val ktFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
            ?: return emptyList()

        return ktFile.collectDescendantsOfType<KtClassOrObject>()
            .asSequence()
            .filter { it.fqName != null }
            .filter { it.parent is KtFile }
            .filterNot { it is KtEnumEntry }
            .filterNot {
                it is KtObjectDeclaration &&
                    (it.isObjectLiteral() || it.isCompanion() && it.nameIdentifier == null)
            }
            .mapNotNull { declaration -> declaration.toComponentInfo(sourceFile, ktFile) }
            .toList()
    }

    private fun discoverJavaFile(sourceFile: SourceFile): List<ComponentInfo> {
        if (isGenerated(sourceFile.absolutePath)) return emptyList()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(sourceFile.absolutePath)
            ?: return emptyList()
        if (!virtualFile.isWritable) return emptyList()
        val javaFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
            ?: return emptyList()

        return javaFile.classes
            .asSequence()
            .filter { it.containingClass == null }
            .mapNotNull { it.toComponentInfo(sourceFile, javaFile) }
            .distinctBy { it.fqn }
            .toList()
    }

    private fun KtClassOrObject.toComponentInfo(sourceFile: SourceFile, file: KtFile): ComponentInfo? {
        val name = name ?: return null
        val qualifiedName = fqName?.asString() ?: return null
        val type = when (this) {
            is KtObjectDeclaration -> ComponentType.OBJECT
            is KtClass -> when {
                isAnnotation() -> ComponentType.ANNOTATION
                isInterface() -> ComponentType.INTERFACE
                isEnum() -> ComponentType.ENUM
                else -> ComponentType.CLASS
            }
            else -> return null
        }
        return ComponentInfo(
            file = sourceFile,
            className = name,
            fqn = qualifiedName,
            packageName = file.packageFqName.asString(),
            componentType = type,
            declarationOffset = textRange.startOffset,
            isTopLevel = parent is KtFile,
            isAbstract = this is KtClass && (isInterface() || hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)),
        )
    }

    private fun PsiClass.toComponentInfo(sourceFile: SourceFile, file: PsiJavaFile): ComponentInfo? {
        val name = name ?: return null
        val qualifiedName = qualifiedName ?: return null
        val type = when {
            isAnnotationType -> ComponentType.ANNOTATION
            isEnum -> ComponentType.ENUM
            isInterface -> ComponentType.INTERFACE
            else -> ComponentType.CLASS
        }
        return ComponentInfo(
            file = sourceFile,
            className = name,
            fqn = qualifiedName,
            packageName = file.packageName,
            componentType = type,
            declarationOffset = nameIdentifier?.textRange?.startOffset ?: textRange.startOffset,
            isTopLevel = containingClass == null,
            isAbstract = isInterface || hasModifierProperty(PsiModifier.ABSTRACT),
        )
    }

    private fun isGenerated(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return "/build/" in normalized || "/generated/" in normalized
    }
}
