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

Class-only refactors skip function/variable symbol collection. Resolve class declarations once per source file, then submit selected Kotlin/Java class renames through one IntelliJ multi-element rename transaction. Rename planned class files in write-command context before the PSI batch so later smart pointers do not retain stale paths.

Build one class-name map and enumerate XML and known ProGuard candidates through indexes once. XML class references are rewritten contextually through mapped `android:name` attributes; unrelated attributes, comments, and arbitrary text are not changed by this optimization.

Keep regression coverage for Java/Kotlin batch renames, usage updates, file renames, class-only scan skipping, and contextual XML rewriting.

## Testing Guidelines

Tests use JUnit Jupiter 5.10. Name classes `*Test`; cover conflicts, module grouping, SDK overrides, accessor collisions, and idempotent reruns. Validate IDE behavior with `runIde` and inspect `.autorefactor-symbols.log` when diagnosing renames.

## Commit & Pull Request Guidelines

Use imperative commit subjects and one change per commit. Pull requests should describe behavior and verification, link issues, include UI screenshots, and note compatibility changes.
