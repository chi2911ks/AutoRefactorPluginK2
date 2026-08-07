package com.org.refactor.plugin.discovery

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.org.refactor.plugin.model.ProjectIndex
import com.org.refactor.plugin.model.AndroidResourceType
import com.org.refactor.plugin.model.StringResourceInfo
import com.org.refactor.plugin.model.ValueXmlFileInfo

class StringResourceDiscoverer(private val project: Project) {
    fun discoverFiles(index: ProjectIndex): List<ValueXmlFileInfo> =
        ReadAction.compute<List<ValueXmlFileInfo>, RuntimeException> {
            index.allXmlFiles.mapNotNull { source ->
                val normalized = source.absolutePath.replace('\\', '/')
                val match = VALUES_DIRECTORY.find(normalized) ?: return@mapNotNull null
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(source.absolutePath)
                    ?: return@mapNotNull null
                ValueXmlFileInfo(
                    moduleName = source.moduleName,
                    fileName = match.groupValues[2],
                    sourceFile = source.absolutePath,
                    valuesDirectory = match.groupValues[1],
                    isWritable = virtualFile.isWritable,
                )
            }.distinctBy { it.sourceFile }
        }

    fun discover(index: ProjectIndex): List<StringResourceInfo> =
        ReadAction.compute<List<StringResourceInfo>, RuntimeException> {
            index.allXmlFiles.flatMap { source ->
                val normalized = source.absolutePath.replace('\\', '/')
                val valuesDirectory = VALUES_DIRECTORY.find(normalized)?.groupValues?.get(1)
                    ?: return@flatMap emptyList()
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(source.absolutePath)
                    ?: return@flatMap emptyList()
                val file = PsiManager.getInstance(project).findFile(virtualFile) as? XmlFile
                    ?: return@flatMap emptyList()
                file.rootTag?.subTags.orEmpty().mapNotNull { tag ->
                    val type = when (tag.name) {
                        "string" -> AndroidResourceType.STRING
                        "color" -> AndroidResourceType.COLOR
                        "style" -> AndroidResourceType.STYLE
                        else -> return@mapNotNull null
                    }
                    val name = tag.getAttributeValue("name") ?: return@mapNotNull null
                    if (!isValidName(type, name)) return@mapNotNull null
                    StringResourceInfo(
                        name = name,
                        type = type,
                        moduleName = source.moduleName,
                        sourceFile = source.absolutePath,
                        valuesDirectory = valuesDirectory,
                        isWritable = virtualFile.isWritable,
                    )
                }
            }.distinctBy { "${it.sourceFile}:${it.type}:${it.name}" }
        }

    private fun isValidName(type: AndroidResourceType, name: String): Boolean = when (type) {
        AndroidResourceType.STYLE -> STYLE_NAME.matches(name)
        else -> RESOURCE_NAME.matches(name)
    }

    private companion object {
        val VALUES_DIRECTORY = Regex(
            "/res/(values(?:-[^/]+)?)/([^/]+\\.xml)$",
            RegexOption.IGNORE_CASE,
        )
        val RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")
        val STYLE_NAME = Regex("[A-Za-z][A-Za-z0-9_.]*")
    }
}
