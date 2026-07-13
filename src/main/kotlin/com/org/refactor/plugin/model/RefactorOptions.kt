package com.org.refactor.plugin.model

data class RefactorOptions(
    val suffixToAdd: String,
    val suffixToRemove: String = "",
    /** Null means all project modules; otherwise only these module names are targets. */
    val selectedModuleNames: Set<String>? = null,
    val refactorClasses: Boolean = true,
    val refactorFunctions: Boolean = false,
    val refactorVariables: Boolean = false,
    val shuffleFunctions: Boolean = false,
    val shuffleVariables: Boolean = false,
) {
    val hasRefactorOperation: Boolean
        get() = refactorClasses || refactorFunctions || refactorVariables

    val hasShuffleOperation: Boolean
        get() = shuffleFunctions || shuffleVariables

    val hasAnyOperation: Boolean
        get() = hasRefactorOperation || hasShuffleOperation
}
