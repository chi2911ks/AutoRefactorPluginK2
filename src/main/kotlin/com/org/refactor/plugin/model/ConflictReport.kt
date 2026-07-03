package com.org.refactor.plugin.model

data class ConflictReport(
    val conflicts: List<RefactorConflict>,
    val isSafe: Boolean,
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

data class RefactorConflict(
    val type: ConflictType,
    val severity: ConflictSeverity,
    val message: String,
    val sourceFile: String? = null,
    val symbolName: String? = null,
    val suggestion: String? = null,
)

enum class ConflictType {
    DUPLICATE_NAME,
    OVERRIDE_CONFLICT,
    INHERITANCE_ISSUE,
    VISIBILITY_PROBLEM,
    JAVA_INTEROP,
    REFLECTION_RISK,
    BINARY_COMPATIBILITY,
    EXTERNAL_REFERENCE,
    GENERATED_CODE,
    RESERVED_NAME,
}

enum class ConflictSeverity {
    ERROR, WARNING, INFO
}
