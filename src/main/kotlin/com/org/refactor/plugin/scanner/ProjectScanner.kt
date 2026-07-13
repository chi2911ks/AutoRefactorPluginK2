package com.org.refactor.plugin.scanner

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.VirtualFileManager
import com.org.refactor.plugin.model.*

class ProjectScanner(private val project: Project) {

    data class ScanDebug(
        val rootPaths: List<String> = emptyList(),
        val kotlinCount: Int = 0,
        val javaCount: Int = 0,
        val xmlCount: Int = 0,
        val errors: List<String> = emptyList(),
    )

    var debug = ScanDebug()
        private set

    fun scan(selectedModuleNames: Set<String>? = null): ProjectIndex {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val debugErrors = mutableListOf<String>()

        val kotlinFiles = mutableListOf<SourceFile>()
        val javaFiles = mutableListOf<SourceFile>()
        val xmlFiles = mutableListOf<SourceFile>()
        val gradleBuildFiles = mutableListOf<VirtualFile>()
        val walkedDirs = mutableSetOf<String>()

        // Gather all roots
        val roots = gatherRoots(selectedModuleNames)
        val rootPaths = roots.map { it.path }

        for (root in roots) {
            try {
                walkRoot(
                    root,
                    kotlinFiles,
                    javaFiles,
                    xmlFiles,
                    gradleBuildFiles,
                    fileIndex,
                    walkedDirs,
                    selectedModuleNames,
                )
            } catch (e: Exception) {
                debugErrors.add("${root.path}: ${e.message}")
            }
        }

        // Some Android Studio source-set modules expose roots that ProjectFileIndex marks as
        // excluded or owned by a sibling `.main` module. Fall back to a path-based walk of the
        // selected content roots so choosing one logical module never produces an empty scan.
        if (selectedModuleNames != null && kotlinFiles.isEmpty()) {
            for (root in roots) {
                try {
                    plainFileFallback(java.io.File(root.path), kotlinFiles, javaFiles, xmlFiles)
                } catch (e: Exception) {
                    debugErrors.add("Module fallback ${root.path}: ${e.message}")
                }
            }
        } else if (
            selectedModuleNames == null &&
            kotlinFiles.isEmpty() && javaFiles.isEmpty() && xmlFiles.isEmpty()
        ) {
            project.basePath?.let { basePath ->
                try {
                    plainFileFallback(java.io.File(basePath), kotlinFiles, javaFiles, xmlFiles)
                } catch (e: Exception) {
                    debugErrors.add("Fallback: ${e.message}")
                }
            }
        }

        val androidModules = detectAndroidModules(gradleBuildFiles)
        val modules = ModuleManager.getInstance(project).modules.map { m ->
            ModuleInfo(name = m.name, sourceRoots = emptyList(), isAndroidModule = m.name in androidModules)
        }

        debug = ScanDebug(
            rootPaths = rootPaths,
            kotlinCount = kotlinFiles.size,
            javaCount = javaFiles.size,
            xmlCount = xmlFiles.size,
            errors = debugErrors,
        )

        return ProjectIndex(
            modules = modules,
            allKotlinFiles = kotlinFiles.distinctBy { it.absolutePath },
            allJavaFiles = javaFiles.distinctBy { it.absolutePath },
            allXmlFiles = xmlFiles.distinctBy { it.absolutePath },
            manifestFiles = xmlFiles.distinctBy { it.absolutePath }.filter { it.fileType == FileType.XML_MANIFEST },
            navigationGraphs = xmlFiles.distinctBy { it.absolutePath }.filter { it.fileType == FileType.XML_NAVIGATION },
            gradleModules = gradleBuildFiles.map { it.parent?.name ?: "root" },
        )
    }

    private fun gatherRoots(selectedModuleNames: Set<String>?): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        val allModules = ModuleManager.getInstance(project).modules

        if (selectedModuleNames != null) {
            return allModules
                .asSequence()
                .filter { ModuleSelection.logicalName(it.name) in selectedModuleNames }
                .flatMap { ModuleRootManager.getInstance(it).contentRoots.asSequence() }
                .distinctBy { it.path }
                .toList()
        }

        // Module content roots
        for (m in allModules) {
            result.addAll(ModuleRootManager.getInstance(m).contentRoots.toList())
        }
        // Project content roots
        result.addAll(ProjectRootManager.getInstance(project).contentRoots.toList())
        // Base dir
        val baseDir = project.baseDir
        if (baseDir != null && result.none { it.path == baseDir.path }) {
            result.add(baseDir)
        }

