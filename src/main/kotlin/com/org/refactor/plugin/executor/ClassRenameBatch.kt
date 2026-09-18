package com.org.refactor.plugin.executor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer

/** Runs selected class renames through bounded IntelliJ multi-element transactions. */
internal class ClassRenameBatch(private val project: Project) {

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
        val chunks = requests.chunked(MAX_RENAMES_PER_BATCH)
        for ((index, requestChunk) in chunks.withIndex()) {
            batches++
            ProgressManager.getInstance().progressIndicator?.text2 =
                "Renaming class batch ${index + 1}/${chunks.size}"
            val chunkResult = runChunkOnEdt(requestChunk)
            renamed += chunkResult.renamed
            errors += chunkResult.errors.map { "Batch $batches: $it" }
        }

        return Result(success = errors.isEmpty(), renamed = renamed, batches = batches, errors = errors)
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
        val resolved = chunk.mapNotNull { request ->
            val element = request.pointer.element
            if (element == null || !element.isValid) {
                errors.add("${request.oldName}: declaration became invalid before rename")
                null
            } else {
                Request(element, request.newName)
            }
        }
        if (resolved.isEmpty()) return ChunkResult(renamed = 0, errors = errors)

        return try {
            val first = resolved.first()
            val processor = AutoAcceptRenameProcessor(project, first.element, first.newName)
            resolved.drop(1).forEach { request ->
                processor.addElement(request.element, request.newName)
            }
            processor.setSearchInComments(false)
            processor.setSearchTextOccurrences(false)
            processor.respectAllAutomaticRenames(resolved.map { it.element })
            processor.run()
            ChunkResult(renamed = resolved.size, errors = errors)
        } catch (error: IndexNotReadyException) {
            throw error
        } catch (error: Exception) {
            errors.add(error.message ?: error.javaClass.simpleName)
            ChunkResult(renamed = 0, errors = errors)
        }
    }

    private companion object {
        /** Bounds RenameProcessor's retained usages and automatic-renamer state on a 2 GB IDE heap. */
        const val MAX_RENAMES_PER_BATCH = 20
        const val MAX_INDEX_RETRIES = 3
    }
}
