package com.org.refactor.plugin.model

data class ProjectIndex(
    val modules: List<ModuleInfo>,
    val allKotlinFiles: List<SourceFile>,
    val allJavaFiles: List<SourceFile>,
    val allXmlFiles: List<SourceFile>,
    val manifestFiles: List<SourceFile>,
    val navigationGraphs: List<SourceFile>,
    val gradleModules: List<String>,
    val androidResourceFiles: List<AndroidResourceFile> = emptyList(),
)

data class ModuleInfo(
    val name: String,
    val sourceRoots: List<String>,
    val isAndroidModule: Boolean,
)
