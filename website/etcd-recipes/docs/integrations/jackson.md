# Jackson

`etcd-recipes-jackson` provides one class: `JacksonCodec<T>`, an implementation of core's
[`EtcdCodec<T>`](../typed-values.md) backed by a Jackson `ObjectMapper`.

```kotlin
implementation("com.pambrose:etcd-recipes-jackson:0.12.0")
```

## Why it is a separate artifact

Core already has a JSON codec — `KotlinxJsonCodec`, reached through `jsonCodec<T>()`. But
`jsonCodec` is an inline reified function backed by `kotlinx-serialization`, which wants a
compiler plugin and an `@Serializable` annotation on your type. From Java that is a
non-starter, and plenty of Kotlin services already have an `ObjectMapper` configured to
their taste.

So `JacksonCodec` is the Java-facing counterpart to `KotlinxJsonCodec`, split into its own
module so that projects which do not want Jackson never see it on their classpath. Nothing
in core knows the difference: a `JacksonCodec` is an `EtcdCodec`, and it drops into every
typed recipe unchanged.

## Constructing a codec

Two `@JvmOverloads` constructors, each taking an optional `ObjectMapper`:

```kotlin
class JacksonCodec<T> : EtcdCodec<T> {
  constructor(type: Class<T>, mapper: ObjectMapper = ObjectMapper())
  constructor(type: TypeReference<T>, mapper: ObjectMapper = ObjectMapper())
}
```

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/jackson/JacksonSnippets.kt:codec"
    ```

=== "Java"

    ```java
    --8<-- "java/website/jackson/JacksonSnippets.java:codec"
    ```

`jacksonCodec<T>(mapper)` is a reified Kotlin helper that builds the `TypeReference` for
you. It is the closest analogue to core's `jsonCodec<T>()`, and the form to reach for from
Kotlin.

### Generic payloads need a `TypeReference`

A `Class` token cannot express `List<Order>` — the type argument is erased, and Jackson
would have nothing to reconstruct the elements from. That is what the second constructor
is for:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/jackson/JacksonSnippets.kt:generic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/jackson/JacksonSnippets.java:generic"
    ```

The `Class` constructor is the one to use for a plain type; reach for `TypeReference` the
moment a generic appears in the payload.

## Kotlin data classes need a configured mapper

!!! warning "The default `ObjectMapper` is vanilla"

    `JacksonCodec(Order::class.java)` gives you `ObjectMapper()` — a bare instance with no
    modules registered. That is exactly right for Java beans, records, and anything Jackson
    can construct out of the box, and it is why the default exists.

    It is **wrong for Kotlin data classes**. A data class has no no-arg constructor, so a
    vanilla mapper cannot instantiate it and `decode` fails at runtime with
    `InvalidDefinitionException` — at decode time, not construction time, so a codec that
    looks fine can fail on the first read. Kotlin nullability is not enforced either: a
    vanilla mapper will happily leave `null` in a non-null field.

    Register `jackson-module-kotlin` and pass the mapper in:

    ```kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    ```

    ```kotlin
    --8<-- "kotlin/website/jackson/JacksonSnippets.kt:kotlin-mapper"
    ```

`findAndRegisterModules()` discovers the Kotlin module via `ServiceLoader`, so it also
picks up `jackson-module-parameter-names`, JSR-310, and anything else on the classpath —
usually what you want. `ObjectMapper().registerKotlinModule()` is the explicit alternative
if you would rather name it.

The other way out is to make the type constructable by a vanilla mapper, which is what the
sample type on this page does — the module's own tests use exactly this trick to stay
honest about the default:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/jackson/JacksonSnippets.kt:pojo"
    ```

=== "Java"

    ```java
    --8<-- "java/website/jackson/JacksonSnippets.java:pojo"
    ```

A Java record works with a vanilla mapper with no annotations at all, as does any bean
with a no-arg constructor and setters. If you are on Java, this is a non-issue.

## Using it

Anywhere an `EtcdCodec` goes. With a [typed recipe](../typed-values.md#typed-recipes):

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/jackson/JacksonSnippets.kt:typed-queue"
    ```

=== "Java"

    ```java
    --8<-- "java/website/jackson/JacksonSnippets.java:typed-queue"
    ```

With a typed cache:

```kotlin
--8<-- "kotlin/website/jackson/JacksonSnippets.kt:typed-cache"
```

Or with typed key/value reads and writes:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/jackson/JacksonSnippets.kt:typed-kv"
    ```

=== "Java"

    ```java
    --8<-- "java/website/jackson/JacksonSnippets.java:typed-kv"
    ```

!!! note "The typed KV helpers are clumsy from Java"

    `putValue` / `getValue` are Kotlin extension functions with default arguments and no
    `@JvmOverloads`, so Java sees statics on `TypedKVUtils` with the client as the first
    argument and **no** short overload — you must pass `PutOption.DEFAULT` and
    `RpcResilience.DEFAULT` explicitly. The typed recipes have no such wart; their
    constructors are `@JvmOverloads`. See the [Java guide](../java.md).

## Service payloads

A `ServiceInstance` carries its payload in an opaque `jsonPayload` **string**, so a service
payload codec must emit UTF-8 text. `JacksonCodec` emits JSON, which qualifies:

```kotlin
--8<-- "kotlin/website/jackson/JacksonSnippets.kt:typed-service"
```

See [Service discovery](../recipes/discovery.md) for the registration side, and
[Typed values](../typed-values.md#typed-service-payloads) for why the text restriction
exists.
