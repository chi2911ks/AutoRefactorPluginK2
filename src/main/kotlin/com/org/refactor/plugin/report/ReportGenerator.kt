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
            appendLine("# Project Refactor Report")
            appendLine()
            appendLine("**Generated:** $timestamp")
            appendLine("**Suffix:** `${plan.suffix}`")
            appendLine("**Removed text:** `${plan.options.suffixToRemove}`")
            val modules = plan.options.selectedModuleNames
                ?.map(ModuleSelection::shortDisplayName)
                ?.sorted()
                ?.joinToString(", ")
                ?: "All modules"
            appendLine("**Modules:** `$modules`")
            appendLine("**Options:** classes=${plan.options.refactorClasses}, functions=${plan.options.refactorFunctions}, variables=${plan.options.refactorVariables}, typealiases=${plan.options.refactorTypeAliases}, strings=${plan.options.refactorStrings}, colors=${plan.options.refactorColors}, styles=${plan.options.refactorStyles}, drawables=${plan.options.refactorDrawables}, layouts=${plan.options.refactorLayouts}, shuffleFunctions=${plan.options.shuffleFunctions}, shuffleVariables=${plan.options.shuffleVariables}")
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
            appendLine("| Typealiases renamed | ${execution.typeAliasesRenamed} |")
            appendLine("| String resources renamed | ${execution.stringsRenamed} |")
            appendLine("| Drawables renamed | ${execution.drawablesRenamed} |")
            appendLine("| Layouts renamed | ${execution.layoutsRenamed} |")
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

            appendLine("## Typealiases Renamed")
            appendLine()
            for (rename in plan.typeAliasRenames) {
                val status = if (rename.checked) "ready" else "skipped: ${rename.skipReason}"
                appendLine("- `${rename.oldName}` -> `${rename.newName}` ($status)")
            }
            appendLine()

            appendLine("## Values XML Selection")
            appendLine()
            for (group in plan.valueXmlFileGroups) {
                val status = if (group.checked) "selected" else "excluded"
                appendLine(
                    "- `${group.fileName}` (${group.moduleName}, $status, " +
                        "${group.variants.size} variant(s))",
                )
            }
            appendLine()

            appendLine("## Value Resources")
            appendLine()
            for (rename in plan.stringResourceRenames) {
                val status = when {
                    rename.skipReason != null -> "skipped: ${rename.skipReason}"
                    rename.checked -> "selected"
                    else -> "not selected"
                }
                appendLine("- `${rename.oldName}` -> `${rename.newName}` (${rename.type.name}, $status, ${rename.variants.size} variant(s))")
            }
            appendLine()

            appendLine("## Resources")
            appendLine()
            for (rename in plan.resourceRenames) {
                val status = if (rename.checked) "ready" else "skipped: ${rename.skipReason}"
                appendLine("- `${rename.oldName}` -> `${rename.newName}` (${rename.type.name}, $status, ${rename.variants.size} variant(s))")
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
            appendLine("| Stale resource references | ${if (verification.staleResourceReferences.isEmpty()) "OK" else "FAILED ${verification.staleResourceReferences.size}"} |")
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
        sb.appendLine("  \"typeAliasesRenamed\": ${execution.typeAliasesRenamed},")
        sb.appendLine("  \"stringsRenamed\": ${execution.stringsRenamed},")
        sb.appendLine("  \"drawablesRenamed\": ${execution.drawablesRenamed},")
        sb.appendLine("  \"layoutsRenamed\": ${execution.layoutsRenamed},")
        sb.appendLine("  \"referencesUpdated\": ${execution.referencesUpdated},")
        sb.appendLine("  \"filesRenamed\": ${execution.filesRenamed},")
        sb.append("  \"verificationPassed\": ${verification.passed},")
        sb.appendLine("  \"errors\": ${execution.errors},")
        sb.appendLine("  \"warnings\": ${execution.warnings}")
        sb.appendLine("}")
        return sb.toString()
    }
}
