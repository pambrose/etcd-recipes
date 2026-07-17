# Transactions

An etcd transaction is not a database transaction. There is no `BEGIN`, no open session, no
locks held across round trips, and nothing to roll back. It is a single atomic **if/then/else**,
sent as one message and evaluated by the server:

```
If    (a list of comparisons, ANDed together)
Then  (ops to run if every comparison held)
Else  (ops to run if any comparison failed)
```

One round trip. Either the `Then` ops all apply or the `Else` ops all apply, at a single
revision, with no other write interleaved. That is the entire model — and it is enough to build
every lock, counter, and election in this library.

`TxnUtils` supplies the `transaction { }` builder plus the comparison and operation helpers.

## The basic shape

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/TxnSnippets.kt:basic"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/TxnSnippets.java:basic"
    ```

`isSucceeded` tells you **which branch ran** — whether the comparisons held — not whether the
call worked. A transaction whose comparisons fail is a perfectly successful RPC that returns
`isSucceeded = false` and runs your `Else`. A transaction that fails as an RPC throws.

Both `Then` and `Else` are optional. A transaction with only an `If` is a pure test, which is
exactly how `isKeyPresent` is implemented.

## Comparisons

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/TxnSnippets.kt:compare"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/TxnSnippets.java:compare"
    ```

!!! warning "The first argument is the key, not a value"

    `equalTo("/config/name", CmpTarget.value(...))` reads as though it compares the string
    `/config/name`. It does not. The first argument names the **key being examined**; the
    `CmpTarget` says which of that key's fields to read and what to compare it against. The
    `String`, `Int`, and `Long` overloads exist only so you can name a key without spelling out
    `asByteSequence` — they never mean "compare against this value".

Three comparison operators, four targets:

| Helper | Operator |
| --- | --- |
| `equalTo(key, target)` | `=` |
| `lessThan(key, target)` | `<` |
| `greaterThan(key, target)` | `>` |

| `CmpTarget` | Reads | Useful for |
| --- | --- | --- |
| `version(n)` | Number of writes since creation; **0 means the key does not exist** | Existence |
| `createRevision(n)` | Revision the key was created at | Ordering, "am I first?" |
| `modRevision(n)` | Revision of the last write | **Compare-and-swap** |
| `value(bytes)` | The value itself | Guarding on content |

Several comparisons in a single `If()` are **ANDed** — every one must hold for `Then` to run.
There is no OR; express alternatives as separate transactions, or restructure so the `Else`
branch carries the other case.

## Create-if-absent

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/TxnSnippets.kt:exists"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/TxnSnippets.java:exists"
    ```

`String.doesExist` and `String.doesNotExist` are `Cmp`-valued extension **properties**, sugar
over `version(0)`:

```kotlin
val String.doesNotExist: Cmp get() = equalTo(this, CmpTarget.version(0))
val String.doesExist: Cmp get() = greaterThan(this, CmpTarget.version(0))
```

Version 0 means "never created, or created and since deleted" — etcd resets the version on
delete, so a recreated key starts over at 1.

This tiny transaction is the atomic primitive that the presence checks on the
[key/value page](kv.md#testing-for-existence) explicitly cannot give you. `isKeyNotPresent`
followed by `putValue` is two round trips with a window between them, and two clients can both
win. The transaction has no window: exactly one of them gets `isSucceeded = true`, and it is
the server that decides. Leader election, lock acquisition, and lazy initialisation are all
this shape.

## Deleting inside a transaction

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/TxnSnippets.kt:delete-op"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/TxnSnippets.java:delete-op"
    ```

`Then` and `Else` take `Op`s, not just puts:

| Helper | Builds |
| --- | --- |
| `key setTo value` | `Op.PutOp` — infix, for `String`, `Int`, `Long`, `ByteSequence` |
| `key.setTo(value, putOption)` | `Op.PutOp` with options — a lease id, say |
| `deleteOp(key)` | `Op.DeleteOp` |
| `deleteOp(key, deleteOption)` | `Op.DeleteOp` over a range, with `prevKV` |

Ops in a branch land **together, atomically**, which is the answer to the caveat on
[`deleteKeys`](kv.md#deleting): several `deleteOp`s in one `Then` cannot half-apply.

`setTo(value, putOption)` is how a recipe writes a key that is bound to a lease *and* guarded
by a comparison in the same atomic step — claim the key only if nobody holds it, and tie it to
your lease so it cannot outlive you. See [Leases](lease.md).

## Compare-and-swap

This is what the whole page has been building to. `modRevision` records the revision of the
last write to a key, so comparing against it answers exactly one question: *has anybody touched
this key since I read it?*

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/basics/TxnSnippets.kt:cas"
    ```

=== "Java"

    ```java
    --8<-- "java/website/basics/TxnSnippets.java:cas"
    ```

Read the value with its `modRevision`, compute the new value locally, then commit only if the
`modRevision` is unchanged. If another client wrote in between, the comparison fails, nothing
is applied, and you re-read and retry against the new value. No lock, no lease, no coordinator
— and no lost update.

`DistributedAtomicLong` is this loop with backoff around it. Read
[Counters](../recipes/counter.md) before writing your own.

!!! danger "Transactions are never retried for you"

    Every other extension in `common/` retries on retriable statuses. `transaction { }` does
    not — it gets the operation timeout and nothing else, deliberately.

    A commit that fails in an ambiguous way **may already have applied**. Re-sending it could
    double-apply a non-idempotent change; a CAS that silently retries could increment twice.
    Only the caller knows whether their transaction is safe to re-send, so the retry decision
    stays with the caller. That is why the loop above is written out rather than hidden: the
    retry is a correctness decision, not transport plumbing.

!!! tip "CAS or lock?"

    A CAS loop beats a [lock](../recipes/locks.md) whenever the work between read and write is
    fast and local. It needs no lease, cannot be lost by expiry, and costs one round trip when
    uncontended. Reach for a lock when the critical section is slow, touches many keys, or
    involves something outside etcd — under heavy contention a CAS loop can livelock while a
    lock queues fairly.
