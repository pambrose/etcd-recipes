# etcd-recipes documentation site

The source for <https://pambrose.github.io/etcd-recipes/>, built with
[Zensical](https://zensical.org). Published from `master` by
[`.github/workflows/docs.yml`](../.github/workflows/docs.yml).

## Layout

```
website/
  pyproject.toml        zensical dev dependency
  uv.lock               pinned; CI installs from this
  etcd-recipes/
    zensical.toml       site config
    docs/               the Markdown pages
```

## Building

From the repo root:

```bash
make site          # serve locally (wipes the previous build first)
make docs-check    # compile the snippets, then build in strict mode — what CI runs
make clean-site    # remove the generated site/ and .cache/
make check-site    # report outdated website dependencies (dry run)
make upgrade-site  # upgrade website dependencies, rewriting uv.lock
```

`make site` serves; `make docs-check` is the one that *builds*, into
`website/etcd-recipes/site`.

Or directly:

```bash
cd website/etcd-recipes
uv run zensical serve
```

> [!IMPORTANT]
> `zensical` must run with `website/etcd-recipes` as the working directory.
> `pymdownx.snippets` resolves `base_path` against the process CWD, not against
> the location of `zensical.toml`, so running
> `zensical build -f website/etcd-recipes/zensical.toml` from the repo root fails
> to find any snippet. Every `make` target above cds first.

## How the code examples work

No code example on the site is written into a Markdown file. Every one is a real
source file in a Gradle **test source set**, pulled in at build time by
`pymdownx.snippets`:

```
etcd-recipes-core/src/test/kotlin/website/     Kotlin examples
etcd-recipes-core/src/test/java/website/       Java examples
etcd-recipes-<satellite>/src/test/{kotlin,java}/website/<module>/
```

A page embeds a named section from one of those files:

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

and the source file marks that section with comments:

```kotlin
fun basicMutex(client: Client) {
  // --8<-- [start:basic]
  DistributedMutex(client, "/locks/orders").use { mutex ->
    mutex.withLock { /* ... */ }
  }
  // --8<-- [end:basic]
}
```

The point is that `./gradlew compileTestKotlin compileTestJava` type-checks every
example against the real API on every CI run, so an example cannot silently rot
when the library changes. `check_paths = true` makes a dangling snippet reference
fail the docs build rather than render an empty block.

### Rules for snippet files

- They are **not tests**. No Kotest specs, no assertions, no `main()` — just plain
  functions that are never invoked. They exist to be compiled.
- Section names must match `[a-z][-_0-9a-z]*`: lowercase, digits, `-`, `_`.
  `try-lock` works; `tryLock` will not match and the section silently won't render.
- They live in the module whose API they document. `etcd-recipes-core`'s test source
  set cannot see the satellite modules, so Spring/Ktor/Jackson/Micrometer examples
  live in their own module's `src/test`.
- kotlinter and detekt lint these files like any other source. Keep them small.

To add an example: write the function, wrap the interesting lines in
`// --8<-- [start:name]` / `[end:name]`, reference it from a page, and run
`make docs-check`.
