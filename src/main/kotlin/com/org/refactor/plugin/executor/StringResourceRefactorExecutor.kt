package com.org.refactor.plugin.executor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.org.refactor.plugin.model.AndroidResourceType
import com.org.refactor.plugin.model.ResourceRename
import com.org.refactor.plugin.model.StringResourceRename

internal class StringResourceRefactorExecutor(private val project: Project) {
    data class Result(
        val stringsRenamed: Int,
        val referencesUpdated: Int,
        val warnings: List<String>,
    )

    fun execute(planned: List<StringResourceRename>): Result {
        val warnings = mutableListOf<String>()
        val executable = planned.filter { it.checked && it.selectable }.filter { rename ->
            val problem = validate(rename)
            if (problem != null) warnings.add("Skipping ${rename.type.name.lowercase()} ${rename.oldName}: $problem")
            problem == null
        }
        if (executable.isEmpty()) return Result(0, 0, warnings)

        val referenceRenames = executable.map { rename ->
            ResourceRename(
                type = rename.type,
                moduleName = rename.moduleName,
                oldName = rename.oldName,
                newName = rename.newName,
                variants = emptyList(),
            )
        }
        val documents = collectProjectDocuments()
        var referencesUpdated = 0
        WriteCommandAction.runWriteCommandAction(project, "Refactor Value Resources", null, {
            val documentManager = PsiDocumentManager.getInstance(project)
            for ((file, document) in documents) {
                val update = ResourceTextUpdater.update(document.text, file.extension, referenceRenames)
                if (update.text != document.text) {
                    document.setText(update.text)
                    documentManager.commitDocument(document)
                    referencesUpdated += update.replacements
                }
            }

            val psiManager = PsiManager.getInstance(project)
            for (rename in executable) {
                for (variant in rename.variants) {
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(variant.sourceFile) ?: continue
                    val xmlFile = psiManager.findFile(virtualFile) as? XmlFile ?: continue
                    val tag = xmlFile.rootTag?.subTags?.firstOrNull { candidate ->
                        candidate.name == rename.type.name.lowercase() &&
                            candidate.getAttributeValue("name") == rename.oldName
                    } ?: continue
                    tag.setAttribute("name", rename.newName)
                }
            }
        })
        return Result(executable.size, referencesUpdated, warnings)
    }

    private fun validate(rename: StringResourceRename): String? {
        for (variant in rename.variants) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(variant.sourceFile)
                ?: return "locale file is missing: ${variant.sourceFile}"
            if (!virtualFile.isWritable) return "locale file is read-only: ${variant.sourceFile}"
            val xmlFile = PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile
                ?: return "locale file is not valid XML: ${variant.sourceFile}"
            val tags = xmlFile.rootTag?.subTags.orEmpty()
            val tagName = rename.type.name.lowercase()
            if (tags.none { it.name == tagName && it.getAttributeValue("name") == rename.oldName }) {
                return "source key is missing in ${variant.valuesDirectory}"
            }
            if (tags.any { it.name == tagName && it.getAttributeValue("name") == rename.newName }) {
                return "target key exists in ${variant.valuesDirectory}"
            }
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
