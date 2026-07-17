# Java guide

The library is written in Kotlin and designed to be used from Java. Most pages on this
site carry a Java tab. This page explains the handful of places where Java and Kotlin
genuinely diverge, so those tabs make sense.

!!! tip "Pick the Java tab once"

    The language tabs are linked across the whole site. Choose "Java" on any page and
    every other page follows.

## Extensions become static facades

Most of the etcd surface is Kotlin *extension functions*, which have no Java equivalent.
Each extension file is therefore annotated `@file:JvmName(...)`, which compiles it to a
class of static methods. A `fun Client.putValue(...)` becomes
`KVUtils.putValue(client, ...)` — the receiver is just the first parameter.

```java
--8<-- "java/website/interop/FacadeSnippets.java:facades"
```

The full map:

| Source file | Java class |
| --- | --- |
| `ClientExtensions.kt` | `ClientUtils` |
| `KVExtensions.kt` | `KVUtils` |
| `TypedKVExtensions.kt` | `TypedKVUtils` |
| `ChildrenExtensions.kt` | `ChildrenUtils` |
| `WatchExtensions.kt` | `WatchUtils` |
| `TxnExtensions.kt` | `TxnUtils` |
| `LeaseExtensions.kt` | `LeaseUtils` |
| `KeepAliveExtensions.kt` | `KeepAliveUtils` |
| `LockExtensions.kt` | `LockUtils` |
| `BuilderExtensions.kt` | `BuilderUtils` |
| `ByteSequenceExtensions.kt` | `ByteSequenceUtils` |
| `KeyValueExtensions.kt` | `KeyValueUtils` |
| `PairExtensions.kt` | `PairUtils` |
| `PathExtensions.kt` | `PathUtils` |
| `TypedServiceInstance.kt` | `TypedServiceInstances` |

Static-import them and the code reads well:

```java
import static io.etcd.recipes.common.ClientUtils.connectToEtcd;
import static io.etcd.recipes.common.KVUtils.putValue;
```

Conversion helpers work the same way, with one wrinkle:

```java
--8<-- "java/website/interop/FacadeSnippets.java:bytes"
```

!!! warning "Extension *properties* get a `get` prefix"

    `asString`, `asInt`, `asLong` and `asByteSequence` are extension **properties**, not
    functions. Java sees their getter names:

    | Kotlin | Java |
    | --- | --- |
    | `bytes.asString` | `ByteSequenceUtils.getAsString(bytes)` |
    | `bytes.asInt` | `ByteSequenceUtils.getAsInt(bytes)` |
    | `bytes.asLong` | `ByteSequenceUtils.getAsLong(bytes)` |
    | `"x".asByteSequence` | `ByteSequenceUtils.getAsByteSequence("x")` |

    Extension *functions* like `putValue` keep their name. If a static import will not
    resolve, this is the first thing to check.

!!! warning "The recipe *classes* are not extensions"

    `DistributedMutex`, `LeaderLatch`, `PathChildrenCache` and friends are ordinary
    classes. Construct them with `new`. Only the `common` extension layer goes through a
    facade.

## Default arguments become an overload ladder

Kotlin default arguments do not exist in Java, so every public constructor and method with
defaults is annotated `@JvmOverloads`. That generates one overload per trailing default:

```java
--8<-- "java/website/interop/FacadeSnippets.java:overloads"
```

!!! warning "No named arguments in Java"

    Kotlin can skip to a late parameter by name:

    ```kotlin
    DistributedMutex(client, "/locks/orders", interruptOnLockLoss = true)
    ```

    Java cannot. To reach `interruptOnLockLoss` you must supply every preceding
    parameter positionally, defaults included:

    ```java
    new DistributedMutex(client, "/locks/orders", 5L, ResilienceConfig.DEFAULT, "worker-1", true);
    ```

    When a Java example here looks more verbose than its Kotlin twin, this is usually why.
    Comment the positional arguments — future readers will not remember the order.

## `Duration`: use the `TimeUnit` overloads

Every timeout takes both a `kotlin.time.Duration` and a `(long, TimeUnit)` overload. Use
the latter:

```java
mutex.tryLock(5, TimeUnit.SECONDS);
semaphore.tryAcquire(2, TimeUnit.SECONDS);
latch.await(30, TimeUnit.SECONDS);
```

!!! note "Except `EtcdConnectionConfig`"

    That one class uses `java.time.Duration` — it is what the underlying jetcd builder
    wants. So `Duration.ofSeconds(5)` there, `(5, TimeUnit.SECONDS)` everywhere else. See
    [Connecting](getting-started/connecting.md).

## Properties become getters

A Kotlin `val lockPath: String` is `getLockPath()`; a `val isLeader: Boolean` is
`isLeader()`. The `readLock`/`writeLock` views of a read/write lock are
`getReadLock()`/`getWriteLock()`.

## Listeners are SAM interfaces

Every callback type is a `fun interface`, so Java lambdas work directly:

```java
mutex.addLockLostListener(cause -> log.warn("lost the lock: {}", cause));

cache.addListener(event -> {
  System.out.println(event.getType() + " " + event.getChildName());
});
```

`LeaderSelectorListener` has two methods, so it needs an anonymous class — or extend
`LeaderSelectorListenerAdapter` and override only what you care about.

## What is Kotlin-only

| Feature | Java story |
| --- | --- |
| [Coroutines and `Flow`](coroutines/index.md) | No equivalent. Use the blocking API and listeners — every suspending function is a twin of one. |
| `withXxx { }` scoped functions | Use `try (…)`. Same lifecycle, no magic. |
| `withLock { }` / `withPermit { }` | Callable as `EtcdLockKt.withLock(lock, Function0)`, but a Java lambda must `return Unit.INSTANCE`. Not worth it — use `try/finally`. |
| Reified helpers (`jsonCodec<T>()`) | Use [`JacksonCodec`](integrations/jackson.md) — that module exists for exactly this. |

```java
--8<-- "java/website/interop/FacadeSnippets.java:with-lock"
```

## Typed values from Java

Core's `jsonCodec<T>()` is a reified Kotlin helper, and `KotlinxJsonCodec` needs a
kotlinx-serialization `KSerializer` — neither is pleasant from Java. The
[Jackson module](integrations/jackson.md) is the Java answer: `EtcdCodec<T>` backed by an
`ObjectMapper`.

```java
JacksonCodec<Order> codec = new JacksonCodec<>(Order.class);
```

`StringCodec.INSTANCE` and `ByteSequenceCodec.INSTANCE` are Kotlin `object`s, reached
through `INSTANCE`.

## The `EtcdRecipes` facade

If you would rather not construct each recipe by hand, `EtcdRecipes` is an ordinary class
and reads naturally from Java:

```java
--8<-- "java/website/interop/FacadeSnippets.java:recipes-facade"
```

See [Integrations](integrations/index.md) for what it covers — and what it does not.

## Spring Boot

The [Spring Boot starter](integrations/spring-boot.md) is the most Java-friendly entry
point: put it on the classpath and inject `Client` or `EtcdRecipes`.

## Runnable Java examples

The repo ships Java programs for most recipes under
[`etcd-recipes-examples/src/main/java`](https://github.com/pambrose/etcd-recipes/tree/master/etcd-recipes-examples/src/main/java/io/etcd/recipes/examples).
Unlike the snippets on this site, those have `main()` methods and expect an etcd at
`localhost:2379`.
