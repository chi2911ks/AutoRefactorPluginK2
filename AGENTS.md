# Repository Guidelines

## Project Structure & Module Organization

This Kotlin refactoring plugin targets IntelliJ IDEA and Android Studio. Code lives under `src/main/kotlin/com/org/refactor/plugin/`, organized by workflow stage. The entry point is `AndroidRefactorAction.kt`; IDE registration is in `src/main/resources/META-INF/plugin.xml`.

Place tests in `src/test/kotlin/`, mirroring production packages. Keep generated artifacts and IDE sandboxes under uncommitted `build/`.

## Build, Test, and Development Commands

Use JDK 21 and the Gradle wrapper. On Windows, use `gradlew.bat`.

- `./gradlew buildPlugin` builds the ZIP in `build/distributions/`.
- `./gradlew runIde` launches a sandbox IDE.
- `./gradlew test` runs the JUnit 5 test suite.
- `./gradlew check` runs all verification tasks.

## Coding Style & Naming Conventions

Follow `kotlin.code.style=official`: four-space indentation, no tabs, and idiomatic null-safety. Use `PascalCase` for classes/objects, `camelCase` for functions/properties, and lowercase packages. Name files after their primary declaration, for example `ConflictDetector.kt`.

## Refactor Behavior

Scanning is Kotlin-only. Rename only top-level classes and objects; never rename nested declarations or enum entries. Class rename is enabled by default. Function and variable rename, plus declaration shuffling, are explicit checkbox options; parameters are never independent rename targets. Suffix removal (for example, `Inv124`) is shared by all enabled rename kinds.

The module picker supports multiple logical Gradle modules and groups source sets such as `app.main` under `app`. Keep "All modules" equivalent to selecting every logical module.

## Architecture Safety Rules

Centralize K2 Analysis API calls in `psi/K2Analysis.kt`. Resolve targets by `SymbolKind` to real Kotlin PSI, not name-only light methods. Collect, deduplicate, then apply replacements in descending offset order. Do not rename SDK/library overrides. Exclude synthetic property accessors that collide with real functions. Shuffling must preserve anchors, dependency blocks, and eager initialization order.

Keep scans and plan preparation in background tasks. Run PSI mutation and `RenameProcessor` work on EDT/write-intent context. `ImmediateRenameProcessor` must suppress usage preview and automatically accept every IntelliJ related-rename suggestion; do not reintroduce confirmation dialogs. Avoid per-symbol project scans: build shared indexes once and verify only affected files.

## Testing Guidelines

Tests use JUnit Jupiter 5.10. Name classes `*Test`; cover conflicts, module grouping, SDK overrides, accessor collisions, and idempotent reruns. Validate IDE behavior with `runIde` and inspect `.autorefactor-symbols.log` when diagnosing renames.

## Commit & Pull Request Guidelines

Use short, imperative commit subjects and keep each commit to one logical change. Pull requests should describe behavior, list verification, and link issues. Include screenshots for UI changes and note IntelliJ or K2 compatibility changes.
