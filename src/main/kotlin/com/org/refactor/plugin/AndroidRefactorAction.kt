package com.org.refactor.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.org.refactor.plugin.executor.RefactorExecutor
import com.org.refactor.plugin.model.RefactorPlan
import com.org.refactor.plugin.report.ReportGenerator
import com.org.refactor.plugin.shuffle.DeclarationShuffler
import com.org.refactor.plugin.ui.RefactorDialog
import com.org.refactor.plugin.verification.VerificationEngine

class AndroidRefactorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val dialog = RefactorDialog(project)
        if (dialog.showAndGet()) {
            val plan = dialog.refactorPlan
            if (plan == null) {
                Messages.showErrorDialog(project, "No plan generated. Please scan first.", "Error")
                return
            }

            val executor = RefactorExecutor(project)
            val executionResult = executor.execute(plan)

            val verifier = VerificationEngine(project)
            val verificationResult = verifier.verify(plan.componentRenames)

            val baseDir = project.basePath ?: System.getProperty("user.home")
            val reporter = ReportGenerator()
            val reportPath = reporter.generateMarkdown(plan, executionResult, verificationResult, baseDir)

            if (executionResult.success) {
                Messages.showInfoMessage(
                    project,
                    buildString {
                        appendLine("Refactor completed!")
                        appendLine()
                        appendLine("Classes renamed: ${executionResult.classesRenamed}")
                        appendLine("Symbols renamed: ${executionResult.symbolsRenamed}")
                        appendLine("References updated: ${executionResult.referencesUpdated}")
                        appendLine("Duration: ${executionResult.durationMs}ms")
                        appendLine("Verification: ${if (verificationResult.passed) "PASSED" else "FAILED"}")
                        appendLine()
                        appendLine("Report: $reportPath")
                    },
                    "Refactor Complete"
                )

                // Optional post-step: shuffle declaration order in the refactored Kotlin files.
                offerShuffle(project, plan)
            } else {
                Messages.showErrorDialog(
                    project,
                    buildString {
                        appendLine("Refactor failed with ${executionResult.errors.size} error(s):")
                        appendLine(executionResult.errors.take(5).joinToString("\n"))
                        appendLine()
                        appendLine("Report: $reportPath")
                    },
                    "Refactor Failed"
                )
            }
        }
    }

    private fun offerShuffle(project: com.intellij.openapi.project.Project, plan: RefactorPlan) {
        // Files renamed on disk keep their new path — translate old source paths through fileRenames.
        val renameMap = plan.fileRenames.associate { it.oldPath to it.newPath }
        val targets = (plan.componentRenames.map { it.sourceFile } + plan.symbolRenames.map { it.sourceFile })
            .map { renameMap[it] ?: it }
            .filter { it.endsWith(".kt") }
            .distinct()
        if (targets.isEmpty()) return

        val answer = Messages.showYesNoDialog(
            project,
            buildString {
                appendLine("Xáo trộn thứ tự khai báo hàm/biến trong ${targets.size} file vừa refactor?")
                appendLine()
                appendLine("• Giữ phụ thuộc (biến liên quan di chuyển cùng nhau)")
                append("• Bỏ qua companion object và các thành phần lồng nhau")
            },
            "Shuffle Declarations",
            "Xáo trộn",
            "Bỏ qua",
            Messages.getQuestionIcon()
        )
        if (answer != Messages.YES) return

        val result = DeclarationShuffler(project).shuffle(targets)
        Messages.showInfoMessage(
            project,
            "Đã xáo trộn ${result.filesChanged}/${result.filesScanned} file.",
            "Shuffle Complete"
        )
    }
}
