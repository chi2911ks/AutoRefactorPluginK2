package com.org.refactor.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.org.refactor.plugin.executor.RefactorExecutor
import com.org.refactor.plugin.report.ReportGenerator
import com.org.refactor.plugin.shuffle.DeclarationShuffler
import com.org.refactor.plugin.ui.RefactorDialog
import com.org.refactor.plugin.verification.VerificationEngine
import com.org.refactor.plugin.verification.VerificationResult

class AndroidRefactorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = RefactorDialog(project)
        if (!dialog.showAndGet()) return

        val plan = dialog.refactorPlan
        if (plan == null) {
            Messages.showErrorDialog(project, "No valid plan generated. Please scan again.", "Error")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Applying Kotlin Refactor...",
            false,
        ) {
            private var execution: RefactorExecutor.ExecutionResult? = null
            private var shuffleResult: DeclarationShuffler.Result? = null
            private var verification: VerificationResult? = null
            private var reportPath: String? = null
            private var fatalError: String? = null
            private var totalDurationMs: Long = 0

            override fun run(indicator: ProgressIndicator) {
                val startedAt = System.currentTimeMillis()
                try {
                    indicator.isIndeterminate = true
                    indicator.text = "Resolving usages and applying renames..."
                    val result = RefactorExecutor(project).execute(plan)
                    execution = result

                    if (result.success && plan.options.hasShuffleOperation) {
                        indicator.text = "Shuffling declarations..."
                        val renameMap = plan.fileRenames.associate { it.oldPath to it.newPath }
                        val targets = plan.shuffleFilePaths.map { renameMap[it] ?: it }.distinct()
                        shuffleResult = DeclarationShuffler(project).shuffle(
                            targets,
                            shuffleFunctions = plan.options.shuffleFunctions,
                            shuffleVariables = plan.options.shuffleVariables,
                        )
                    }

                    indicator.text = "Verifying affected files..."
                    verification = VerificationEngine(project).verify(plan)

                    indicator.text = "Writing report..."
                    val baseDir = project.basePath ?: System.getProperty("user.home")
                    reportPath = ReportGenerator().generateMarkdown(
                        plan,
                        result,
                        requireNotNull(verification),
                        baseDir,
                    )
                } catch (error: Throwable) {
                    fatalError = error.message ?: error.javaClass.simpleName
                } finally {
                    totalDurationMs = System.currentTimeMillis() - startedAt
                }
            }

            override fun onSuccess() {
                fatalError?.let { error ->
                    Messages.showErrorDialog(project, "Refactor failed: $error", "Refactor Failed")
                    return
                }
                val result = execution ?: return
                val verified = verification ?: return
                val report = reportPath.orEmpty()

                if (result.success) {
                    Messages.showInfoMessage(
                        project,
                        buildString {
                            appendLine("Refactor completed!")
                            appendLine()
                            appendLine("Classes renamed: ${result.classesRenamed}")
                            appendLine("Symbols renamed: ${result.symbolsRenamed}")
                            appendLine("Typealiases renamed: ${result.typeAliasesRenamed}")
                            appendLine("String resources renamed: ${result.stringsRenamed}")
                            appendLine("Drawables renamed: ${result.drawablesRenamed}")
                            appendLine("Layouts renamed: ${result.layoutsRenamed}")
                            appendLine("References updated: ${result.referencesUpdated}")
                            shuffleResult?.let {
                                appendLine("Files shuffled: ${it.filesChanged}/${it.filesScanned}")
                            }
                            appendLine("Total duration: ${totalDurationMs}ms")
                            appendLine("Verification: ${if (verified.passed) "PASSED" else "FAILED"}")
                            appendLine()
                            appendLine("Report: $report")
                        },
                        "Refactor Complete",
                    )
                } else {
                    Messages.showErrorDialog(
                        project,
                        buildString {
                            appendLine("Refactor failed with ${result.errors.size} error(s):")
                            appendLine(result.errors.take(5).joinToString("\n"))
                            appendLine()
                            appendLine("Report: $report")
                        },
                        "Refactor Failed",
                    )
                }
            }
        })
    }
}
