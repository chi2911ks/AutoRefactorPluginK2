package com.org.refactor.plugin.discovery

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.org.refactor.plugin.conflict.ConflictDetector
import com.org.refactor.plugin.executor.ClassRenameBatch
import com.org.refactor.plugin.executor.ClassReferenceRewriter
import com.org.refactor.plugin.executor.RefactorExecutor
import com.org.refactor.plugin.model.FileType
import com.org.refactor.plugin.model.ProjectIndex
import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.SourceFile
import com.org.refactor.plugin.plan.RefactorPlanGenerator
import com.org.refactor.plugin.psi.UniversalSymbolCollector
import com.org.refactor.plugin.references.DependencyGraph
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JavaRefactorDiscoveryTest : BasePlatformTestCase() {

    private lateinit var diskTestRoot: File
    private lateinit var rootVirtualFile: com.intellij.openapi.vfs.VirtualFile

    @BeforeEach
    override fun setUp() {
        super.setUp()
        diskTestRoot = File.createTempFile("autorefactor-java-", "").also {
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
    fun `discovers top level Java classes without nested declarations`() {
        val source = addJavaFile(
            "sample/JavaFeature.java",
            """
                package sample;

                public class JavaFeature {
                    class Nested {}
                }

                class PackagePrivateFeature {}
            """.trimIndent(),
        )
        val components = ComponentDiscoverer(project).discover(indexFor(source))

        assertEquals(
            setOf("sample.JavaFeature", "sample.PackagePrivateFeature"),
            components.map { it.fqn }.toSet(),
            "components=$components",
        )
        assertTrue(components.all { it.isTopLevel })
    }

    @Test
    fun `detects Java class target collision in the same scope`() {
        val source = addJavaFile(
            "sample/JavaFeature.java",
            """
                package sample;

                class JavaFeature {}
                class JavaFeatureRef {}
            """.trimIndent(),
        )
        val options = RefactorOptions(
            suffixToAdd = "Ref",
            refactorTypeAliases = false,
            refactorStrings = false,
            refactorColors = false,
            refactorStyles = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )
        val component = ComponentDiscoverer(project)
            .discover(indexFor(source))
            .single { it.className == "JavaFeature" }
        val plan = RefactorPlanGenerator(options).generate(
            components = listOf(component),
            symbols = emptyList(),
            allKotlinPaths = emptyList(),
        )

        val report = ConflictDetector(project).detect(plan, DependencyGraph(), indexFor(source))

        assertTrue(
            report.conflicts.any { it.message.contains("target already exists") },
            "conflicts=${report.conflicts}",
        )
    }

    @Test
    fun `collects Java methods and fields but skips constructors callbacks and nested members`() {
        val source = addJavaFile(
            "sample/JavaFeature.java",
            """
                package sample;

                public class JavaFeature {
                    private int itemCount;

                    public JavaFeature() {}
                    public void loadData(String argument) {}
                    public void onCreate() {}

                    class Nested {
                        void nestedMethod() {}
                    }
                }
            """.trimIndent(),
        )
        val options = RefactorOptions(
            suffixToAdd = "Ref",
            refactorClasses = false,
            refactorFunctions = true,
            refactorVariables = true,
            refactorTypeAliases = false,
            refactorStrings = false,
            refactorColors = false,
            refactorStyles = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )

        val symbols = UniversalSymbolCollector(project).collectAll(indexFor(source), options)

        assertEquals(
            setOf("loadData", "itemCount"),
            symbols.map { it.name }.toSet(),
            "symbols=$symbols",
        )
        assertTrue(symbols.all { it.sourceFile == source.absolutePath })
    }

    @Test
    fun `renames a Java class and its source file`() {
        val source = addJavaFile(
            "sample/JavaFeature.java",
            """
                package sample;

                public class JavaFeature {}
            """.trimIndent(),
        )
        val usage = addJavaFile(
            "sample/JavaUsage.java",
            """
                package sample;

                class JavaUsage {
                    JavaFeature feature;
                }
            """.trimIndent(),
        )
        val options = RefactorOptions(
            suffixToAdd = "Ref",
            refactorTypeAliases = false,
            refactorStrings = false,
            refactorColors = false,
            refactorStyles = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )
        val component = ComponentDiscoverer(project).discover(indexFor(source)).single()
        val plan = RefactorPlanGenerator(options).generate(
            components = listOf(component),
            symbols = emptyList(),
            allKotlinPaths = emptyList(),
        )
        DumbService.getInstance(project).waitForSmartMode()
        val result = RefactorExecutor(project).execute(plan)
        val usageText = readDocument(usage.absolutePath)

        assertTrue(result.success, result.errors.joinToString())
        assertEquals(1, result.classesRenamed)
        assertEquals(1, result.classRenameBatches)
        assertTrue(
            LocalFileSystem.getInstance().findFileByPath(
                File(source.absolutePath).resolveSibling("JavaFeatureRef.java").path,
            ) != null,
        )
        assertTrue("JavaFeatureRef" in usageText, "usage=$usageText")
    }

    @Test
    fun `renames Java methods and fields and updates their usages`() {
        val source = addJavaFile(
            "sample/JavaFeature.java",
            """
                package sample;

                public class JavaFeature {
                    public int itemCount;
                    public void loadData() {}
                }
            """.trimIndent(),
        )
        val usage = addJavaFile(
            "sample/JavaUsage.java",
            """
                package sample;

                class JavaUsage {
                    void use(JavaFeature feature) {
                        feature.loadData();
                        int count = feature.itemCount;
                    }
                }
            """.trimIndent(),
        )
        val options = RefactorOptions(
            suffixToAdd = "Ref",
            refactorClasses = false,
            refactorFunctions = true,
            refactorVariables = true,
            refactorTypeAliases = false,
            refactorStrings = false,
            refactorColors = false,
            refactorStyles = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )
        val symbols = UniversalSymbolCollector(project)
            .collectAll(indexFor(listOf(source, usage)), options)
            .filter { it.sourceFile == source.absolutePath }
        val plan = RefactorPlanGenerator(options).generate(
            components = emptyList(),
            symbols = symbols,
            allKotlinPaths = emptyList(),
        )
        DumbService.getInstance(project).waitForSmartMode()
        val result = RefactorExecutor(project).execute(plan)
        val usageText = readDocument(usage.absolutePath)

        assertTrue(result.success, result.errors.joinToString())
        assertEquals(setOf("loadDataRef", "itemCountRef"), plan.symbolRenames.map { it.newName }.toSet())
        assertEquals(2, result.symbolsRenamed)
        assertTrue("feature.loadDataRef()" in usageText, "usage=$usageText")
        assertTrue("feature.itemCountRef" in usageText, "usage=$usageText")
    }

    @Test
    fun `keeps a real Java accessor separate from a field rename`() {
        val source = addJavaFile(
            "sample/JavaFeature.java",
            """
                package sample;

                public class JavaFeature {
                    public int itemCount;

                    public int getItemCount() {
                        return itemCount;
                    }
                }
            """.trimIndent(),
        )
        val usage = addJavaFile(
            "sample/JavaUsage.java",
            """
                package sample;

                class JavaUsage {
                    int read(JavaFeature feature) {
                        return feature.itemCount + feature.getItemCount();
                    }
                }
            """.trimIndent(),
        )
        val options = RefactorOptions(
            suffixToAdd = "Ref",
            refactorClasses = false,
            refactorFunctions = false,
            refactorVariables = true,
            refactorTypeAliases = false,
            refactorStrings = false,
            refactorColors = false,
            refactorStyles = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )
        val symbols = UniversalSymbolCollector(project)
            .collectAll(indexFor(listOf(source, usage)), options)
            .filter { it.sourceFile == source.absolutePath }
        val plan = RefactorPlanGenerator(options).generate(
            components = emptyList(),
            symbols = symbols,
            allKotlinPaths = emptyList(),
        )
        DumbService.getInstance(project).waitForSmartMode()
        val result = RefactorExecutor(project).execute(plan)
        val sourceText = readDocument(source.absolutePath)
        val usageText = readDocument(usage.absolutePath)

        assertTrue(result.success, result.errors.joinToString())
        assertEquals(listOf("itemCountRef"), plan.symbolRenames.map { it.newName })
        assertTrue("itemCountRef" in sourceText, "source=$sourceText")
        assertTrue("getItemCount()" in sourceText, "source=$sourceText")
        assertTrue("feature.itemCountRef + feature.getItemCount()" in usageText, "usage=$usageText")
    }

    @Test
    fun `renames multiple Java classes and usages in one batch`() {
        val first = addJavaFile(
            "sample/JavaFeature.java",
            """
                package sample;

                public class JavaFeature {}
            """.trimIndent(),
        )
        val second = addJavaFile(
            "sample/JavaScreen.java",
            """
                package sample;

                public class JavaScreen {}
            """.trimIndent(),
        )
        val usage = addJavaFile(
            "sample/JavaUsage.java",
            """
                package sample;

                class JavaUsage {
                    JavaFeature feature;
                    JavaScreen screen;
                }
            """.trimIndent(),
        )

        DumbService.getInstance(project).waitForSmartMode()
        val requests = listOf(
            ClassRenameBatch.Request(javaClass(first), "JavaFeatureRef"),
            ClassRenameBatch.Request(javaClass(second), "JavaScreenRef"),
        )
        val result = ClassRenameBatch(project).execute(requests)
        val usageText = readDocument(usage.absolutePath)

        assertTrue(result.success, result.errors.joinToString())
        assertEquals(2, result.renamed)
        assertTrue("JavaFeatureRef feature" in usageText, "usage=$usageText")
        assertTrue("JavaScreenRef screen" in usageText, "usage=$usageText")
        assertTrue(
            LocalFileSystem.getInstance().findFileByPath(
                File(first.absolutePath).resolveSibling("JavaFeatureRef.java").path,
            ) != null,
        )
        assertTrue(
            LocalFileSystem.getInstance().findFileByPath(
                File(second.absolutePath).resolveSibling("JavaScreenRef.java").path,
            ) != null,
        )
    }

    @Test
    fun `rewrites only mapped Android class name attributes`() {
        val original = """
            <activity android:name="JavaFeature" />
            <service android:name='JavaScreen' />
            <item name="JavaFeature" />
            <!-- JavaFeature -->
        """.trimIndent()

        val rewritten = ClassReferenceRewriter.rewrite(
            original,
            mapOf("JavaFeature" to "JavaFeatureRef", "JavaScreen" to "JavaScreenRef"),
        )

        assertEquals(
            """
                <activity android:name="JavaFeatureRef" />
                <service android:name='JavaScreenRef' />
                <item name="JavaFeature" />
                <!-- JavaFeature -->
            """.trimIndent(),
            rewritten,
        )
    }

    private fun javaClass(source: SourceFile): com.intellij.psi.PsiNamedElement =
        ReadAction.compute<com.intellij.psi.PsiNamedElement, RuntimeException> {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(source.absolutePath)
                ?: error("missing Java fixture ${source.absolutePath}")
            val file = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
                ?: error("missing Java PSI ${source.absolutePath}")
            file.classes.single()
        }

    private fun addJavaFile(name: String, text: String): SourceFile {
        val file = File(diskTestRoot, name)
        file.parentFile.mkdirs()
        file.writeText(text)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: error("missing physical fixture ${file.absolutePath}")
        val path = file.absolutePath
        return SourceFile(
            virtualFilePath = path,
            absolutePath = path,
            moduleName = "test",
            fileType = FileType.JAVA,
        )
    }

    private fun indexFor(source: SourceFile): ProjectIndex = indexFor(listOf(source))

    private fun indexFor(sources: List<SourceFile>): ProjectIndex = ProjectIndex(
        modules = emptyList(),
        allKotlinFiles = emptyList(),
        allJavaFiles = sources,
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
