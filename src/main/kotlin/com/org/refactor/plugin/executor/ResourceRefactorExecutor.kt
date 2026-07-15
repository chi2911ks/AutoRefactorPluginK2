package com.org.refactor.plugin.executor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.org.refactor.plugin.model.AndroidResourceType
import com.org.refactor.plugin.model.ResourceRename

internal class ResourceRefactorExecutor(private val project: Project) {

    data class Result(
        val drawablesRenamed: Int,
        val layoutsRenamed: Int,
        val filesRenamed: Int,
        val referencesUpdated: Int,
        val warnings: List<String>,
    )

    fun execute(planned: List<ResourceRename>): Result {
        val warnings = mutableListOf<String>()
        val executable = planned.filter { it.checked }.filter { rename ->
            val problem = validate(rename)
            if (problem != null) warnings.add("Skipping ${rename.oldName}: $problem")
            problem == null
        }
        if (executable.isEmpty()) {
            return Result(0, 0, 0, 0, warnings)
        }

        val documents = collectProjectDocuments()
        var referencesUpdated = 0
        var filesRenamed = 0
        WriteCommandAction.runWriteCommandAction(project, "Refactor Android Resources", null, {
            val documentManager = PsiDocumentManager.getInstance(project)
            for ((file, document) in documents) {
                var text = document.text
                var replacements = 0
                ResourceTextUpdater.update(text, file.extension, executable).let {
                    text = it.text
                    replacements = it.replacements
                }
                if (text != document.text) {
                    document.setText(text)
                    documentManager.commitDocument(document)
                    referencesUpdated += replacements
                }
            }

            for (rename in executable) {
                for (variant in rename.variants) {
                    val file = LocalFileSystem.getInstance().findFileByPath(variant.oldPath) ?: continue
                    file.rename(this, variant.newFileName)
                    filesRenamed++
                }
            }
        })

        return Result(
            drawablesRenamed = executable.count { it.type == AndroidResourceType.DRAWABLE },
            layoutsRenamed = executable.count { it.type == AndroidResourceType.LAYOUT },
            filesRenamed = filesRenamed,
            referencesUpdated = referencesUpdated,
            warnings = warnings,
        )
    }

    private fun validate(rename: ResourceRename): String? {
        for (variant in rename.variants) {
            val source = LocalFileSystem.getInstance().findFileByPath(variant.oldPath)
                ?: return "source variant is missing: ${variant.oldPath}"
            if (!source.isWritable) return "source variant is read-only: ${variant.oldPath}"
            val target = source.parent?.findChild(variant.newFileName)
            if (target != null && target != source) return "target already exists: ${variant.newPath}"
        }
        return null
    }

    private fun collectProjectDocuments(): List<Pair<VirtualFile, com.intellij.openapi.editor.Document>> {
        val scope = GlobalSearchScope.projectScope(project)
        val psiManager = PsiManager.getInstance(project)
        val documentManager = PsiDocumentManager.getInstance(project)
        return sequenceOf("kt", "java", "xml")
            .flatMap { extension -> FilenameIndex.getAllFilesByExt(project, extension, scope).asSequence() }
            .distinctBy { it.path }
            .filterNot { file ->
                val path = file.path.replace('\\', '/').lowercase()
                "/build/" in path || "/generated/" in path
            }
            .mapNotNull { file ->
                val psi = psiManager.findFile(file) ?: return@mapNotNull null
                val document = documentManager.getDocument(psi) ?: return@mapNotNull null
                file to document
            }
            .toList()
    }
}
