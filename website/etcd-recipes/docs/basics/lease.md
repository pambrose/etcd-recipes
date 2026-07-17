# Leases

A lease is a countdown timer that lives **on the etcd server**. It is granted with a TTL, it
expires unless something renews it, and any key bound to it is deleted by etcd the moment it
does.

That last clause is the whole point, and it is worth being precise about why it matters: the
deletion is performed by the cluster, not by your process. Your process does not have to notice
that it has died. It does not have to be reachable, or healthy, or willing. A machine that
loses power, a JVM stopped at a breakpoint, a container the scheduler killed without warning —
all of them release their leased keys on schedule, because nothing they do or fail to do is
involved.

This is the mechanism under every ephemeral recipe in the library. A service registration that
disappears when the service dies, a lock that cannot be held forever by a crashed holder, a
barrier participant that stops counting when its process goes away — all of them are a lease
and a key bound to it.

## Granting and binding

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/LeaseSnippets.kt:grant"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/LeaseSnippets.java:grant"
    ```

`leaseGrant` returns jetcd's `LeaseGrantResponse`; its `id` is what binds a key to it, via
`putOption { withLeaseId(lease.id) }`. Many keys can share one lease, which is how a
multi-key registration is made to appear and vanish as a unit.

!!! warning "Java cannot call `leaseGrant`"

    `leaseGrant` takes a `kotlin.time.Duration`, which is an **inline value class**. Kotlin
    mangles the JVM name of any function with a value-class parameter — `leaseGrant` compiles
    to `leaseGrant-HG0u8IE`, and `-` is not a legal character in a Java identifier. There is no
    way to call it from Java, and no `@JvmName` rescues it.

    Java callers grant through jetcd directly — `client.getLeaseClient().grant(5).get()` — and
    then use the rest of the layer normally, as the Java tab above shows. The `LeaseUtils`
    functions that take no `Duration` (`keepAlive`, `keepAliveWith`, `leaseRevoke`) are all
    perfectly callable, as are the `ttlSecs: Long` overloads in `KeepAliveUtils`. See the
    [Java guide](../java.md).

!!! note "The TTL is a floor, not a promise"

    etcd may keep a lease slightly beyond its TTL — TTLs are enforced at cluster granularity,
    and etcd will not expire a lease while it cannot reach consensus. So a TTL of 5 seconds
    means "not before 5 seconds", never "at exactly 5 seconds". Never build a protocol that
    needs a key gone by a specific instant. Very short TTLs are also a bad trade: they multiply
    renewal traffic and make a brief GC pause look like death.

## Keeping a lease alive

A granted lease with nobody renewing it is a dead man's switch that has already been let go.
Renewal is what makes a lease mean "this process is still here".

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/LeaseSnippets.kt:keep-alive"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/LeaseSnippets.java:keep-alive"
    ```

`keepAliveWith` starts renewal, runs your block, and stops renewing when the block exits —
however it exits. The lease then expires on its TTL, and the keys bound to it go with it.

`onKeepAliveError` is the parameter to care about. jetcd's own observer leaves the renewal
stream's `onError` and `onCompleted` as no-ops, which produces the worst failure this library
can have: **renewal stops, your keys expire, and your process carries on looking completely
healthy**. So the layer logs both at error/warn *and* hands them to `onKeepAliveError`, and
`onCompleted` synthesizes a throwable so a stream that merely stops is reported like one that
broke. Neither callback fires on your own `close()` — if you hear from it, renewal genuinely
stopped.

Supply it. A lease whose renewal has died is not a problem you want to learn about from a
downstream service's error rate.

### When the lease must outlive the block

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/LeaseSnippets.kt:keep-alive-raw"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/LeaseSnippets.java:keep-alive-raw"
    ```

`keepAlive` returns a `CloseableClient` and hands you the lifetime. Renewal runs until you
close it. Prefer `keepAliveWith` unless the scopes genuinely do not nest.

### Revoking

`leaseRevoke` expires a lease immediately rather than waiting out its TTL — the polite way to
deregister, since it deletes your keys now instead of leaving a tombstone that other clients
believe in for another TTL.

It is **best-effort by design**: failures are logged and swallowed. Callers use it on cleanup
paths — a failed CAS, an exception on the way out — where raising a secondary failure would
mask the original problem. If the revoke RPC itself fails, the TTL is already the upper bound
on how long the lease can linger, so there is nothing a thrown exception would let you usefully
do.

## Put, renew, and revoke in one call

The grant/put/renew/revoke sequence is the same every time, so `KeepAliveUtils` collapses it:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/LeaseSnippets.kt:put-with-keep-alive"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/LeaseSnippets.java:put-with-keep-alive"
    ```

The key exists for exactly the duration of the block. `putValueWithKeepAlive` has eight
overloads — `String`, `Int`, `Long`, and `ByteSequence` values, each with a `ttlSecs: Long` or a
`kotlin.time.Duration`.

!!! note "Only the `ttlSecs: Long` overloads are callable from Java"

    The `Duration` ones are disambiguated with `@JvmName("putValueWithKeepAliveDur")` rather
    than mangled, so they *appear* on `KeepAliveUtils` — but their `Duration` parameter arrives
    as a raw `long` of internal representation bits, which is not something a Java caller can
    construct meaningfully. Use the `ttlSecs` overloads, which is what the Java tab does.

!!! tip "A stranded lease is a leak"

    Granting a lease and then throwing before renewal starts leaves it alive on the server for
    its whole TTL, holding your keys up. `putValuesWithKeepAlive` revokes the lease if any put
    throws on the way in, which is the bug this shape exists to prevent. Hand-rolled
    grant-then-put sequences should do the same.

### Several keys, one lease

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/LeaseSnippets.kt:put-values-with-keep-alive"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/LeaseSnippets.java:put-values-with-keep-alive"
    ```

`putValuesWithKeepAlive` binds every key in the collection to a **single** lease, so they
expire together. A reader can never catch half a registration published and half of it expired
— which is why `ServiceDiscovery` registers this way rather than with a lease per key.

The puts themselves are separate RPCs, so the keys do not *appear* atomically. If readers must
never see a partial registration on the way in, publish a single key with a
[typed value](../typed-values.md) instead, or gate visibility behind one final key.

## What happens when a lease expires

Nothing, from your side. That is the design and also the difficulty: etcd deletes the keys and
your process is not told. The recipes that hold leases layer detection on top —
`onKeepAliveError` here, `LockLostListener` and `connectionState` on the recipes — because the
lease mechanism itself offers no notification.

Which leads to the question this page deliberately does not answer: **should a lease that dies
be healed and re-established, or is it gone for good?** The answer is not the same for every
recipe, and getting it wrong in either direction is a correctness bug. Reviving a lock's lease
would race the holder etcd has already promoted; refusing to revive a service registration's
lease would silently deregister a healthy service forever.

That split — which leases self-heal, which are never healed, and why — is owned by
[Leases and loss](../resilience/leases.md). Read it before building anything on top of these
primitives.
