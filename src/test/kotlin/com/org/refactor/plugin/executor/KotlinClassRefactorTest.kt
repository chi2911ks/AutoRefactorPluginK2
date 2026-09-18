package com.org.refactor.plugin.executor

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.org.refactor.plugin.discovery.ComponentDiscoverer
import com.org.refactor.plugin.model.FileType
import com.org.refactor.plugin.model.ProjectIndex
import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.SourceFile
import com.org.refactor.plugin.plan.RefactorPlanGenerator
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KotlinClassRefactorTest : BasePlatformTestCase() {

    private lateinit var diskTestRoot: File
    private lateinit var rootVirtualFile: com.intellij.openapi.vfs.VirtualFile

    @BeforeEach
    override fun setUp() {
        super.setUp()
        diskTestRoot = File.createTempFile("autorefactor-kotlin-", "").also {
            check(it.delete())
            check(it.mkdirs())
        }
        rootVirtualFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(diskTestRoot)
            ?: error("missing physical fixture root ${diskTestRoot.absolutePath}")
        PsiTestUtil.addSourceContentToRoots(module, rootVirtualFile)
    }

    @AfterEach
    override fun tearDown() {
        try {
            PsiTestUtil.removeSourceRoot(module, rootVirtualFile)
            diskTestRoot.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun `renames multiple Kotlin classes and usages in one batch`() {
        val first = addKotlinFile(
            "sample/KotlinFeature.kt",
            """
                package sample

                class KotlinFeature
            """.trimIndent(),
        )
        val second = addKotlinFile(
            "sample/KotlinScreen.kt",
            """
                package sample

                class KotlinScreen
            """.trimIndent(),
        )
        val usage = addKotlinFile(
            "sample/KotlinUsage.kt",
            """
                package sample

                class KotlinUsage {
                    val feature = KotlinFeature()
                    val screen = KotlinScreen()
                }
            """.trimIndent(),
        )
        val sources = listOf(first, second, usage)
        val components = ComponentDiscoverer(project)
            .discover(indexFor(sources))
            .filter { it.className != "KotlinUsage" }
        val options = RefactorOptions(
            suffixToAdd = "Ref",
            refactorTypeAliases = false,
            refactorStrings = false,
            refactorColors = false,
            refactorStyles = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )
        val plan = RefactorPlanGenerator(options).generate(
            components = components,
            symbols = emptyList(),
            allKotlinPaths = sources.map(SourceFile::absolutePath),
        )

        DumbService.getInstance(project).waitForSmartMode()
        val result = RefactorExecutor(project).execute(plan)
        val usageText = readDocument(usage.absolutePath)

        assertTrue(result.success, result.errors.joinToString())
        assertEquals(2, result.classesRenamed)
        assertEquals(1, result.classRenameBatches)
        assertEquals(2, result.filesRenamed, "planFiles=${plan.fileRenames}")
        assertTrue("KotlinFeatureRef()" in usageText, "usage=$usageText")
        assertTrue("KotlinScreenRef()" in usageText, "usage=$usageText")
        assertTrue(
            LocalFileSystem.getInstance().findFileByPath(
                File(first.absolutePath).resolveSibling("KotlinFeatureRef.kt").path,
            ) != null,
            "first file was not renamed",
        )
        assertTrue(
            LocalFileSystem.getInstance().findFileByPath(
                File(second.absolutePath).resolveSibling("KotlinScreenRef.kt").path,
            ) != null,
            "second file was not renamed; files=${diskTestRoot.walkTopDown().map { it.name }.toList()}",
        )
    }

    private fun addKotlinFile(name: String, text: String): SourceFile {
        val file = File(diskTestRoot, name)
        file.parentFile.mkdirs()
        file.writeText(text)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: error("missing physical fixture ${file.absolutePath}")
        return SourceFile(
            virtualFilePath = virtualFile.path,
            absolutePath = file.absolutePath,
            moduleName = "test",
            fileType = FileType.KOTLIN,
        )
    }

    private fun indexFor(sources: List<SourceFile>): ProjectIndex = ProjectIndex(
        modules = emptyList(),
        allKotlinFiles = sources,
        allJavaFiles = emptyList(),
        allXmlFiles = emptyList(),
        manifestFiles = emptyList(),
        navigationGraphs = emptyList(),
        gradleModules = emptyList(),
        androidResourceFiles = emptyList(),
    )

    private fun readDocument(path: String): String = ReadAction.compute<String, RuntimeException> {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return@compute ""
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@compute ""
        PsiDocumentManager.getInstance(project).getDocument(psiFile)?.text.orEmpty()
    }
}
