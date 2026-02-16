# AGENTS.md - Instructions for AI Coding Agents

<!-- OPENSPEC:START -->
# OpenSpec Instructions

These instructions are for AI assistants working in this project.

Always open `@/openspec/AGENTS.md` when the request:
- Mentions planning or proposals (words like proposal, spec, change, plan)
- Introduces new capabilities, breaking changes, architecture shifts, or big performance/security work
- Sounds ambiguous and you need the authoritative spec before coding

Use `@/openspec/AGENTS.md` to learn:
- How to create and apply change proposals
- Spec format and conventions
- Project structure and guidelines

Keep this managed block so 'openspec update' can refresh the instructions.

<!-- OPENSPEC:END -->

## Project Overview

This is the Prometheus Proxy project, a Kotlin-based application using Gradle for building. It provides proxy and agent
services for Prometheus metrics scraping, utilizing gRPC, Ktor, and other libraries. The codebase includes main sources
in src/main/kotlin/io/prometheus, tests in src/test/kotlin, and various configuration files.

The project follows OpenSpec methodology for managing changes and specifications. See openspec/AGENTS.md for detailed
OpenSpec workflows.

## Project Structure

- `src/main/kotlin/io/prometheus/` - Main application code
  - `agent/` - Agent service components
  - `proxy/` - Proxy service components
  - `common/` - Shared utilities and types
- `src/test/kotlin/` - Test files using Kotest framework
- `etc/detekt/` - Static analysis configuration
- `openspec/` - OpenSpec specifications and change proposals
- Key files: `build.gradle.kts`, `.editorconfig`, `detekt.yml`

## Build, Lint, and Test Commands

Use these commands to build, lint, and test the project. Run them via the Bash tool with ./gradlew.

### Build Commands

- Full build (clean, generate proto, build without tests): `./gradlew clean stubs build -xtest`
- Build JARs (agent and proxy): `./gradlew agentJar proxyJar`
- Generate protobuf stubs: `./gradlew generateProto`
- Create distribution: `./gradlew distro`

### Lint Commands

- Run Kotlinter on main sources: `./gradlew lintKotlinMain`
- Run Kotlinter on test sources: `./gradlew lintKotlinTest`
- Run Detekt (static analysis): `./gradlew detekt`
- Format code with Kotlinter: `./gradlew formatKotlin`

Before completing tasks, always run lint and typecheck if applicable. Kotlin is compiled, so build will catch type
errors.

### Test Commands

- Run all tests: `./gradlew test` or `./gradlew check` (includes lint)
- Run tests with coverage: `./gradlew koverMergedHtmlReport`
- Run a single test class: `./gradlew test --tests "io.prometheus.SomeTestClass"`
- Run a single test method: `./gradlew test --tests "io.prometheus.SomeTestClass.someTestMethod"`
- Rerun tests: `./gradlew --rerun-tasks check`

Tests use Kotest and MockK. Verify changes with tests; search codebase for existing test patterns before writing new
ones.

## Code Style Guidelines

Follow these conventions to maintain consistency. Derived from .editorconfig, detekt.yml, and codebase analysis.

### General Formatting

- Indent size: 2 spaces for .kt files (from .editorconfig)
- Max line length: 120 characters
- End of line: LF
- Trim trailing whitespace: true
- Insert final newline: true
- No tabs (use spaces)
- For Makefiles: tab indentation, size 4

### Imports

- Avoid wildcard imports where possible (ktlint_standard_no-wildcard-imports disabled, but prefer explicit)
- Order imports alphabetically
- No unused imports (UnusedImports rule active)

### Naming Conventions

- Classes/Interfaces: PascalCase, e.g., ProxyHttpService
- Functions/Methods: camelCase, starting with lowercase, e.g., registerAgent
- Variables/Properties: camelCase, starting with lowercase, e.g., scrapeRequestManager
- Constants: UPPER_SNAKE_CASE, e.g., MAX_SCRAPE_REQUESTS
- Enums: UPPER_SNAKE_CASE for entries
- Packages: lowercase with dots, e.g., io.prometheus.proxy
- Private properties: may start with underscore, but prefer without unless necessary
- Boolean properties: Prefix with is/has/are (BooleanPropertyNaming disabled, but still recommended)

### Types and Declarations

- Use explicit types where clarity is needed; avoid redundant explicit types (RedundantExplicitType disabled)
- Prefer val over var (VarCouldBeVal disabled, but immutable by default)
- Use Kotlin's null safety: Avoid unnecessary safe calls or not-null assertions
- Data classes: Use for simple data holders; avoid functions in them unless conversion (DataClassContainsFunctions
  disabled)
- Opt-in annotations: Use for experimental features like coroutines (configured in build.gradle.kts)

### Error Handling

- Use specific exceptions; avoid too generic ones (TooGenericExceptionThrown active)
- Don't swallow exceptions without reason (SwallowedException active)
- Provide messages or causes for thrown exceptions (ThrowingExceptionsWithoutMessageOrCause active)
- Handle empty catch blocks appropriately (EmptyCatchBlock active, allow specific names like 'ignored')
- Prefer require/requireNotNull over manual checks (UseRequire, UseRequireNotNull active)

### Functions and Control Flow

- Function length: Max 140 lines (LongMethod threshold)
- Parameter list: Max 12 for functions/constructors (LongParameterList)
- Complexity: Cyclomatic complexity <=25 (CyclomaticComplexMethod)
- Avoid magic numbers; use constants (MagicNumber disabled, but recommended)
- Use expression body syntax where appropriate (ExpressionBodySyntax disabled)
- Avoid unnecessary let/apply/run (UnnecessaryLet disabled, UnnecessaryApply active)
- Prefer forEach on ranges over indexed loops (ForEachOnRange active)

### Coroutines

- Use suspend modifiers correctly (RedundantSuspendModifier active)
- Avoid GlobalCoroutineUsage (disabled, but be cautious)
- Prefer delay over sleep (SleepInsteadOfDelay active)

### Collections and Performance

- Use immutable collections where possible
- Avoid spread operators in non-test code (SpreadOperator disabled)
- Use array literals in annotations (UseArrayLiteralsInAnnotations active)
- Prefer sequence for large data processing (CouldBeSequence disabled)

### Comments and Documentation

- Use KDoc for public APIs (UndocumentedPublicClass disabled, but recommended)
- Avoid forbidden comments like TODO/FIXME unless temporary (ForbiddenComment disabled)
- Ensure end-of-sentence format in comments (EndOfSentenceFormat disabled)

### Other

- No empty blocks (various Empty* rules active)
- Use raw strings for multi-line (StringShouldBeRawString disabled, recommended)
- Avoid void type (ForbiddenVoid active)
- Follow detekt rules as per detekt.yml (many active for style, potential-bugs, etc.)

## Testing Guidelines

- Use Kotest with descriptive test names and backtick syntax
- Use MockK for mocking dependencies
- Follow existing patterns: descriptive test names, proper setup/teardown
- Test both success and failure scenarios
- Use @Test annotation from JUnit Jupiter (configured via Kotest)
- Place test files alongside main files with Test suffix

## Additional Guidelines for Agents

- Always mimic existing code style: Check imports, patterns in similar files.
- Never add comments unless requested.
- Before editing, read relevant files and understand context.
- After changes, run lint (./gradlew detekt && ./gradlew lintKotlinMain) and tests (./gradlew test).
- Do not commit unless explicitly asked.
- For new features, consider OpenSpec if major changes.
- Use OpenSpec workflow for planning changes: check openspec/AGENTS.md

This file is ~150 lines. Update as needed based on project evolution.
