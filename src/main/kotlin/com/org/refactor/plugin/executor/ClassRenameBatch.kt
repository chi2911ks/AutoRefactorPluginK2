package com.org.refactor.plugin.executor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement

/** Runs all selected class renames through one IntelliJ rename transaction. */
internal class ClassRenameBatch(private val project: Project) {

    data class Request(
        val element: PsiNamedElement,
        val newName: String,
    )

    data class Result(
        val success: Boolean,
        val renamed: Int,
        val errors: List<String>,
    )

    fun execute(requests: List<Request>): Result {
        if (requests.isEmpty()) return Result(success = true, renamed = 0, errors = emptyList())

        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return executeOnEdt(requests)

        var result: Result? = null
        application.invokeAndWait {
            result = executeOnEdt(requests)
        }
        return requireNotNull(result)
    }

    private fun executeOnEdt(requests: List<Request>): Result {
        return try {
            val first = requests.first()
            val processor = AutoAcceptRenameProcessor(project, first.element, first.newName)
            requests.drop(1).forEach { request ->
                processor.addElement(request.element, request.newName)
            }
            processor.setSearchInComments(false)
            processor.setSearchTextOccurrences(false)
            processor.respectAllAutomaticRenames(requests.map { it.element })
            processor.run()
            Result(success = true, renamed = requests.size, errors = emptyList())
        } catch (error: Exception) {
            Result(
                success = false,
                renamed = 0,
                errors = listOf(error.message ?: error.javaClass.simpleName),
            )
        }
    }
}