        return result.distinctBy { it.path }
    }

    private fun walkRoot(
        root: VirtualFile,
        kotlinFiles: MutableList<SourceFile>,
        javaFiles: MutableList<SourceFile>,
        xmlFiles: MutableList<SourceFile>,
        gradleBuildFiles: MutableList<VirtualFile>,
        fileIndex: ProjectFileIndex,
        walkedDirs: MutableSet<String>,
        selectedModuleNames: Set<String>?,
    ) {
        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Void>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    if (file.name in SKIPPED_DIRECTORIES) return false
                    // For a selected logical module, its content roots are authoritative. Android
                    // Studio may mark a parent module root as excluded because `.main` owns files.
                    if (selectedModuleNames == null && fileIndex.isExcluded(file)) return false
                    return walkedDirs.add(file.path)
                }
                val name = file.name
                val ext = file.extension?.lowercase() ?: return true
                val moduleName = fileIndex.getModuleForFile(file)?.name ?: "unknown"

                when (ext) {
                    "kt" -> kotlinFiles.add(SourceFile(
                        virtualFilePath = file.path, absolutePath = file.path,
                        moduleName = moduleName, fileType = FileType.KOTLIN
                    ))
                    "java" -> javaFiles.add(SourceFile(
                        virtualFilePath = file.path, absolutePath = file.path,
                        moduleName = moduleName, fileType = FileType.JAVA
                    ))
                    "xml" -> {
                        val type = when {
                            name.equals("AndroidManifest.xml", ignoreCase = true) -> FileType.XML_MANIFEST
                            file.path.contains("navigation") -> FileType.XML_NAVIGATION
                            file.path.contains("layout") || file.path.contains("res/") -> FileType.XML_LAYOUT
                            else -> FileType.OTHER
                        }
                        xmlFiles.add(SourceFile(
                            virtualFilePath = file.path, absolutePath = file.path,
                            moduleName = moduleName, fileType = type
                        ))
                    }
                }

                if (name == "build.gradle" || name == "build.gradle.kts") {
                    gradleBuildFiles.add(file)
                }
                return true
            }
        })
    }

    private fun plainFileFallback(
        base: java.io.File,
        kotlinFiles: MutableList<SourceFile>,
        javaFiles: MutableList<SourceFile>,
        xmlFiles: MutableList<SourceFile>,
    ) {
        val stack = ArrayDeque<java.io.File>()
        stack.add(base)
        val visited = mutableSetOf<String>()
        val skip = setOf("build", ".git", ".gradle", ".idea", "node_modules", "__pycache__")
        val virtualPath = base.absolutePath.replace('\\', '/')

        while (stack.isNotEmpty()) {
            val dir = stack.removeFirst()
            if (!visited.add(dir.absolutePath)) continue
            val children = dir.listFiles() ?: continue

            for (child in children) {
                if (child.isDirectory) {
                    if (child.name !in skip) stack.add(child)
                } else {
                    val ext = child.extension.lowercase()
                    val absPath = child.absolutePath.replace('\\', '/')
                    val sf = SourceFile(
                        virtualFilePath = absPath, absolutePath = absPath,
                        moduleName = "unknown", fileType = FileType.OTHER
                    )
                    when (ext) {
                        "kt" -> kotlinFiles.add(sf.copy(fileType = FileType.KOTLIN))
                        "java" -> javaFiles.add(sf.copy(fileType = FileType.JAVA))
                        "xml" -> {
                            val path = absPath.lowercase()
                            val type = when {
                                "androidmanifest" in path && path.endsWith(".xml") -> FileType.XML_MANIFEST
                                "/navigation/" in path -> FileType.XML_NAVIGATION
                                "/layout/" in path || "/res/" in path -> FileType.XML_LAYOUT
                                else -> FileType.OTHER
                            }
                            xmlFiles.add(sf.copy(fileType = type))
                        }
                    }
                }
            }
        }
    }

    private fun detectAndroidModules(buildFiles: List<VirtualFile>): Set<String> {
        val androidModules = mutableSetOf<String>()
        for (vFile in buildFiles) {
            try {
                val content = String(vFile.contentsToByteArray())
                if (content.contains("com.android.application") ||
                    content.contains("com.android.library") ||
                    content.contains("android {") ||
                    content.contains("\"org.jetbrains.kotlin.android\"")
                ) {
                    androidModules.add(vFile.parent?.name ?: "root")
                }
            } catch (_: Exception) {}
        }
        return androidModules
    }

    private companion object {
        val SKIPPED_DIRECTORIES = setOf(
            "build", ".gradle", ".git", ".idea", "node_modules", "__pycache__",
        )
    }
}
