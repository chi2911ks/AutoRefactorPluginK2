package com.org.refactor.plugin.conflict

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.org.refactor.plugin.model.*
import com.org.refactor.plugin.references.DependencyGraph
import com.org.refactor.plugin.references.ReferenceType
import com.org.refactor.plugin.references.XmlReferenceParser
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

class ConflictDetector(private val project: Project) {

    private val kotlinKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum",
        "when", "if", "else", "for", "while", "do", "return", "break",
        "continue", "try", "catch", "finally", "throw", "import", "package",
        "as", "is", "in", "out", "where", "typealias", "data", "sealed",
        "open", "abstract", "final", "override", "private", "protected",
        "public", "internal", "companion", "const", "lateinit", "suspend",
        "tailrec", "operator", "infix", "inline", "noinline", "crossinline",
        "reified", "annotation", "vararg", "field", "it", "this", "super",
    )

    fun detect(
        plan: RefactorPlan,
        graph: DependencyGraph,
        index: ProjectIndex,
    ): ConflictReport {
        return ReadAction.compute<ConflictReport, RuntimeException> {
            doDetect(plan, graph, index)
        }
    }

    private fun doDetect(
        plan: RefactorPlan,
        graph: DependencyGraph,
        index: ProjectIndex,
    ): ConflictReport {
        val conflicts = mutableListOf<RefactorConflict>()

        detectDuplicateNames(plan, conflicts)
        detectExistingTargets(plan, conflicts)
        detectOverrideConflicts(graph, conflicts)
        detectJavaInteropIssues(graph, conflicts)
        detectReflectionRisks(graph, conflicts)
        detectExternalReferences(plan, index, conflicts)
        detectKeywordConflicts(plan, conflicts)
        detectGeneratedCode(plan, conflicts)

        val hasErrors = conflicts.any { it.severity == ConflictSeverity.ERROR }
        return ConflictReport(conflicts = conflicts, isSafe = !hasErrors)
    }

    private fun detectExistingTargets(
        plan: RefactorPlan,
        conflicts: MutableList<RefactorConflict>,
    ) {
        for (rename in plan.componentRenames) {
            val file = kotlinFile(rename.sourceFile) ?: continue
            val source = file.collectDescendantsOfType<KtClassOrObject>().firstOrNull {
                it.textRange.startOffset == rename.declarationOffset && it.name == rename.oldName
            } ?: continue
            val collision = source.parent.children.filterIsInstance<KtClassOrObject>()
                .any { it !== source && it.name == rename.newName }
            if (collision) addExistingTargetConflict(rename.oldName, rename.newName, rename.sourceFile, conflicts)
        }

        for (rename in plan.symbolRenames) {
            val file = kotlinFile(rename.sourceFile) ?: continue
            val source = file.collectDescendantsOfType<KtNamedDeclaration>().firstOrNull {
                it.textRange.startOffset == rename.declarationOffset && it.name == rename.oldName
            } ?: continue
            val collision = source.parent.children.filterIsInstance<KtNamedDeclaration>().any { candidate ->
                if (candidate === source || candidate.name != rename.newName) return@any false
                if (source is KtNamedFunction && candidate is KtNamedFunction) {
                    source.valueParameters.size == candidate.valueParameters.size
                } else {
                    true
                }
            }
            if (collision) addExistingTargetConflict(rename.oldName, rename.newName, rename.sourceFile, conflicts)
        }
    }

    private fun kotlinFile(path: String): KtFile? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
    }

    private fun addExistingTargetConflict(
        oldName: String,
        newName: String,
        sourceFile: String,
        conflicts: MutableList<RefactorConflict>,
    ) {
        conflicts.add(
            RefactorConflict(
                type = ConflictType.DUPLICATE_NAME,
                severity = ConflictSeverity.ERROR,
                message = "Cannot rename '$oldName' to '$newName': target already exists in the same scope",
                sourceFile = sourceFile,
                symbolName = oldName,
            ),
        )
    }

    private fun detectDuplicateNames(
        plan: RefactorPlan,
        conflicts: MutableList<RefactorConflict>,
    ) {
        val allNewNames = mutableMapOf<String, MutableList<String>>()

        for (rename in plan.componentRenames) {
            val owner = rename.fqn.substringBeforeLast('.', missingDelimiterValue = "")
            allNewNames.getOrPut("class:$owner:${rename.newName}") { mutableListOf() }.add(rename.oldName)
        }
        for (rename in plan.symbolRenames) {
            val key = "${rename.kind}:${rename.ownerScope}:${rename.newName}"
            allNewNames.getOrPut(key) { mutableListOf() }.add(rename.oldName)
        }

        for ((newName, oldNames) in allNewNames) {
            if (oldNames.distinct().size > 1) {
                conflicts.add(RefactorConflict(
                    type = ConflictType.DUPLICATE_NAME,
                    severity = ConflictSeverity.ERROR,
                    message = "Multiple symbols would be renamed to '$newName': ${oldNames.joinToString(", ")}",
                ))
            }
        }
    }

    private fun detectOverrideConflicts(
        graph: DependencyGraph,
        conflicts: MutableList<RefactorConflict>,
    ) {
        for (symbol in graph.getAllSymbols()) {
            val node = graph.getNode(symbol.fqn) ?: continue
            val overrideRefs = node.references.filter {
                it.referenceType == ReferenceType.OVERRIDE
            }
            if (overrideRefs.isNotEmpty()) {
                conflicts.add(RefactorConflict(
                    type = ConflictType.OVERRIDE_CONFLICT,
                    severity = ConflictSeverity.WARNING,
                    message = "Symbol '${symbol.name}' has ${overrideRefs.size} override references",
                    sourceFile = symbol.sourceFile,
                    symbolName = symbol.name,
                    suggestion = "Override functions should typically not be renamed",
                ))
            }
        }
    }

    private fun detectJavaInteropIssues(
        graph: DependencyGraph,
        conflicts: MutableList<RefactorConflict>,
    ) {
        for (symbol in graph.getAllSymbols()) {
            val node = graph.getNode(symbol.fqn) ?: continue
            val javaRefs = node.references.filter { it.targetFile.endsWith(".java") }
            if (javaRefs.isNotEmpty()) {
                conflicts.add(RefactorConflict(
                    type = ConflictType.JAVA_INTEROP,
                    severity = ConflictSeverity.WARNING,
                    message = "Kotlin symbol '${symbol.name}' referenced from ${javaRefs.size} Java files",
                    sourceFile = symbol.sourceFile,
                    symbolName = symbol.name,
                    suggestion = "Java references will be updated via RenameProcessor",
                ))
            }
        }
    }

    private fun detectReflectionRisks(
        graph: DependencyGraph,
        conflicts: MutableList<RefactorConflict>,
    ) {
        val reflectionPatterns = listOf(
            "Class.forName", "KClass", "::class.java", "javaClass",
            "getDeclaredMethod", "getDeclaredField", "getMethod", "getField",
            "setAccessible", "invoke",
        )

        for (symbol in graph.getAllSymbols()) {
            val node = graph.getNode(symbol.fqn) ?: continue
            for (ref in node.references) {
                val context = ref.elementText.lowercase()
                if (reflectionPatterns.any { it.lowercase() in context }) {
                    conflicts.add(RefactorConflict(
                        type = ConflictType.REFLECTION_RISK,
                        severity = ConflictSeverity.WARNING,
                        message = "Possible reflection usage near reference to '${symbol.name}'",
                        sourceFile = ref.targetFile,
                        symbolName = symbol.name,
                        suggestion = "Review manually — reflection targets aren't auto-renamed",
                    ))
                    break
                }
            }
        }
    }

    private fun detectExternalReferences(
        plan: RefactorPlan,
        index: ProjectIndex,
        conflicts: MutableList<RefactorConflict>,
    ) {
        val parser = XmlReferenceParser(project)

        for (manifestFile in index.manifestFiles) {
            val manifestRefs = parser.parseManifest(manifestFile)
            for (ref in manifestRefs) {
                val matched = plan.componentRenames.find { it.oldName == ref.componentName }
                if (matched != null) {
                    conflicts.add(RefactorConflict(
                        type = ConflictType.EXTERNAL_REFERENCE,
                        severity = ConflictSeverity.WARNING,
                        message = "'${ref.componentName}' referenced in AndroidManifest.xml — will need update",
                        sourceFile = manifestFile.absolutePath,
                        symbolName = ref.componentName,
                    ))
                }
            }
        }

        for (navFile in index.navigationGraphs) {
            val navRefs = parser.parseNavigationGraph(navFile)
            for (ref in navRefs) {
                val matched = plan.componentRenames.find { it.oldName == ref.componentName }
                if (matched != null) {
                    conflicts.add(RefactorConflict(
                        type = ConflictType.EXTERNAL_REFERENCE,
                        severity = ConflictSeverity.WARNING,
                        message = "'${ref.componentName}' referenced in navigation graph",
                        sourceFile = navFile.absolutePath,
                        symbolName = ref.componentName,
                    ))
                }
            }
        }
    }

    private fun detectKeywordConflicts(
        plan: RefactorPlan,
        conflicts: MutableList<RefactorConflict>,
    ) {
        for (rename in plan.componentRenames) {
            if (rename.newName.lowercase() in kotlinKeywords) {
                conflicts.add(RefactorConflict(
                    type = ConflictType.RESERVED_NAME,
                    severity = ConflictSeverity.ERROR,
                    message = "New name '${rename.newName}' conflicts with Kotlin keyword",
                    symbolName = rename.oldName,
                ))
            }
        }
        for (rename in plan.symbolRenames) {
            if (rename.newName.lowercase() in kotlinKeywords) {
                conflicts.add(RefactorConflict(
                    type = ConflictType.RESERVED_NAME,
                    severity = ConflictSeverity.ERROR,
                    message = "New name '${rename.newName}' conflicts with Kotlin keyword",
                    symbolName = rename.oldName,
                ))
            }
        }
    }

    private fun detectGeneratedCode(
        plan: RefactorPlan,
        conflicts: MutableList<RefactorConflict>,
    ) {
        for (rename in plan.componentRenames) {
            val path = rename.sourceFile.replace('\\', '/').lowercase()
            if ("/build/" in path || "/generated/" in path) {
                conflicts.add(RefactorConflict(
                    type = ConflictType.GENERATED_CODE,
                    severity = ConflictSeverity.ERROR,
                    message = "Class '${rename.oldName}' is in generated code — skipping",
                    sourceFile = rename.sourceFile,
                    symbolName = rename.oldName,
                ))
            }
        }
    }
}
