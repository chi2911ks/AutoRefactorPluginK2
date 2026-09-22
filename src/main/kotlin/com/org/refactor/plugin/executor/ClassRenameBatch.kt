package com.org.refactor.plugin.executor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import java.util.IdentityHashMap
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/** Runs selected class renames through bounded IntelliJ multi-element transactions. */
internal class ClassRenameBatch(
    private val project: Project,
    private val suffixToAdd: String = "",
) {

    data class Request(
        val element: PsiNamedElement,
        val newName: String,
    )

    data class Result(
        val success: Boolean,
        val renamed: Int,
        val batches: Int,
        val errors: List<String>,
    )

    private data class PreparedRequest(
        val oldName: String,
        val pointer: SmartPsiElementPointer<PsiNamedElement>,
        val newName: String,
    )

    private data class ChunkResult(
        val renamed: Int,
        val errors: List<String>,
        val retryRequests: List<Request> = emptyList(),
    )

    fun execute(requests: List<Request>): Result {
        if (requests.isEmpty()) {
            return Result(success = true, renamed = 0, batches = 0, errors = emptyList())
        }

        val errors = mutableListOf<String>()
        var renamed = 0
        var batches = 0

        // Do not create smart pointers for the whole project up front. On a large all-module
        // refactor that keeps every declaration and its PSI file reachable while RenameProcessor
        // is also retaining usages and undo data. Prepare one small chunk, then let it go out of
        // scope before the next chunk is resolved.
        // Do not submit the same PSI declaration twice. This can happen when a grouped module
        // index contributes the same declaration through more than one source-set view. IntelliJ
        // accepts duplicate elements in a multi-element rename transaction, but the second
        // automatic rename may observe the already-renamed declaration and append the suffix
        // again.
        val uniqueRequests = requests.distinctByElement()
        val chunks = uniqueRequests.chunked(MAX_RENAMES_PER_BATCH)
        for ((index, requestChunk) in chunks.withIndex()) {
            batches++
            ProgressManager.getInstance().progressIndicator?.text2 =
                "Renaming class batch ${index + 1}/${chunks.size}"
            val chunkResult = runChunkOnEdt(requestChunk)
            renamed += chunkResult.renamed
            errors += chunkResult.errors.map { "Batch $batches: $it" }

            // IntelliJ's multi-element RenameProcessor can complete without throwing while
            // leaving some Kotlin declarations unchanged. Retry only those declarations through
            // a singleton processor, which follows the same reliable path as Shift+F6.
            for ((retryIndex, request) in chunkResult.retryRequests.withIndex()) {
                batches++
                ProgressManager.getInstance().progressIndicator?.text2 =
                    "Retrying skipped class ${retryIndex + 1}/${chunkResult.retryRequests.size}"
                val retryResult = runChunkOnEdt(listOf(request))
                renamed += retryResult.renamed
                errors += retryResult.errors.map { "Batch $batches fallback: $it" }
                if (retryResult.retryRequests.isNotEmpty()) {
                    errors += "Batch $batches fallback: declaration did not rename to ${request.newName}"
                }
            }
        }

        return Result(success = errors.isEmpty(), renamed = renamed, batches = batches, errors = errors)
    }

    private fun List<Request>.distinctByElement(): List<Request> {
        val seen = IdentityHashMap<PsiNamedElement, Boolean>()
        return filter { request -> seen.put(request.element, true) == null }
    }

    private fun prepareChunk(requests: List<Request>): List<PreparedRequest> =
        ReadAction.compute<List<PreparedRequest>, RuntimeException> {
            val pointerManager = SmartPointerManager.getInstance(project)
            requests.map { request ->
                PreparedRequest(
                    oldName = request.element.name.orEmpty(),
                    pointer = pointerManager.createSmartPsiElementPointer(request.element),
                    newName = request.newName,
                )
            }
        }

    /** Each chunk gets its own EDT turn so queued repaint/progress events run between chunks. */
    private fun runChunkOnEdt(requests: List<Request>): ChunkResult {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return executeChunk(prepareChunk(requests))

        var attempt = 0
        while (true) {
            waitForIndexes()
            try {
                // Prepare the pointers inside the same retry window as the EDT mutation. The
                // previous implementation prepared them before entering this loop, so an index
                // transition between waitForSmartMode() and prepareChunk() escaped the retry and
                // surfaced as the Refactor Failed dialog.
                val chunk = prepareChunk(requests)
                var result: ChunkResult? = null
                application.invokeAndWait {
                    result = executeChunk(chunk)
                }
                return requireNotNull(result)
            } catch (error: Throwable) {
                if (!hasIndexNotReadyCause(error) || attempt++ >= MAX_INDEX_RETRIES) throw error
                ProgressManager.getInstance().progressIndicator?.text2 =
                    "Indexes changed; retrying class batch (${attempt}/${MAX_INDEX_RETRIES})"
            }
        }
    }

    private fun hasIndexNotReadyCause(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is IndexNotReadyException) return true
            current = current.cause
        }
        return false
    }

    private fun waitForIndexes() {
        if (ApplicationManager.getApplication().isDispatchThread) return
        ProgressManager.getInstance().progressIndicator?.text2 = "Waiting for indexes..."
        DumbService.getInstance(project).waitForSmartMode()
    }

    private fun executeChunk(chunk: List<PreparedRequest>): ChunkResult {
        val errors = mutableListOf<String>()
        var alreadyRenamed = 0
        val resolved = chunk.mapNotNull { request ->
            val element = currentDeclaration(request.pointer.element)
            if (element == null || !element.isValid) {
                errors.add("${request.oldName}: declaration became invalid before rename")
                null
            } else if (hasRepeatedTargetSuffix(element.name, request.newName)) {
                // This is the important rerun path: the declaration may already be
                // FooINV160INV160 before this execution starts, so request.oldName itself
                // cannot be used to derive the suffix. Normalize it before it enters a new
                // automatic batch.
                if (repairRepeatedTarget(element, request.newName)) {
                    val repaired = currentDeclaration(element)
                    if (repaired?.name == request.newName) {
                        alreadyRenamed++
                        null
                    } else {
                        errors.add("${request.oldName}: repeated suffix could not be normalized")
                        null
                    }
                } else {
                    errors.add("${request.oldName}: repeated suffix repair failed")
                    null
                }
            } else if (element.name == request.newName) {
                alreadyRenamed++
                null
            } else {
                Request(element, request.newName)
            }
        }
        if (resolved.isEmpty()) {
            return ChunkResult(renamed = alreadyRenamed, errors = errors)
        }

        return try {
            val first = resolved.first()
            val explicitElements = resolved.map { it.element }
            val processor = AutoAcceptRenameProcessor(
                project = project,
                element = first.element,
                newName = first.newName,
                explicitElements = explicitElements,
                // Every selected production class is already an explicit request. Letting
                // IntelliJ add related class renames makes a later batch request rename the
                // same declaration a second time (FooINV160INV160). References are handled by
                // this processor and files by the executor's file phase.
                acceptAutomaticRenames = false,
            )
            resolved.drop(1).forEach { request ->
                processor.addElement(request.element, request.newName)
            }
            processor.setSearchInComments(false)
            processor.setSearchTextOccurrences(false)
            processor.run()

            var renamed = 0
            val retryRequests = mutableListOf<Request>()
            for (request in chunk) {
                val element = currentDeclaration(request.pointer.element, request.newName)
                when {
                    element?.name == request.newName -> renamed++
                    element != null && element.isValid && hasRepeatedTargetSuffix(
                        actualName = element.name,
                        oldName = request.oldName,
                        targetName = request.newName,
                    ) -> {
                        // Automatic related renames can occasionally apply the same suffix
                        // twice (for example FooINV160INV160). Do not send that declaration
                        // through the automatic path again: it would make the duplicate worse.
                        // A fresh plain RenameProcessor still updates references, but has no
                        // automatic related rename pass that can append the suffix again.
                        if (repairRepeatedTarget(element, request.newName)) {
                            val repaired = currentDeclaration(element)
                            if (repaired?.name == request.newName) {
                                renamed++
                            } else {
                                retryRequests += Request(repaired ?: element, request.newName)
                            }
                        } else {
                            retryRequests += Request(element, request.newName)
                        }
                    }
                    element != null && element.isValid -> retryRequests += Request(element, request.newName)
                    request.oldName != request.newName ->
                        errors += "${request.oldName}: declaration became invalid after rename"
                }
            }
            ChunkResult(renamed = renamed, errors = errors, retryRequests = retryRequests)
        } catch (error: IndexNotReadyException) {
            throw error
        } catch (error: Exception) {
            errors.add(error.message ?: error.javaClass.simpleName)
            ChunkResult(renamed = 0, errors = errors)
        }
    }

    private fun hasRepeatedTargetSuffix(actualName: String?, targetName: String): Boolean {
        if (actualName.isNullOrBlank() || suffixToAdd.isBlank()) return false
        return actualName.endsWith(suffixToAdd + suffixToAdd, ignoreCase = true) &&
            !actualName.equals(targetName, ignoreCase = true)
    }

    private fun hasRepeatedTargetSuffix(actualName: String?, oldName: String, targetName: String): Boolean =
        hasRepeatedTargetSuffix(actualName, targetName)

    private fun repairRepeatedTarget(element: PsiNamedElement, targetName: String): Boolean =
        try {
            RenameProcessor(project, element, targetName, false, false).run()
            true
        } catch (error: IndexNotReadyException) {
            throw error
        } catch (_: Exception) {
            false
        }

    /**
     * RenameProcessor can replace a Kotlin declaration while leaving the original smart-pointer
     * element valid. A fallback using that stale PSI object can then report a successful command
     * but leave the declaration unchanged. Resolve the current real declaration from its file
     * before every attempt, matching by FQN and current/target name.
     */
    private fun currentDeclaration(element: PsiNamedElement?, expectedName: String? = null): PsiNamedElement? {
        if (element == null || !element.isValid) return null
        val file = element.containingFile ?: return element
        val virtualFile = file.virtualFile ?: return element
        val currentName = element.name
        val currentFqn = when (element) {
            is KtClassOrObject -> element.fqName?.asString()
            is PsiClass -> element.qualifiedName
            else -> null
        }
        val freshFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return element
        return when (freshFile) {
            is KtFile -> freshFile.collectDescendantsOfType<KtClassOrObject>().firstOrNull { declaration ->
                    declaration.fqName?.asString() == currentFqn && declaration.name == currentName
                }
                ?: freshFile.collectDescendantsOfType<KtClassOrObject>().firstOrNull { declaration ->
                    isExpectedOrRepeatedName(declaration.name, expectedName)
                }
            is PsiJavaFile -> freshFile.classes
                .firstOrNull { declaration ->
                    declaration.qualifiedName == currentFqn && declaration.name == currentName
                }
                ?: freshFile.classes.firstOrNull { declaration ->
                    isExpectedOrRepeatedName(declaration.name, expectedName)
                }
            else -> null
        }
    }

    private fun isExpectedOrRepeatedName(actualName: String?, expectedName: String?): Boolean {
        if (actualName == null || expectedName == null) return false
        if (actualName == expectedName) return true
        if (suffixToAdd.isBlank()) return false
        return actualName.startsWith(expectedName) &&
            actualName.endsWith(suffixToAdd + suffixToAdd, ignoreCase = true)
    }

    private companion object {
        /** Bounds RenameProcessor's retained usages and automatic-renamer state on a 2 GB IDE heap. */
        const val MAX_RENAMES_PER_BATCH = 20
        const val MAX_INDEX_RETRIES = 3
    }
}
