package com.org.refactor.plugin.model

data class RefactorOptions(
    val suffixToAdd: String,
    val suffixToRemove: String = "",
    /** Null means all project modules; otherwise only these module names are targets. */
    val selectedModuleNames: Set<String>? = null,
    val refactorClasses: Boolean = true,
    val refactorFunctions: Boolean = false,
    val refactorVariables: Boolean = false,
    val refactorTypeAliases: Boolean = true,
    val refactorStrings: Boolean = true,
    val refactorColors: Boolean = true,
    val refactorStyles: Boolean = true,
    val refactorDrawables: Boolean = true,
    val refactorLayouts: Boolean = true,
    val shuffleFunctions: Boolean = false,
    val shuffleVariables: Boolean = false,
) {
    val hasRefactorOperation: Boolean
        get() = refactorClasses || refactorFunctions || refactorVariables || refactorTypeAliases ||
            refactorStrings || refactorColors || refactorStyles ||
            refactorDrawables || refactorLayouts

    val hasShuffleOperation: Boolean
        get() = shuffleFunctions || shuffleVariables

    val hasAnyOperation: Boolean
        get() = hasRefactorOperation || hasShuffleOperation
}
