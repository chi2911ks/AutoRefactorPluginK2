package com.org.refactor.plugin.references

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.org.refactor.plugin.model.*

class XmlReferenceParser(private val project: Project) {

    data class XmlReference(
        val componentName: String,
        val componentFqn: String,
        val filePath: String,
        val xmlElement: String,
        val attribute: String,
        val lineNumber: Int,
    )

    fun parseManifest(manifestFile: SourceFile): List<XmlReference> {
        val references = mutableListOf<XmlReference>()
        val xmlFile = loadXml(manifestFile) ?: return references
        val rootTag = xmlFile.rootTag ?: return references

        val activityTags = rootTag.findSubTags("activity") + rootTag.findSubTags("activity-alias")
        for (tag in activityTags) {
            val name = tag.getAttributeValue("android:name") ?: continue
            references.add(createXmlReference(name, manifestFile.absolutePath, tag.name, "android:name", tag))
        }

        return references
    }

    fun parseNavigationGraph(navFile: SourceFile): List<XmlReference> {
        val references = mutableListOf<XmlReference>()
        val xmlFile = loadXml(navFile) ?: return references
        val rootTag = xmlFile.rootTag ?: return references

        val destinationTags = rootTag.findSubTags("fragment") +
            rootTag.findSubTags("dialog") +
            rootTag.findSubTags("activity")

        for (tag in destinationTags) {
            val name = tag.getAttributeValue("android:name") ?: continue
            references.add(createXmlReference(name, navFile.absolutePath, tag.name, "android:name", tag))
        }

        return references
    }

    fun parseLayout(layoutFile: SourceFile): List<XmlReference> {
        val references = mutableListOf<XmlReference>()
        val xmlFile = loadXml(layoutFile) ?: return references
        val rootTag = xmlFile.rootTag ?: return references

        collectFragmentRefs(rootTag, references, layoutFile.absolutePath)
        return references
    }

    private fun collectFragmentRefs(
        tag: XmlTag,
        references: MutableList<XmlReference>,
        filePath: String,
    ) {
        if (tag.name == "fragment") {
            val name = tag.getAttributeValue("android:name")
                ?: tag.getAttributeValue("class")
            if (name != null) {
                references.add(createXmlReference(name, filePath, "fragment", "android:name", tag))
            }
        }
        for (subTag in tag.subTags) {
            collectFragmentRefs(subTag, references, filePath)
        }
    }

    private fun createXmlReference(
        nameAttr: String,
        filePath: String,
        xmlElement: String,
        attribute: String,
        tag: XmlTag,
    ): XmlReference {
        val simpleName = nameAttr.substringAfterLast('.').ifEmpty { nameAttr }
        return XmlReference(
            componentName = simpleName,
            componentFqn = nameAttr,
            filePath = filePath,
            xmlElement = xmlElement,
            attribute = attribute,
            lineNumber = getLineNumber(tag),
        )
    }

    private fun loadXml(sourceFile: SourceFile): XmlFile? {
        val vFile = LocalFileSystem.getInstance()
            .findFileByPath(sourceFile.absolutePath) ?: return null
        return PsiManager.getInstance(project).findFile(vFile) as? XmlFile
    }

    private fun getLineNumber(element: PsiElement): Int {
        val document = PsiDocumentManager.getInstance(project)
            .getDocument(element.containingFile) ?: return -1
        return document.getLineNumber(element.textRange.startOffset) + 1
    }
}
