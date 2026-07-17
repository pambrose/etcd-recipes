# Contributing

Source: [github.com/pambrose/etcd-recipes](https://github.com/pambrose/etcd-recipes).
Licensed [Apache 2.0](https://github.com/pambrose/etcd-recipes/blob/master/License.txt).

## Building

JDK 17 and the Gradle wrapper. `make help` lists every target.

```bash
make build      # ./gradlew clean build -x test
make tests      # full suite against a local etcd at localhost:2379
make tests-tc   # full suite against an ephemeral Testcontainers etcd (needs Docker)
make lint       # ./gradlew lintKotlin detekt
make coverage   # Kover HTML + XML + summary
make kdocs      # Dokka API docs
```

`make tests` expects an etcd at `http://localhost:2379`:

```bash
./etcd.sh        # start
./etcd-stop.sh   # graceful shutdown
```

A single test class:

```bash
./gradlew :etcd-recipes-core:test --tests "io.etcd.recipes.barrier.DistributedBarrierTests"
```

## Modules

| Module | Published as |
| --- | --- |
| `etcd-recipes-core` | `com.pambrose:etcd-recipes-core` |
| `etcd-recipes-jackson` | `com.pambrose:etcd-recipes-jackson` |
| `etcd-recipes-micrometer` | `com.pambrose:etcd-recipes-micrometer` |
| `etcd-recipes-spring-boot-starter` | `com.pambrose:etcd-recipes-spring-boot-starter` |
| `etcd-recipes-ktor` | `com.pambrose:etcd-recipes-ktor` |
| `etcd-recipes-examples` | — runnable examples |
| `etcd-recipes-test-runners` | — test-only shadow JAR |

## Tests

**Thread-based** (`*Tests.kt` beside each recipe) — N threads in one JVM simulate
distributed clients via `blockingThreads(...)` / `nonblockingThreads(...)` from
`TestExtensions.kt`.

**Container-based** (`container/Container*Test.kt`) — each participant runs in its own
container against a shared etcd container. Gated by `assumeTrue(testcontainers=true)`, so a
default `./gradlew check` skips them.

Test JVMs fork per class (`forkEvery = 1`) so one spec's background threads and watch
connections cannot bleed into the next. `maxParallelForks` lets classes run concurrently
against one etcd, so **each test namespaces its keys by class name**. Keep doing that.

New Kotlin tests use [Kotest](https://kotest.io) with `StringSpec()` and an `init {}`
block, plus MockK where it helps.

## Adding a recipe

1. Compose the existing extensions in `common/` rather than reaching into jetcd directly.
2. Extend `EtcdConnector` for anything stateful — it gives you the lifecycle, the
   exception list, connection state and `close()`.
3. Raise `EtcdRecipeException` / `EtcdRecipeRuntimeException`, not jetcd types.
4. Add Kotest tests under `etcd-recipes-core/src/test/kotlin/`.
5. Add a runnable example under `etcd-recipes-examples/`.
6. Document it here, with a compiled snippet (below).

## Documentation

This site is [Zensical](https://zensical.org), under `website/`.

```bash
make site          # serve locally (wipes the previous build first)
make docs-check    # compile the snippets, then build in strict mode — what CI runs
make clean-site    # remove the generated site/ and .cache/
make check-site    # report outdated website dependencies (dry run)
make upgrade-site  # upgrade website dependencies, rewriting uv.lock
```

`make site` serves the site; `make docs-check` is the one that builds it, and it is what
you want before pushing.

### Code examples are compiled, not pasted

No example on this site is typed into a Markdown file. Every one is a real source file in a
Gradle **test source set**, embedded at build time by `pymdownx.snippets`. `./gradlew
compileTestKotlin compileTestJava` type-checks all of them against the real API on every CI
run, so an example cannot silently rot when the library changes.

They live in the module whose API they document:

```
etcd-recipes-core/src/test/kotlin/website/      Kotlin
etcd-recipes-core/src/test/java/website/        Java
etcd-recipes-<satellite>/src/test/…/website/<module>/
```

!!! note "Why the satellites need their own"

    `etcd-recipes-core`'s test source set cannot see the satellite modules — they depend on
    core, not the reverse. So Spring, Ktor, Jackson and Micrometer examples live in their
    own module's `src/test`, and `zensical.toml` lists each one in `base_path`.

### Adding an example

Write a plain function that is never called, and mark the interesting part:

```kotlin
fun basicMutex(client: Client) {
  // --8<-- [start:basic]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    mutex.withLock { /* ... */ }
  }
  // --8<-- [end:basic]
}
```

Then embed it:

````markdown
=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/locks/MutexSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/locks/MutexSnippets.java:basic"
    ```
````

Run `make docs-check` and you are done.

### Rules

- **They are not tests.** No Kotest specs, no assertions, no `main()`. Plain functions,
  never invoked. They exist to be compiled.
- **Section names must match `[a-z][-_0-9a-z]*`** — lowercase, digits, `-`, `_`. `try-lock`
  works; `tryLock` will not match, and the section silently will not render.
- **kotlinter and detekt lint them** like any other source. Keep them small.
- **Prefer showing both languages.** Coroutines are the honest exception.
- A dangling snippet reference **fails** the build (`check_paths = true`) rather than
  rendering an empty block. That is deliberate — please keep it that way.

!!! warning "zensical must run from `website/etcd-recipes`"

    `pymdownx.snippets` resolves `base_path` against the process working directory, not
    against `zensical.toml`'s location, so `zensical build -f website/etcd-recipes/zensical.toml`
    from the repo root finds no snippets. Every `make` target cds first.

## Versioning

The library version lives in `gradle.properties`; Kotlin and dependency versions in
`gradle/libs.versions.toml`. Bump both when releasing, and update the version references in
`README.md` and `website/etcd-recipes/docs/getting-started/index.md`.

## CI

`.github/workflows/ci.yml` runs `detekt`, then `check koverXmlReport -PuseTestcontainers`.

!!! note "`check` and `koverXmlReport` must stay in one Gradle invocation"

    Splitting them changes the `-PuseTestcontainers` input, which invalidates the test
    cache and makes the rerun hang against `localhost:2379`. The comments in `ci.yml`
    record this and the codecov report-path quirk — read them before editing.

`.github/workflows/docs.yml` builds the site on every PR and deploys from `master`.
