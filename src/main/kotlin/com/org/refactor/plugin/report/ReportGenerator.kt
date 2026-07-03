package com.org.refactor.plugin.report

import com.org.refactor.plugin.executor.RefactorExecutor
import com.org.refactor.plugin.model.*
import com.org.refactor.plugin.verification.VerificationResult
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ReportGenerator {

    fun generateMarkdown(
        plan: RefactorPlan,
        execution: RefactorExecutor.ExecutionResult,
        verification: VerificationResult,
        outputDir: String,
    ): String {
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val report = buildString {
            appendLine("# Android Refactor Report")
            appendLine()
            appendLine("**Generated:** $timestamp")
            appendLine("**Suffix:** `${plan.suffix}`")
            appendLine("**Duration:** ${execution.durationMs}ms")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("| Metric | Count |")
            appendLine("|--------|-------|")
            appendLine("| Classes renamed | ${execution.classesRenamed} |")
            appendLine("| Symbols renamed | ${execution.symbolsRenamed} |")
            appendLine("| References updated | ${execution.referencesUpdated} |")
            appendLine("| Files renamed | ${execution.filesRenamed} |")
            appendLine("| Errors | ${execution.errors.size} |")
            appendLine("| Warnings | ${execution.warnings.size} |")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Classes Renamed")
            appendLine()
            for (rename in plan.componentRenames.filter { it.checked }) {
                appendLine("- `${rename.oldName}` → `${rename.newName}` (${rename.componentType.name})")
            }
            appendLine()
            appendLine("## Symbols Renamed")
            appendLine()
            for (rename in plan.symbolRenames.filter { it.checked }) {
                appendLine("- `${rename.oldName}` → `${rename.newName}` (${rename.kind.name})")
            }
            appendLine()

            if (execution.errors.isNotEmpty()) {
                appendLine("## Errors")
                appendLine()
                for (error in execution.errors) {
                    appendLine("- ❌ $error")
                }
                appendLine()
            }
            if (execution.warnings.isNotEmpty()) {
                appendLine("## Warnings")
                appendLine()
                for (warning in execution.warnings) {
                    appendLine("- ⚠️ $warning")
                }
                appendLine()
            }
            appendLine("---")
            appendLine()
            appendLine("## Verification")
            appendLine()
            appendLine("| Check | Status |")
            appendLine("|-------|--------|")
            appendLine("| PSI errors | ${if (verification.psiErrors.isEmpty()) "✅" else "❌ ${verification.psiErrors.size}"} |")
            appendLine("| Duplicate symbols | ${if (verification.duplicateSymbols.isEmpty()) "✅" else "❌ ${verification.duplicateSymbols.size}"} |")
            appendLine("| Broken imports | ${if (verification.brokenImports.isEmpty()) "✅" else "❌ ${verification.brokenImports.size}"} |")
            appendLine()
            appendLine("**Overall:** ${if (verification.passed) "✅ PASSED" else "❌ FAILED"}")
        }

        val outputFile = File(outputDir, "refactor-report-${System.currentTimeMillis()}.md")
        outputFile.writeText(report)
        return outputFile.absolutePath
    }

    fun generateJson(
        plan: RefactorPlan,
        execution: RefactorExecutor.ExecutionResult,
        verification: VerificationResult,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"timestamp\": \"${LocalDateTime.now()}\",")
        sb.appendLine("  \"suffix\": \"${plan.suffix}\",")
        sb.appendLine("  \"durationMs\": ${execution.durationMs},")
        sb.appendLine("  \"success\": ${execution.success},")
        sb.appendLine("  \"classesRenamed\": ${execution.classesRenamed},")
        sb.appendLine("  \"symbolsRenamed\": ${execution.symbolsRenamed},")
        sb.appendLine("  \"referencesUpdated\": ${execution.referencesUpdated},")
        sb.appendLine("  \"filesRenamed\": ${execution.filesRenamed},")
        sb.append("  \"verificationPassed\": ${verification.passed},")
        sb.appendLine("  \"errors\": ${execution.errors},")
        sb.appendLine("  \"warnings\": ${execution.warnings}")
        sb.appendLine("}")
        return sb.toString()
    }
}
