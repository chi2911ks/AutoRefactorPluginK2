# Repository Guidelines

## Project Structure & Module Organization

Plugin code lives under `src/main/kotlin/com/org/refactor/plugin/`. `AndroidRefactorAction.kt` is the entry point; registration is in `src/main/resources/META-INF/plugin.xml`.

Mirror packages under `src/test/kotlin/`. Never commit `build/` artifacts.

## Build, Test, and Development Commands

Use JDK 21 and Gradle wrapper (`gradlew.bat` on Windows).

- `./gradlew buildPlugin` builds the ZIP in `build/distributions/`.
- `./gradlew runIde` launches a sandbox IDE.
- `./gradlew test` runs the JUnit 5 test suite.
- `./gradlew check` runs verification tasks.

## Coding Style & Naming Conventions

Follow `kotlin.code.style=official`: four spaces, no tabs, and idiomatic null-safety. Use `PascalCase` for types, `camelCase` for functions/properties, and lowercase packages.

## Refactor Behavior

Symbol scanning supports Kotlin and Java source declarations. Shuffling remains Kotlin-only. Rename top-level classes/objects, never nested declarations or enum entries. Classes, typealiases, drawables, and layouts are enabled by default. Typealiases rename their identifiers; class refactoring may update expanded types. Functions, variables, and shuffling are opt-in; parameters are never targets. Remove text case-insensitively before appending suffixes. Resource names use lowercase `_segments`; collapse duplicate underscores. Rename qualifier variants together, skip `mipmap`, and update View Binding without generated edits. Strings, colors, and styles default selected and rename across variants with target prefix.

The module picker groups source sets such as `app.main` under `app`. Keep "All modules" equivalent to selecting modules.

## Architecture Safety Rules

Centralize K2 Analysis API calls in `psi/K2Analysis.kt`. Resolve targets by `SymbolKind` to real Kotlin/Java PSI, not name-only light methods. Collect, deduplicate, then apply replacements in descending offset order. Do not rename SDK/library overrides. Exclude synthetic property accessors that collide with real functions. Shuffling must preserve anchors, dependency blocks, and eager initialization order.

Keep scans and plan preparation in background tasks. Run PSI mutation, `RenameProcessor`, and multi-element `RenameRefactoring` work on EDT/write-intent context. `ImmediateRenameProcessor` and the class rename batch must suppress usage preview and automatically accept every IntelliJ related-rename suggestion; do not reintroduce confirmation dialogs. Avoid per-symbol project scans: build shared indexes once and verify only affected files.

Resource replacements must be contextual (`R.layout`, `R.drawable`, `@layout`, `@drawable`) and run with qualifier file renames in one write command. Skip the whole logical resource on collision or read-only variants.

## Class Refactor Performance Safeguards

Class-only refactors skip function/variable symbol collection. Resolve class declarations once per source file, then submit selected Kotlin/Java class renames through bounded IntelliJ multi-element rename transactions. Keep smart-pointer preparation scoped to the current batch; do not retain pointers/usages for the entire all-module plan. Use a conservative batch size (currently 20) and allow progress events between batches.

Class declarations must be renamed before their source files. Rename planned class files only after every selected class declaration resolves and the class batch completes successfully. If any declaration cannot be resolved, or the number of successful class renames is lower than the planned count, skip all class-file renames to prevent files with renamed names and stale Java/Kotlin declarations.

Wait for Smart Mode before index-dependent reads and retry when `IndexNotReadyException` occurs between the wait and the read/rename operation. Apply this to class batches, class-reference indexing, and post-refactor verification. Do not surface an avoidable Dumb Mode race as a fatal refactor dialog.

Build one class-name map and enumerate XML and known ProGuard candidates through indexes once. XML class references are rewritten contextually through mapped `android:name` attributes; unrelated attributes, comments, and arbitrary text are not changed by this optimization.

Keep regression coverage for Java/Kotlin batch renames, usage updates, file renames, class-only scan skipping, and contextual XML rewriting.

Large all-module refactors must be treated as memory-sensitive: avoid retaining PSI, usage, or undo state for completed batches, and test first with one module. A build failure caused by Windows paging-file exhaustion is an environment limitation, not a code verification result.

## Testing Guidelines

Tests use JUnit Jupiter 5.10. Name classes `*Test`; cover conflicts, module grouping, SDK overrides, accessor collisions, and idempotent reruns. Validate IDE behavior with `runIde` and inspect `.autorefactor-symbols.log` when diagnosing renames.

## Commit & Pull Request Guidelines

Use imperative commit subjects and one change per commit. Pull requests should describe behavior and verification, link issues, include UI screenshots, and note compatibility changes.
