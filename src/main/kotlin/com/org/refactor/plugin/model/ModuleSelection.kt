package com.org.refactor.plugin.model

/** Maps IntelliJ source-set modules (for example `app.main`) to their logical Gradle module. */
object ModuleSelection {
    fun logicalName(moduleName: String): String {
        val suffix = moduleName.substringAfterLast('.', missingDelimiterValue = "")
        return if (isSourceSetName(suffix)) moduleName.substringBeforeLast('.') else moduleName
    }

    fun shortDisplayName(logicalName: String): String = logicalName.substringAfterLast('.')

    private fun isSourceSetName(name: String): Boolean =
        name == "main" ||
            name == "test" ||
            name == "unitTest" ||
            name == "androidTest" ||
            name == "testFixtures" ||
            name.endsWith("Main") ||
            name.endsWith("Test")
}
