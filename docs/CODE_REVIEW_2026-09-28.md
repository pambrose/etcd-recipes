# etcd-recipes Code Review — 2026-09-28

| | |
|---|---|
| **Revision reviewed** | `master` @ `190fa42`, plus the uncommitted working tree (the `DistributedBarrierWithCount` close-race fix, `DesignFixesTests` edits, the new `DistributedBarrierWithCountCloseRaceTests`, and the Kotlin 2.4.20 bump) |
| **Scope** | All modules: `etcd-recipes-core` (main and test), the four satellites, `etcd-recipes-test-runners`, `etcd-recipes-examples`, the build, CI, scripts, and docs (README, CHANGELOG, `llms.txt`, `CLAUDE.md`, website) |
| **Method** | Seven parallel read-only passes, one per area: `common/`; election + barrier; lock + counter; queue + keyvalue; discovery + cache; coroutines; satellites + build + CI + docs + test infra. Each pass read every file in its scope and traced behavior into the jetcd 0.8.7, vertx-grpc, kotlinx.coroutines 1.11, Spring Boot 4.1, and Ktor 3.5 sources where it mattered. Duplicate findings across passes were merged. The lead reviewer then re-checked a sample of the Critical and High findings against the source. No code was changed, and no builds or tests were run during the review. |
| **Paths** | Unless a path starts with a module or repo-root name, it is relative to `etcd-recipes-core/src/main/kotlin/io/etcd/recipes/`. Line numbers refer to the revision reviewed. |
| **Verdicts** | *Confirmed*: the code path was traced end to end. *Plausible*: the code path is confirmed, but the bad outcome depends on etcd, jetcd, or network timing that static reading cannot prove. |

## Summary

**105 issues: 3 Critical · 19 High · 49 Medium · 34 Low.**

| Area | Critical | High | Medium | Low | Total |
|---|---:|---:|---:|---:|---:|
| `common/` infrastructure | – | 4 | 10 | 8 | 22 |
| Queue + keyvalue | 2 | 3 | 8 | 5 | 18 |
| Build, satellites, CI, docs, test infra | – | 1 | 5 | 8 | 14 |
| Lock + counter | 1 | 3 | 5 | 4 | 13 |
| Election | – | 4 | 5 | 2 | 11 |
| Discovery + cache | – | 2 | 6 | 2 | 10 |
| Coroutines | – | 2 | 5 | 3 | 10 |
| Barrier | – | – | 5 | 2 | 7 |

**What is in good shape.** The core safety mechanisms hold up:

- **Election:** the CAS is authoritative and leadership leases are never healed. No path to two simultaneous leaders was found.
- **Mutex:** ownership keys embed the lease id, so `unlock` can never delete another holder's key.
- **Plain queues:** the delete-CAS picks exactly one winner.
- **Caches:** the snapshot-then-anchored-watch bootstrap is correct in all three caches, and prefix handling uses `ensureSuffix("/")` everywhere except the two places below.
- **Discovery:** provider strategies handle empty lists and index overflow.
- **Docs site:** every snippet is compiled, and every internal link and anchor resolves.
- **CI:** it runs the full Testcontainers suite, including the container and fault tests.

**Where the problems cluster:**

1. **Prefix and key hygiene.** These are the only silent data-loss and safety bugs found:
   - Two range scans omit the trailing `/` (#2, #6).
   - The read-write lock classifies entries with `substringAfterLast('/')` (#7).
   - Queue keys carry only 3 random characters and are written with a blind put (#3).
2. **The resilience layer's model of jetcd doesn't match jetcd.**
   - The retry predicate matches an exception type that jetcd's KV path never produces (#4).
   - A timeout "cancel" does not cancel the gRPC call (#24).
   - A client-side keep-alive deadline is treated as proof that the server expired the lease (#9).
   - Lease callbacks are assumed to run off the Vert.x event loop, but they run on it (#10).
   - The unit tests mock jetcd using the library's own assumptions, so none of this surfaced (#33).
3. **Liveness and lifecycle around the safe cores.** Timed waits that don't time out, start/close hangs, `close()` racing in-flight operations, and attempts that fail once and are never retried (#11–#14, #17, #18, #44, #52).
4. **Cancellation at the coroutine bridge.** `withContext`'s prompt-cancellation guarantee discards results that must be undone: lock holds, permits, and dequeued items (#21, #22).
5. **Publishing.** The released 0.12.0 core POM scopes jetcd as `runtime` even though jetcd types are part of the public API (#5). Every artifact also forces logback onto consumers (#68).

> **In-flight work:** the uncommitted barrier close-race fix is correct as far as it goes, but it leaves a pre-park window of the same width open (#39). Finish it before committing (Step 1 of the plan).

### Checklist

#### Critical
- [x] [#1](#issue-1) `DistributedReadWriteLock` write→read downgrade deadlocks when another writer queues in between
- [x] [#2](#issue-2) Queue take steals and deletes keys from sibling paths that share the queue's string prefix
- [x] [#3](#issue-3) Colliding enqueue keys (millis + 3 random chars, blind put) silently overwrite queued items

#### High
- [ ] [#4](#issue-4) RPC retry predicate never matches real jetcd failures, so configured status-based retries are inert
- [ ] [#5](#issue-5) Published core POM scopes jetcd and kotlinx-serialization as runtime-only although both are in the public API
- [x] [#6](#issue-6) `DistributedReadWriteLock` conflict scan bleeds into sibling lock paths (missing trailing `/`)
- [x] [#7](#issue-7) `DistributedReadWriteLock`: a `clientId` containing `/` lets a reader and a writer hold at once
- [ ] [#8](#issue-8) Lease heal leaks the newly granted lease when the establish hook throws
- [ ] [#9](#issue-9) Self-healing CAS hooks can't reclaim their own key, and a client-side deadline is treated as server expiry
- [ ] [#10](#issue-10) Listener and `recordException` callbacks run on jetcd's Vert.x event loop, contrary to the documented contract
- [ ] [#11](#issue-11) `LeaderSelector.waitOnLeadershipComplete(timeout)` ignores its timeout
- [ ] [#12](#issue-12) `LeaderSelector.close()` from inside `takeLeadership` self-deadlocks
- [ ] [#13](#issue-13) `LeaderSelector.start()` can hang forever, uninterruptibly
- [ ] [#14](#issue-14) Watch-triggered election attempts swallow Phase-1 failures and never retry, so the election can be left leaderless
- [ ] [#15](#issue-15) `DistributedAtomicLong` is permanently broken if first-use initialization fails
- [ ] [#16](#issue-16) `DistributedWorkQueue.requeue()` bypasses `maxDeliveries`, so poison messages loop forever
- [ ] [#17](#issue-17) Work-queue `claimHead` lease-not-found retry is unbounded and ignores the deadline and `close()`
- [ ] [#18](#issue-18) A work-queue `receive()` in flight across `close()` creates a lease after close, and its item can never be acked
- [ ] [#19](#issue-19) `ServiceRegistry.close()` can block about 2 min, then throw part-way through and leave healers running
- [ ] [#20](#issue-20) `PathChildrenCache` priming start swallows load failures and leaves a dead cache that reports healthy
- [ ] [#21](#issue-21) Coroutine `withLock` / `withPermit` / `awaitAcquire` leak the hold when cancellation races a successful acquire
- [ ] [#22](#issue-22) Coroutine queue twins lose or strand items when cancellation races a successful take

#### Medium
- [ ] [#23](#issue-23) Non-retriable RPC failures escape as a raw checked `ExecutionException`
- [ ] [#24](#issue-24) A per-attempt timeout doesn't cancel the gRPC call, so timed-out writes can land after later ones
- [ ] [#25](#issue-25) Interrupts are handled inconsistently by the RPC engine and swallowed by `leaseRevoke`
- [ ] [#26](#issue-26) `connectionState` never leaves `SUSPENDED` after jetcd recovers a stream on its own
- [ ] [#27](#issue-27) `connectionState` is last-writer-wins across independent streams, and notifications can arrive out of order
- [ ] [#28](#issue-28) The compaction marker is lost when a resync attempt fails
- [ ] [#29](#issue-29) Un-anchored watches lose events across a recovery, and createNotify advances the resume revision early
- [ ] [#30](#issue-30) `putValuesWithKeepAlive` never revokes its lease, and its multi-key puts aren't atomic
- [ ] [#31](#issue-31) The RPC budget isn't threaded through several helpers and call sites
- [ ] [#32](#issue-32) `ping()` and the Spring health indicator can block about 150 s, and RBAC-scoped clusters always report DOWN
- [ ] [#33](#issue-33) Unit tests mock jetcd using the library's own assumptions, so resilience bugs can't surface
- [ ] [#34](#issue-34) Leader-key watches are not revision-anchored, so a hand-off during setup is missed
- [ ] [#35](#issue-35) Step-down leaves `attemptLeadership` set, so a DELETE can start a second concurrent term
- [ ] [#36](#issue-36) A term won via the watch runs on the watch dispatcher, so `close()` can return while it still holds the key
- [ ] [#37](#issue-37) A `LeaderSelector` closed without ever winning can't be restarted
- [ ] [#38](#issue-38) Composite recipes (`LeaderLatch`, `ServiceProvider`) hide the health of the recipes they wrap
- [x] [#39](#issue-39) The uncommitted `DistributedBarrierWithCount` close-race fix leaves a pre-park window open
- [ ] [#40](#issue-40) The count-barrier trip releases the tripper before the global release is committed
- [ ] [#41](#issue-41) Count-barrier waiting keys aren't scoped to a generation
- [ ] [#42](#issue-42) `DistributedBarrier.setBarrier()` after `removeBarrier()` on the same instance silently fails
- [ ] [#43](#issue-43) A broad `catch (EtcdRecipeRuntimeException)` reports infrastructure failures as a lost CAS
- [ ] [#44](#issue-44) `close()` races in-flight lock and permit acquisitions
- [ ] [#45](#issue-45) `DistributedMutex` retries non-retriable lock failures forever, and `tryLock` reports them as a timeout
- [ ] [#46](#issue-46) Semaphore `release()` frees another thread's live permit before consuming a lost one
- [ ] [#47](#issue-47) Java can't catch `InterruptedException` from the concrete `DistributedMutex` / `DistributedSemaphore` types
- [ ] [#48](#issue-48) `tryLock`/`tryAcquire` deadlines don't bound the setup and cleanup RPCs
- [ ] [#49](#issue-49) The work queue's empty-wait watch isn't revision-anchored (the lost wakeup that PR #68 fixed elsewhere)
- [ ] [#50](#issue-50) An ambiguous take commit loses the item (plain queues) or strands the claim (work queue)
- [x] [#51](#issue-51) Retried enqueue puts can duplicate an item that was already consumed
- [ ] [#52](#issue-52) `AbstractQueue` takes don't react to `close()`
- [x] [#53](#issue-53) `enqueue(value, Duration.INFINITE)` writes a poison key that breaks every receive
- [ ] [#54](#issue-54) The orphan sweep issues one transaction per in-flight claim on every idle wake
- [ ] [#55](#issue-55) `enqueueAll`'s within-batch order relies on etcd's unstable sort
- [ ] [#56](#issue-56) `TransientKeyValue` parks an executor thread for its whole lifetime, and `start()` waits with no timeout
- [ ] [#57](#issue-57) `PathChildrenCache` fires INITIALIZED after the watch is live: out of order, a snapshot per listener, and a deadlock
- [ ] [#58](#issue-58) A compaction resync updates caches silently, so listeners and flows never see the gap's changes
- [ ] [#59](#issue-59) One malformed or newer-schema service instance breaks discovery for the whole service
- [ ] [#60](#issue-60) `PathChildrenCache.rebuild()` can permanently undo a watch event
- [ ] [#61](#issue-61) `ServiceProvider` error counts never reset, and down-entries accumulate
- [ ] [#62](#issue-62) Re-registering after `LeaseEvent.Failed` leaks the old healer
- [ ] [#63](#issue-63) `interruptibleOn` misses re-wrapped interrupts, so some cancellations surface as `EtcdRecipeException`
- [ ] [#64](#issue-64) `interruptOnPermitLoss` / `interruptOnLockLoss` misfire under the suspend surface
- [ ] [#65](#issue-65) `leadershipAsFlow`: a blocking uncancellable GET, swallowed re-read failures, and a fixed RPC budget
- [ ] [#66](#issue-66) A terminal watch failure never completes the watch and leadership flows
- [ ] [#67](#issue-67) The suspend RPC engine never records `EtcdMetrics.recordRpc`
- [ ] [#68](#issue-68) Every published artifact forces `logback-classic`, guava, and common-utils onto consumers
- [ ] [#69](#issue-69) The Ktor plugin closes its client on `ApplicationStopping`, before user teardown runs
- [ ] [#70](#issue-70) RPC-backed Micrometer gauges can stall the metrics scrape during an outage
- [ ] [#71](#issue-71) A deadlocked test can hang CI for 45 min without identifying the test

#### Low
- [ ] [#72](#issue-72) `close()` from a watcher's or healer's own thread always stalls 5 s
- [ ] [#73](#issue-73) Closing the `Client` while a watcher is open makes it retry forever, silently
- [ ] [#74](#issue-74) `keepAlive()` reports transient stream errors as "renewal stopped"
- [ ] [#75](#issue-75) The `EtcdConnector` exception list grows without bound
- [ ] [#76](#issue-76) The compaction fallback resumes one revision too late
- [ ] [#77](#issue-77) `Duration`-typed APIs hidden from Java (`EtcdRecipes.distributedPriorityQueue`, `LeaderLatch` `closeJoinTimeout`)
- [ ] [#78](#issue-78) Passwords appear in `toString()`, and half-configured mTLS is silently ignored
- [ ] [#79](#issue-79) Gaps in background logging context (MDC)
- [ ] [#80](#issue-80) `DistributedAtomicLong` resilience gaps (no close check, uncapped backoff, undocumented ambiguous commits)
- [ ] [#81](#issue-81) Read-write lock and semaphore releases rely on a single, un-retried revoke
- [ ] [#82](#issue-82) Lock-loss bookkeeping is keyed by thread, and `holdCount` is read cross-thread without synchronization
- [ ] [#83](#issue-83) Locks expose no fencing token, and the default lease TTL is 2 s
- [ ] [#84](#issue-84) Election and barrier API traps (`close()` before `start()`, unused `clientId`)
- [ ] [#85](#issue-85) `LeaderObserver.onRecovery` replays leadership on every recovery
- [x] [#86](#issue-86) `DistributedBarrier.close()` doesn't unpark waiters, and its internal reads are close-checked
- [ ] [#87](#issue-87) Code-quality leftovers: Java atomics reintroduced, unused fields, stale `ElectionPaths`
- [ ] [#88](#issue-88) `TransientKeyValue`: retrying `start()` after a failure can leave a published key that can't be removed
- [ ] [#89](#issue-89) Work-queue sweeper failures are dropped at DEBUG
- [ ] [#90](#issue-90) `ack()`/`requeue()` are guarded on the instance's `clientId`, not on the specific claim
- [ ] [#91](#issue-91) Queue metrics and small interop and docs gaps
- [ ] [#92](#issue-92) Head selection makes etcd read and sort the whole range (O(N²) drain)
- [ ] [#93](#issue-93) `TypedPathChildrenCache`: one throwing listener stops the event reaching the rest
- [ ] [#94](#issue-94) Discovery lifecycle and naming traps
- [ ] [#95](#issue-95) `eventsAsFlow().onStart { }` doesn't guarantee the cache listener is registered
- [ ] [#96](#issue-96) The docs claim every blocking call has a suspending twin, but several don't
- [ ] [#97](#issue-97) `withLock(timeout)`'s "null means not acquired" is false for a nullable `T`
- [ ] [#98](#issue-98) Micrometer gauge binders can report the wrong recipe, and the documented example binds to recipes it then closes
- [ ] [#99](#issue-99) `EtcdProperties` binding depends on a transitive `kotlin-reflect`, and the starter's tests don't check binding
- [ ] [#100](#issue-100) `TransientKeyValueTest` counts every key under `/keyvalue`, which other test classes write to in parallel
- [ ] [#101](#issue-101) Some regression tests can't fail for the bug they target
- [ ] [#102](#issue-102) The minimum Kotlin version for consumers (2.3+) is undocumented
- [ ] [#103](#issue-103) Documentation drift (`CLAUDE.md`, README, `llms.txt`, CHANGELOG, version string)
- [ ] [#104](#issue-104) Most examples depend on helper libraries that consumers won't have
- [ ] [#105](#issue-105) CI hygiene (token permissions, action versions, ungated docs deploy)

## Remediation plan

The ordering follows five principles:

1. **Land in-flight work first.** Don't commit a half-fix.
2. **Stop silent data loss and ship a patch release.** The three Critical issues and the broken POM (#5) are all small, isolated fixes.
3. **Fix the RPC engine before anything that classifies exceptions.** #4 and #23 change which failures retry and what types callers see. Many recipe-level fixes branch on those types.
4. **Do quick, local High fixes before shared-infrastructure design work.** That removes the most risk soonest, while the design items (#10, #27, #36) get a short design note.
5. **Leave integrations, tests, and docs for last**, except the test-harness hang protection, which comes early as insurance before the behavior changes.

Each step is sized to be one PR. Steps within a phase are mostly independent and can run in parallel, unless the "Depends on" column says otherwise.

### Phase 0: Land the in-flight work

| Step | Issues | Rationale | Depends on | Size |
|---|---|---|---|---|
| 1 | #39, #86, #87 (only the qualified `AtomicReference` in `DistributedBarrierWithCount`) | The working tree already has half of this. Close the pre-park window, apply the same raw-read and cancel-hook pattern to `DistributedBarrier`, and add a test that closes immediately after `waitOnBarrier` starts. | – | S |

### Phase 1: Stop the bleeding, then release 0.12.1

| Step | Issues | Rationale | Depends on | Size |
|---|---|---|---|---|
| 2 | #2, #3, #51, #53 | Queue key namespace and uniqueness. Use create-only, non-retried enqueue txns, which also covers #51. One PR in the enqueue and take paths. | – | S–M |
| 3 | #6, #7, then #1 | Read-write lock correctness, all in `nearestConflict`. Do the two one-line fixes first; the downgrade rank fix (#1) is the larger part. | – | M |
| 4 | #5, #68, #104, #102, and #103 (version bump and CHANGELOG only) | Per-module `api`/`implementation` scopes, logback moved out of library deps, examples moved to the stdlib. Ship 0.12.1 afterward, because 0.12.0 consumers are affected by #2, #3, and #5. | – | S–M |

### Phase 2: Foundations

| Step | Issues | Rationale | Depends on | Size |
|---|---|---|---|---|
| 5 | #71, #100 | Bounded test waits, a Gradle `Test` timeout, and report upload on cancel. This is cheap insurance before the behavior changes in Step 6. | – | S |
| 6 | #33 (fixtures first), #4, #23, #25, #24 | Make the RPC engine match jetcd's real failures. Fixing #4 turns status-based retry on for the first time, so decide in the same PR which writes may retry (#24). Verify with the full `make tests-tc` run, not a package subset. | 5 | M |
| 7 | #8, #9, #43, #62, #19, #74 | Lease heal and establish-hook correctness: revoke on hook failure, a `timeToLive` check before re-granting, own-key reclaim, and catching only `EstablishDeclinedException`. | 6 | M |

### Phase 3: Quick High-severity fixes

These are independent, local, and mostly small.

| Step | Issues | Rationale | Depends on | Size |
|---|---|---|---|---|
| 8 | #11, #12, #37, #84, #85 | `LeaderSelector` and `LeaderObserver` lifecycle fixes that don't need the Step 16 restructure. | – | S |
| 9 | #15, #80 | Make the counter self-initializing, with a close-aware and capped CAS loop. | 6 | S |
| 10 | #16, #17, #18, #52, #49, #89, #90 | Work-queue and queue lifecycle: dead-lettering on requeue, bounded `claimHead`, close-awareness, an anchored watch, and per-claim tokens. | 6 | M |
| 11 | #20, #57, #58, #60, #93 | Cache event path: surface start failures, fire INITIALIZED before the watch, emit events on resync, make rebuild revision-safe. | – | M |
| 12 | #21, #22, #63, #64 | Coroutine cancellation safety: undo on a late cancel, and classify cancellations by job state. | 6. Coordinate the `withPermit` undo with #46 (Step 17). | M |

### Phase 4: Shared-infrastructure design work

Write a short design note first for Steps 14 and 16.

| Step | Issues | Rationale | Depends on | Size |
|---|---|---|---|---|
| 13 | #31, #30, #32, #70 | Thread `rpc` through the remaining helpers and call sites. Give probes (`ping`, health, gauges) a tight single-attempt budget. Mostly mechanical. | 6 | S–M |
| 14 | #10, #27, #26, #38, #72, #75 | A per-connector serial notifier (listeners off the event loop, ordered delivery), per-stream state aggregation, a "resumed" signal after transient suspension, composite health forwarding, and a capped exception list. | 7 | M–L |
| 15 | #28, #29, #76, #73 | `ResilientWatcher` revision correctness: a sticky compaction marker, createNotify handling, the resume revision, and terminal handling of a closed client. | – | S–M |
| 16 | #36, #35, #14, #13, #34 | Restructure `LeaderSelector` so the watch only signals the start worker, which runs every term in one loop. That makes Phase-1 failures retryable (#14), prevents same-node re-entry (#35), and lets `close()` wait for every term (#36). Also: internal threads for the watch and advertise tasks (#13), and anchored leader watches (#34, including `LeaderObserver` and `leadershipAsFlow`). | 14, 15 | L |

### Phase 5: Remaining recipe fixes

These steps are independent of each other.

| Step | Issues | Rationale | Depends on | Size |
|---|---|---|---|---|
| 17 | #44, #45, #46, #47, #48, #81, #82 | Lock lifecycle and semantics: abort attempts before draining holds on close, retry classification, owner-aware semaphore release, `@Throws`, deadline-bounded RPCs, retried release. | 6 | M |
| 18 | #50, #55, #54, #92, #91 | Queue ambiguity, ordering, and performance: claim reconciliation on failure, tie-breaking within a batch, keys-only sweeps and head selection, queue metrics. | 2, 10 | M |
| 19 | #56, #88 | `TransientKeyValue` lifecycle: no parked thread, and a retry-safe `start()`. | – | S–M |
| 20 | #65, #66, #67, #95, #96, #97 | Coroutine flows and parity: complete flows on terminal failure, suspend-path metrics, the missing twins. | 6 | M |
| 21 | #59, #61, #94 | Discovery robustness: decode once and skip bad entries, a windowed error count, pruning and naming. | – | S–M |
| 22 | #40, #41, #42 | Count-barrier protocol: delete `/ready` before releasing, generation-scoped waiting keys, per-call `removeBarrier` state. | 1 | M |

### Phase 6: Integrations, tests, and docs

| Step | Issues | Rationale | Depends on | Size |
|---|---|---|---|---|
| 23 | #69, #98, #99, #78 | Satellites: Ktor close on `ApplicationStopped`, gauge naming and unbinding, `@ConstructorBinding` plus binding tests, redacted `toString()`. | 13 (health timeout already in place) | S |
| 24 | #101, #105 | Make the regression tests able to fail. CI: token permissions, action versions, deploy gated on CI. | 5 | S–M |
| 25 | #103 (the rest), #77, #79, #83, #87 (the rest) | Docs drift, Java-visible `Duration` overloads (with a `javap` check in CI), MDC coverage, a fencing-token API, code-quality leftovers. | – | M |

**Working notes for every step:**
- Verify with `make tests-tc`, or the full `-PuseTestcontainers` run, before merging. Package subsets miss cross-recipe timing changes, especially after Step 6.
- `DesignFixesTests` makes static assertions on source text. For example, fix #8 counts literal `dequeue()` occurrences. Keep new comments and code clear of the phrases it counts.
- New Kotlin tests: Kotest `StringSpec` with an `init {}` block, and MockK for the deterministic race tests. Several findings below name the exact MockK shape.
- Add a CHANGELOG `[Unreleased]` entry per step. #26, #27, and #97 change observable behavior or signatures.

## Detailed findings

### Critical

#### <a id="issue-1"></a>1. `DistributedReadWriteLock` write→read downgrade deadlocks when another writer queues in between
**Critical** · lock · concurrency · effort M · Confirmed
**Location:** `lock/DistributedReadWriteLock.kt:315-347` (`nearestConflict`), KDoc at `:55-62`

- **Problem:** When a thread that holds the write lock calls `readLock.lock()`, its new read entry waits on the nearest earlier *write* entry. Only the thread's own write entry (`ownWriteEntry`) is excluded. A writer from another process that queued after the held write entry, but before the new read entry, is not excluded.
- **Failure scenario:**
  1. Thread T holds write entry `Wt` (rev 10).
  2. Process P's writer `Wp` (rev 11) waits on `Wt`.
  3. T calls `readLock.lock()`, which creates `Rt` (rev 12). `Rt` waits on `Wp`.
  4. `Wp` waits on `Wt`, and T can't release `Wt` because T is parked.

  Both unbounded `lock()` calls hang forever, and the leases keep renewing, so nothing breaks the cycle. The existing downgrade test (`DistributedReadWriteLockTests.kt:183-197`) runs without contention.
- **Fix:** The downgraded read entry must inherit the write entry's queue position. For example, store `Wt`'s createRevision in `Rt`'s value as an effective rank, and have `nearestConflict` filter and sort on that rank. This is sound because `Rt` exists before `Wt` is deleted, so `Wp` sees it on its next snapshot. A conservative alternative is to keep `Wt` alive as the backing entry of the downgraded read hold until both are released. Do *not* admit the downgrade immediately, Curator-style: that lets `Wp` in while `Rt` is reading.
- **Related:** #6 and #7 (same function).

#### <a id="issue-2"></a>2. Queue take steals and deletes keys from sibling paths that share the queue's string prefix
**Critical** · queue · correctness · effort S · Confirmed
**Location:** `queue/AbstractQueue.kt:145-161` (the watch), `:186-195` (the fallback); compare `common/ChildrenExtensions.kt:71`

- **Problem:**
  - The empty-queue wait watches `queuePath` with `isPrefix(true)` and no trailing `/`. `withWatcher` doesn't add one; `getFirstChild` does. So any PUT under, say, `/jobs-retry/…` or `/jobs2/…` wakes a `/jobs` consumer.
  - After the re-query of `/jobs/` comes back empty, the code falls back to the key the watch saw: `getFirstChild(...).kvs.firstOrNull() ?: keyFound.load()`.
  - That foreign key goes to `deleteRevKey`, which CAS-deletes it and returns its value as a `/jobs` item.
- **Failure scenario:**
  - An idle `DistributedQueue("/q1")` consumer is waiting, and a producer enqueues into `/q10`. The item is stolen from `/q10` and delivered from `/q1`.
  - If the sibling is a `DistributedWorkQueue`, the stolen key is `claimed/<id>` or `claims/<id>`. Either the payload is destroyed or the item is delivered twice.
  - The fallback can never win legitimately. The re-query is a linearizable read taken after the event, so any in-range key it doesn't return has already been deleted.
- **Fix:**
  - Watch `queuePath.ensureSuffix("/")`.
  - Drop the `?: keyFound.load()` fallback: return null so the outer loop re-reads.
  - Add a sibling-path test (`/x` vs `/x2`).
  - `DesignFixesTests` fix #8 counts literal `dequeue()` occurrences in source text, so keep that phrase out of new comments.
- **Related:** #6 (the same class of bug).

#### <a id="issue-3"></a>3. Colliding enqueue keys silently overwrite queued items
**Critical** · queue · correctness · effort S · Confirmed (the rate is arithmetic from the key format)
**Location:** `queue/DistributedQueue.kt:53-77` (`enqueue`, `enqueueAll`, key formats); `queue/DistributedWorkQueue.kt:176-177`, `:196-202`, `:212`, `:414-419` (`promoteMatured`)

- **Problem:**
  - Keys are `%019d(currentTimeMillis)-randomId(3)`. That gives 62³ = 238,328 possible suffixes per millisecond.
  - Keys are written with an unconditional put (`enqueue`) or with `setTo` inside a txn (`enqueueAll`, `promoteMatured`).
  - Two enqueues in the same millisecond that draw the same suffix produce the same key, and the second silently replaces the first.
- **Failure scenario:**
  - With Poisson arrivals, that is about 1.8 lost items a day at 100 enqueues/s and about 180 a day at 1,000/s. Nothing reports the loss.
  - In the work queue, a colliding id also clobbers the `claimed/`, `claims/` and `attempts/` state of an in-flight item.
  - The semaphore already guards this exact pattern with `If(entryKey.doesNotExist)` (`lock/DistributedSemaphore.kt:249-257`).
- **Fix:**
  - Use a longer suffix: at least 10 characters, or a UUID.
  - Make the writes create-only: `If(key.doesNotExist) Then(put)`, regenerating the key on a CAS miss. Apply this in `DistributedQueue.enqueue`/`enqueueAll` and in `DistributedWorkQueue.enqueue` (both overloads) and `enqueueAll`.
  - Add `"$itemsPath/$basename".doesNotExist` to the `promoteMatured` guard.
  - Doing the write as a non-retried txn also fixes #51.
- **Related:** #51, #24.

### High

#### <a id="issue-4"></a>4. RPC retry predicate never matches real jetcd failures, so configured status-based retries are inert
**High** · common · resilience · effort S · Confirmed (jetcd 0.8.7 source)
**Location:** `common/RpcRetry.kt:29`, `:90-93`; also used by `coroutines/RpcSuspend.kt:73`

- **Problem:**
  - `isRetriableRpcFailure` retries a `TimeoutException`, or an `EtcdException` with code UNAVAILABLE, INTERNAL or DEADLINE_EXCEEDED.
  - jetcd's KV and Txn calls go through `Impl.execute`, which is `Failsafe…getStageAsync(() -> supplier.get().toCompletionStage()).thenApply(resultConvert)`. It never calls `EtcdExceptionFactory.toEtcdException`; only the older `completable(...)` helper does.
  - So failures arrive as `ExecutionException(StatusRuntimeException)`, with no `EtcdException` anywhere in the cause chain.
  - The repo already knows this. The `SelfHealingKeepAlive.isLeaseNotFound` KDoc says "transactions and puts surface the raw gRPC StatusRuntimeException".
- **Failure scenario:**
  - During an etcd leader election or member restart, the server returns UNAVAILABLE ("no leader", "leader changed").
  - `putValue`, `deleteKey`, `deleteChildren`, transactions and `unlock` fail on the first attempt.
  - GETs and lease ops get only jetcd's own internal retries.
  - `RpcResilience` retries only client-side attempt timeouts. That breaks the promises in `RpcResilience.kt:27-31` and `KVExtensions.kt:34-36`.
- **Fix:**
  - Also match `io.grpc.Status.fromThrowable(t).code in {UNAVAILABLE, INTERNAL, DEADLINE_EXCEEDED}` for `StatusRuntimeException`/`StatusException`, and keep the `EtcdException` branch.
  - In `RpcRetryTests`, fail futures with `Status.UNAVAILABLE.asRuntimeException()` the way jetcd does.
  - This turns retry on everywhere, so decide in the same change which writes are safe to retry (#24, #51), and run the full `make tests-tc` suite.
  - While there: `isKeyPresent`/`isKeyNotPresent` (`KVExtensions.kt:161-170`) use a non-retried txn for a read-only check. A retried count-only GET would be better.
- **Related:** #23, #24, #33, #51, #67.

#### <a id="issue-5"></a>5. Published core POM scopes jetcd and kotlinx-serialization as runtime-only although both are in the public API
**High** · build · build · effort S · Confirmed (published 0.12.0 POM and module metadata)
**Location:** `build.gradle.kts:80-96` (the root `subprojects {}` block); `README.md:520-560`; `llms.txt:57`

- **Problem:**
  - The root `subprojects {}` block adds `jetcd-core` and `kotlinx-serialization-json` as `implementation` to every module, and `etcd-recipes-core` has no build file of its own.
  - Gradle maps `implementation` to Maven `runtime` scope. The published `etcd-recipes-core-0.12.0.pom` has `jetcd-core <scope>runtime</scope>`, and the module metadata's `apiElements` lists only coroutines and the stdlib.
  - Yet the public API is built on these types:
    - every recipe constructor takes `io.etcd.jetcd.Client`, and `connectToEtcd()` returns one;
    - `EtcdCodec` encodes to `ByteSequence`;
    - `jsonCodec(json: Json)` and `KotlinxJsonCodec(serializer: KSerializer<T>, …)` expose kotlinx-serialization.
- **Failure scenario:**
  - A consumer who follows the README and adds only `com.pambrose:etcd-recipes-core:0.12.0` gets "Cannot access class 'io.etcd.jetcd.Client'" from Kotlin, or "class file for io.etcd.jetcd.Client not found" from Java.
  - The in-repo build can't catch this, because the root block gives every module its own direct jetcd dependency.
  - The satellites have the same problem (`Application.etcdClient: Client`, the Spring `etcdClient` bean).
- **Fix:**
  - Add `etcd-recipes-core/build.gradle.kts` with `api(libs.jetcd.core)` and `api(libs.kotlinx.serialization.json)`.
  - Move the blanket dependency list out of the root `subprojects {}` block.
  - Have the satellites and examples see jetcd only through `api(project(":etcd-recipes-core"))`, so a scope regression fails compilation.
  - Optionally, add a smoke build that consumes the `publishToMavenLocal` output.
  - Ship the fix as 0.12.1.
- **Related:** #68, #104.

#### <a id="issue-6"></a>6. `DistributedReadWriteLock` conflict scan bleeds into sibling lock paths
**High** · lock · correctness · effort S · Confirmed
**Location:** `lock/DistributedReadWriteLock.kt:321-330`

- **Problem:** `nearestConflict` reads `client.getResponse(lockPath, getOption { isPrefix(true) … })` with no trailing `/`. The semaphore uses `"$holdersPath/"`, and `isLocked` goes through `getChildrenKeys`, which adds the `/`.
- **Failure scenario:**
  - Take two locks, `/locks/order-1` and `/locks/order-10`. Every `/order-10` entry counts as a conflict for `/order-1`, which is false contention.
  - Worse, a thread holding the `/order-10` write lock that then takes the `/order-1` write lock waits for its *own* `/order-10` entry to be deleted. `ownWriteEntry` is tracked per instance, so it isn't excluded, and the thread hangs forever.
- **Fix:** Query `lockPath.ensureSuffix("/")`, and add a sibling-path test.
- **Related:** #1, #2, #7.

#### <a id="issue-7"></a>7. `DistributedReadWriteLock`: a `clientId` containing `/` lets a reader and a writer hold at once
**High** · lock · correctness · effort S · Confirmed. It needs a non-default `clientId`; the impact is a mutual-exclusion violation.
**Location:** `lock/DistributedReadWriteLock.kt:225`, `:337-342`, `:157`; constructor `:71`

- **Problem:** Entry keys are `"$lockPath/${side.entryPrefix}$clientId:${token}"`. Readers classify an entry by checking whether `kv.key.asString.substringAfterLast('/')` starts with `write-`. `clientId` is a public constructor parameter and is never validated.
- **Failure scenario:**
  1. A writer with `clientId = "orders/pod-7"` creates `/locks/x/write-orders/pod-7:AbC`.
  2. The reader computes the basename as `pod-7:AbC` and finds no write conflict.
  3. The reader is admitted while the writer holds the lock.

  `isLocked` misclassifies the same way.
- **Fix:** Classify on the name relative to the lock path (`key.removePrefix("$lockPath/")`), and add `require('/' !in clientId)` in `init`, or encode the id.
- **Related:** #6.

#### <a id="issue-8"></a>8. Lease heal leaks the newly granted lease when the establish hook throws
**High** · common · resource-leak / correctness · effort S · Confirmed. Permanent loss needs an ambiguous commit.
**Location:** `common/SelfHealingKeepAlive.kt:205-247` (`runAttempt`, `runEstablishForHeal`); compare `start()` at `:94-104`

- **Problem:** If the establish hook throws, `start()` revokes the granted lease ("Nothing is holding the lease if the hook blew up part-way"). The heal path doesn't: an exception from `establish(granted)` reaches `runAttempt`'s catch, which only schedules the next attempt.
- **Failure scenario:** `ServiceRegistry`, `DistributedBarrier`, `DistributedBarrierWithCount` and `LeaderSelector` participation all establish with `If(path.doesNotExist) Then(put with lease)`.
  1. Heal attempt 1 grants lease L2. The txn times out but actually commits, so the key is now bound to L2, which nobody renews.
  2. Attempt 2 grants L3. The CAS fails because the key exists, so L3 is revoked and `Failed` is emitted.
  3. L2 expires and deletes the key.

  A transient ambiguous commit has become a permanent deregistration or loss of participation.
- **Fix:** In `runAttempt`, wrap `establish` and `register` in a try/catch that revokes `granted` before rethrowing, mirroring `start()`. Add a test where establish throws during a heal and assert the granted lease is revoked. This pairs with #9, which lets the next attempt reclaim the key.
- **Related:** #9.

#### <a id="issue-9"></a>9. Self-healing CAS hooks can't reclaim their own key, and a client-side deadline is treated as server-side expiry
**High** · common, plus discovery, barrier and election · correctness · effort M · Plausible. It depends on etcd extending leases on a leader change; the jetcd DeadLine logic was verified in source.
**Location:**
- `common/SelfHealingKeepAlive.kt:144-162` (`val expired = error == null || error.isLeaseNotFound()`) and `:206-247`
- Establish hooks: `discovery/ServiceRegistry.kt:108-123`, `barrier/DistributedBarrier.kt:105-117`, `barrier/DistributedBarrierWithCount.kt:199-210`, `election/LeaderSelector.kt:393-400`

- **Problem:**
  - jetcd fires `onCompleted` from its own client-side DeadLine check (last response + TTL) and stops renewing the lease.
  - The healer treats that as proof the server expired the lease. It grants a new lease and re-runs the establish CAS, `If(doesNotExist)`.
  - But after an etcd leader change, the new leader's lessor extends every lease. A GC pause can also delay a queued keep-alive response past the deadline. Either way, the old lease and its key may still be alive.
  - The CAS then fails on the process's *own* key. The heal declines and emits `Failed`, and the old lease, which is no longer renewed, expires seconds later.
  - `ServiceRegistry`'s comment reasons that "instance ids are unique to this process; a CAS loss means the key unexpectedly exists". That contradicts itself: the only possible holder of the key is this process's own previous lease.
  - `TransientKeyValue` does a plain put when it heals, so it is immune.
- **Failure scenario:**
  - An etcd leader election runs longer than the remaining keep-alive deadline (the default TTL is 2 s).
  - When the cluster recovers, the service instance, barrier or election participant is permanently deregistered.
  - The tests miss it: `ServiceRegistryHealTests` mocks the CAS to always succeed, and `SelfHealingLeaseFaultTests` covers only `TransientKeyValue`.
- **Fix:**
  - (a) At the start of `runAttempt`, call `timeToLive(expiredLeaseId)`. If the TTL is > 0, re-register a keep-alive on the same lease, skip grant and establish, and emit a "resumed" event. Only re-grant when the TTL is -1.
  - (b) Let establish hooks reclaim their own key: track the last bound lease id, and on a CAS loss run `If(lease(key) == boundLeaseId) Then(put with new lease)`.
  - Add a fault test that pauses etcd while an instance is registered.
  - Correct the docs that say `Expired` means the keys are gone (`LeaseResilience.kt:65-70` and the website).
  - Also, `runEstablishForHeal` emits `Failed(…, lastCause)` with a possibly stale grant error as the cause.
- **Related:** #8, #62.

#### <a id="issue-10"></a>10. Listener and `recordException` callbacks run on jetcd's Vert.x event loop, contrary to the documented contract
**High** · common, lock, election, coroutines · concurrency · effort M · The contract violation is Confirmed; the stall and cascade are Plausible.
**Location:**
- `common/EtcdConnector.kt:105-118` (`recordException`) and `:161-176` (`transitionTo`). The contract is stated at `:163-166`, in `common/BackgroundExceptionListener.kt:26-28`, and in `website/…/resilience/connection-state.md:78` and `observability.md:99`.
- Callers on the lease-callback thread: `lock/AcquisitionLease.kt:63-77`, `lock/DistributedMutex.kt:193-197` and `:264-304`, `lock/DistributedReadWriteLock.kt:232-236` and `:360-398`, `lock/DistributedSemaphore.kt:240-244` and `:347-384`, `election/LeaderSelector.kt:546-579`.
- Flows fed from those callbacks: `coroutines/LockFlows.kt:42-61`, `coroutines/ExceptionFlows.kt:35-47`.

- **Problem:**
  - The docs promise that listeners run on "a healer or a watch dispatcher, never jetcd's event loop". `SelfHealingKeepAlive` hops onto its own executor for exactly that reason.
  - `AcquisitionLease`'s observer and `LeaderSelector`'s leadership keep-alive observer don't. They call `recordException`, `reportLeaseEvent`, the lock-lost and permit-lost listeners, and step-down inline, from jetcd's `LeaseImpl` handlers. Those are Vert.x stream handlers, and the DeadLine check is a `vertx().setPeriodic` callback.
  - `EtcdConnector` then calls `BackgroundExceptionListener`s and `ConnectionStateListener`s inline on that same thread.
  - The coroutine flows fed from these callbacks use `callbackFlow`'s default buffer (64, SUSPEND) with `trySendBlocking`. A slow collector therefore parks the callback thread, which contradicts `LockFlows`' "unconditionally unlimited" KDoc.
- **Failure scenario:**
  - A user's `addLockLostListener { mutex.tryLock(…) }`, or a `ConnectionStateListener` that calls `ping()` on SUSPENDED, runs a blocking RPC on the event loop. The response has to come back on that same loop.
  - Keep-alive processing for every lease on the client stalls until the RPC times out: about 2.5 min with the defaults, and forever under `RpcResilience.DISABLED`.
  - Meanwhile the process's other locks and leadership expire.
  - The same happens with `backgroundExceptionsAsFlow(capacity = 16)` and a collector that makes RPCs.
- **Fix:**
  - Keep the phase CAS and future completion inline, since they don't block.
  - Dispatch `recordException`, `reportLeaseEvent`, the listener loops and interrupts onto a serial daemon executor, per recipe or per connector, wrapped in `withRecipeLoggingContext`, and shut it down in `doClose`.
  - Doing the exchange and the enqueue under one lock also fixes delivery order (#27).
  - Add `.buffer(Channel.UNLIMITED)` to `lockLostAsFlow` and `permitLostAsFlow`. Make a bounded `backgroundExceptionsAsFlow` use non-blocking `trySend` with DROP_OLDEST.
  - Document the listener thread on `LockLostListener` and `PermitLostListener`.
- **Related:** #26, #27, #72.

#### <a id="issue-11"></a>11. `LeaderSelector.waitOnLeadershipComplete(timeout)` ignores its timeout
**High** · election · correctness · effort S · Confirmed
**Location:** `election/LeaderSelector.kt:326-332` (with `:200` and `:297-306`)

- **Problem:** The timed overload first does an untimed `startThreadComplete.waitUntilTrueWithInterruption()`. `start()` resets that flag, and the start worker sets it only in its `finally`, after `leadershipComplete`. So the timed wait blocks until this node wins and finishes its term, or until `close()`.
- **Failure scenario:**
  - A standby that calls `waitOnLeadershipComplete(5, SECONDS)` never gets `false` back.
  - A 1 s wait on a node that holds a 60 s term takes 60 s or more.
  - `coroutines`' `awaitLeadershipComplete(timeout)` inherits the bug.
  - Every test uses the timed form and expects `true`.
- **Fix:** Apply one deadline to both waits: `if (!leadershipComplete.wait(timeout)) return false`, then wait on `startThreadComplete` for the remaining time. Add a test for the timeout path.

#### <a id="issue-12"></a>12. `LeaderSelector.close()` from inside `takeLeadership` self-deadlocks
**High** · election · concurrency · effort S · Confirmed
**Location:** `election/LeaderSelector.kt:357-364` (`doClose`), `:297`, `:306`

- **Problem:** `doClose` waits uninterruptibly on `startThreadComplete`, which only the start worker's `finally` sets. When the node won at `start()`, `takeLeadership` is running on that same start worker.
- **Failure scenario:**
  - `takeLeadership` calls `selector.close()` on a fatal condition. The thread then waits on itself forever while holding the instance monitor, and any other `close()` caller blocks too.
  - If the node won via the watch instead, the same call stalls for 5 s (#72).
  - `LeaderSelectorCloseDeadlockTests` only closes from another thread.
- **Fix:** In `doClose`, skip the wait when `Thread.currentThread() === leadershipThreadRef.load()`; the term unwinds once the callback returns. Add a close-from-callback test.

#### <a id="issue-13"></a>13. `LeaderSelector.start()` can hang forever, uninterruptibly
**High** · election · concurrency / api-design · effort M · Confirmed
**Location:** `election/LeaderSelector.kt:149`, `:208`, `:215`, `:278`, `:292`, `:311`, `:361`

- **Problem:**
  - The start worker, the watch task and the advertise task all run on `executor`, and each one lasts for the whole candidacy.
  - `start()` waits uninterruptibly on `electionSetup`, which requires `watchStarted`.
  - The watch task's `catch` never sets `watchStarted`.
- **Failure scenario:**
  - (a) A user executor with fewer than 3 free threads: a single-thread pool, a shared pool already used by another selector, or a direct executor. The watch task never runs, so `start()` never returns.
  - (b) The jetcd client is closed. `watch()` throws synchronously, `watchStarted` is never set, and `start()` hangs.
  - (c) A shut-down user pool throws `RejectedExecutionException`. `start()` throws, but `startThreadComplete` stays false, so a later `close()` hangs.

  `LeaderLatch.start()` inherits all three. The 3-thread requirement is undocumented, and `concepts.md` encourages passing a `userExecutor`.
- **Fix:**
  - Run the watch and advertise tasks on internal threads, and use `userExecutor` only for the term.
  - In the watch task's `catch`, publish the failure and release `electionSetup` so `start()` rethrows it.
  - Restore state when execution is rejected.
  - Make the `start()` wait interruptible.
- **Related:** #14, #36 (the same restructuring).

#### <a id="issue-14"></a>14. Watch-triggered election attempts swallow Phase-1 failures and never retry, so the election can be left leaderless
**High** · election · resilience / correctness · effort M · Plausible. The missing retry is confirmed; the leaderless outcome needs correlated RPC failures.
**Location:** `election/LeaderSelector.kt:257-264` (DELETE handler), `:448-483` (Phase 1); `common/WatchExtensions.kt:125-126`

- **Problem:**
  - In Phase 1 of `attemptToBecomeLeader`, `leaseGrant` and the CAS txn aren't inside any try.
  - On the watch path, an exception reaches `ResilientWatcher`'s `runCatching { block(response) }.onFailure { logger.error … }`. It is only logged: no `recordException`, no state change, no retry.
  - The leader key is now absent, so no further DELETE will ever arrive to trigger another attempt.
- **Failure scenario:**
  1. The leader's lease expires during an etcd blip.
  2. Every candidate gets the DELETE and runs Phase 1 at the same moment.
  3. The txn, which is never retried, fails with UNAVAILABLE or a timeout on each of them.
  4. Nobody ever retries, so the election stays leaderless for good.

  A 2-node active/standby pair needs only one such failure. `LeaderLatch` is stuck too, and the granted lease leaks until its TTL.
- **Fix:** Wrap Phase 1 in a try/catch that revokes `granted`, calls `recordException`, and schedules a re-probe/retry paced by `resilience.watch.retryPolicy`, until the node wins, loses the CAS, or terminates. This fits best with #36's restructuring, where the watch only signals the start worker, which loops.
- **Related:** #13, #34, #35, #36.

#### <a id="issue-15"></a>15. `DistributedAtomicLong` is permanently broken if first-use initialization fails
**High** · counter · correctness · effort S · Confirmed
**Location:** `counter/DistributedAtomicLong.kt:74-105` (`get`, `ensureStarted`), `:129-140`

- **Problem:**
  - `ensureStarted` sets `startCalled` with a CAS and runs `createCounterIfNotPresent`, a non-retried txn, exactly once. If that fails, the flags stay set.
  - `get()` reads with a default of `-1L`.
  - `applyCounterTransaction` does `check(kvList.isNotEmpty()) { "Empty KeyValue list" }`.
- **Failure scenario:**
  - One transient failure on first use, for example during a leader election, breaks the instance for good. From then on `get()` silently returns -1, which is a legitimate counter value, and every `increment`/`add` throws `IllegalStateException`.
  - The same happens after another process calls `DistributedAtomicLong.delete(...)`.
- **Fix:** Make the CAS loop self-initializing: when the key is absent, run `If(doesNotExist) Then(put(default + amount))`. Have `get()` return `default` when the key is absent. At minimum, reset `startCalled` when initialization throws.
- **Related:** #80, #31.

#### <a id="issue-16"></a>16. `DistributedWorkQueue.requeue()` bypasses `maxDeliveries`, so poison messages loop forever
**High** · queue · correctness · effort S · Confirmed
**Location:**
- `queue/DistributedWorkQueue.kt:297-308` (`requeue`), `:340` (the `claimHead` attempt count), `:380-385` (the only dead-letter decision, inside `reclaimOrphans`)
- `README.md:331`; `website/…/recipes/queues.md:288`

- **Problem:**
  - `maxDeliveries` is checked only when orphaned claims are reclaimed, which is the crash path.
  - `requeue()` puts the item back under its original key, which is the head of the queue, without looking at `attempts`.
  - `claimHead` increments `attempts` without a cap.
- **Failure scenario:**
  - A payload always makes the handler throw, and the handler calls `item.requeue()`, exactly as the README's canonical consumer does.
  - The item is re-received immediately with attempt 2, 3, … N and is never dead-lettered. A single-consumer deployment makes no progress at all.
  - This contradicts the docs: "An item that keeps failing cannot be redelivered forever."
  - Only dead-lettering through lease revocation is tested (`WorkQueueTests.kt:140`).
- **Fix:** In `requeue()`, read `attempts/<id>`. If it is at or above `maxDeliveries`, move the item to `dlq/<id>` in the same guarded txn and return a distinct result. Alternatively, enforce the cap in `claimHead`. Add a test that requeues past `maxDeliveries`.

#### <a id="issue-17"></a>17. Work-queue `claimHead` lease-not-found retry is unbounded and ignores the deadline and `close()`
**High** · queue · concurrency / resilience · effort S · Confirmed
**Location:** `queue/DistributedWorkQueue.kt:336-366`; `common/SelfHealingKeepAlive.kt:87` (`currentLeaseId`)

- **Problem:**
  - The retry is `catch (e) { if (e.isLeaseNotFound()) { Thread.sleep(LEASE_HEAL_PAUSE_MS); continue } }`, with no check of the deadline or `closeCalled`.
  - `currentLeaseId` keeps returning the dead id after `close()` revokes the lease, and after healing emits `Failed`.
- **Failure scenario:**
  - **Heal abandoned:** under `ResilienceConfig.DISABLED`, `LeaseResilience.DISABLED`, or any bounded lease policy, the consumer lease expires once, `Failed` is emitted, and the lease is never re-granted.
    - From then on, every receive that finds an item spins at 4 Hz doing 3 RPCs per loop. It never returns or throws.
    - `receive(5.seconds)` blows straight through its timeout.
  - **After `close()`:** a thread parked in `awaitItem` wakes, reclaims its own revoked claims, and then spins forever.

  `DistributedMutex.kt:219-224` handles the same pattern correctly.
- **Fix:** Pass the deadline into `claimHead`. On each retry, check the deadline and `closeCalled`, and throw when the keep-alive isn't healing. Also check `closeCalled` at the top of each `receiveWithDeadline` iteration.
- **Related:** #18.

#### <a id="issue-18"></a>18. A work-queue `receive()` in flight across `close()` creates a lease after close, and its item can never be acked
**High** · queue · resource-leak · effort M · Confirmed
**Location:** `queue/DistributedWorkQueue.kt:134-144` (`doClose`), `:286` and `:299` (the close checks in `ack`/`requeue`), `:318-328`, `:528-536` (the lazy lease)

- **Problem:**
  - `doClose` closes only what the lazy delegates report as initialized.
  - `receiveWithDeadline` checks `closeCalled` once, on entry.
  - `ack` and `requeue` throw after `close()`.
- **Failure scenario:**
  1. A consumer that has never claimed anything parks on an empty queue.
  2. `close()` runs and does nothing, because nothing is initialized.
  3. An item arrives. The parked thread wakes, and `claimHead` initializes the lazy lease: a grant, a keep-alive, a healer thread and a sweeper, all after close.
  4. The claim succeeds and a `WorkItem` is returned, but `ack()` and `requeue()` throw "close() already called".
  5. The leaked keep-alive keeps the claim alive, so no sweep ever reclaims it. The item is invisible until the `Client` closes or the JVM exits.
- **Fix:**
  - Check `closeCalled` before touching the lazy lease, and again after its initializer returns. If closed, close the new keep-alive and throw.
  - Re-check `closeCalled` on each loop iteration.
  - Have `doClose` count down the active `awaitItem` latch, the same pattern as #52.
- **Related:** #17, #52.

#### <a id="issue-19"></a>19. `ServiceRegistry.close()` can block about 2 min, then throw part-way through and leave healers running
**High** · discovery · resource-leak / resilience · effort S · Confirmed
**Location:** `discovery/ServiceRegistry.kt:90-97` (`ServiceInstanceContext.close`), `:207-216` (`internalUnregisterService`, `doClose`); `discovery/ServiceDiscovery.kt:154`

- **Problem:**
  - `client.deleteKey(instancePath)`, which is commented "best-effort", runs without `rpc` and without a catch. It therefore uses `RpcResilience.DEFAULT`: 5 attempts of 30 s each.
  - `doClose` runs `serviceContextMap.forEach { internalUnregisterService(…) }` under the monitor.
  - PR #89 threaded `rpc` into the grant and revoke calls but missed this one.
- **Failure scenario:**
  1. etcd is unreachable at shutdown. The first context blocks for about 2.5 min, then throws `EtcdRecipeRuntimeException`.
  2. The throw aborts the `forEach`, so every remaining context keeps its keep-alive and healer.
  3. `closeCalled` is already true, so `close()` can't be retried.
  4. When etcd comes back, those "closed" instances keep renewing, or heal and re-register. The result is zombie registrations.

  `ServiceDiscovery.doClose` rethrows the exception.
- **Fix:**
  - Pass `resilience.rpc` into `ServiceInstanceContext`, and wrap the delete in `runCatching`: the revoke has already removed the key.
  - In `doClose`, close each context independently: `values.toList().forEach { runCatching { … }.onFailure(::recordException) }`.
- **Related:** #62, #31.

#### <a id="issue-20"></a>20. `PathChildrenCache` priming start swallows load failures and leaves a dead cache that reports healthy
**High** · cache · correctness / resilience · effort M · Confirmed
**Location:** `cache/PathChildrenCache.kt:130-153` (the start worker), `:193-215` (`loadDataAndStartWatcher`)

- **Problem:** In `BUILD_INITIAL_CACHE` and `POST_INITIALIZED_EVENT` modes, `loadDataAndStartWatcher` catches every `Throwable`, logs it and calls `recordException`. The `finally` block then still fires INITIALIZED and sets `startThreadComplete`.
- **Failure scenario:**
  - The app starts while etcd is unreachable for longer than the RPC budget. `start(true)` returns normally and `waitOnStartComplete()` returns true.
  - No watcher was ever created, so nothing will ever recover the cache.
  - `isHealthy()` is true and `connectionState` is CONNECTED.
  - `POST_INITIALIZED_EVENT` listeners get INITIALIZED with empty `initialData` and conclude the prefix is empty.

  `NodeCache.start()`, `ServiceCache.start()` and NORMAL mode all throw in this situation.
- **Fix:**
  - Store the failure.
  - In `start(mode, wait = true)`, throw `EtcdRecipeRuntimeException(cause)` after the wait.
  - With `wait = false`, transition to LOST so `isHealthy()` is false.
  - Don't fire INITIALIZED on failure, or retry the load under `resilience.watch.retryPolicy`.
- **Related:** #57.

#### <a id="issue-21"></a>21. Coroutine `withLock` / `withPermit` / `awaitAcquire` leak the hold when cancellation races a successful acquire
**High** · coroutines · concurrency / resource-leak · effort M · Confirmed (kotlinx `withContext` prompt-cancellation semantics)
**Location:** `coroutines/LockRecipesSuspend.kt:59-72` (`withLock`), `:78-93` (`withLock(timeout)`), `:101-104` (`awaitAcquire`, `awaitTryAcquire`), `:117-125` (`withPermit`)

- **Problem:**
  - `interruptibleOn(confined) { lock() }` is `runInterruptible`, which is built on `withContext`.
  - `withContext` has a prompt-cancellation guarantee: if the caller is cancelled by the time the result is dispatched back, the result is discarded and `CancellationException` is thrown.
  - The `try/finally` that unlocks is only entered after that call returns.
- **Failure scenario:**
  1. `withTimeout(5.seconds) { mutex.withLock { … } }` runs on a contended mutex.
  2. `lock()` succeeds on thread T just as the timeout fires.
  3. `CancellationException` is thrown from line 62, and unlock never runs.
  4. `confined.close()` kills T, while the `AcquisitionLease` keep-alive keeps renewing the lock.
  5. Unlock from any other thread throws `IllegalMonitorStateException`, because ownership is pinned to the dead T.

  Every contender in every process blocks until `mutex.close()`. The same happens with `withLock(timeout)` and with permits: `withPermit` loses capacity, and with `permits = 1` it is the same deadlock.
- **Fix:**
  - Record success inside the blocking block (`lock(); acquired.store(true)`).
  - On `CancellationException` with `acquired` set, run the undo under `NonCancellable` on the confined thread, then rethrow. For semaphores, call `release()` under `NonCancellable`.
  - Consider one shared `interruptibleAcquire(dispatcher, acquire, undo)` helper in `Bridges.kt`.
  - A deterministic MockK test: a mocked `lock()` that cancels the job, then `verify { unlock() }`. This fails today.
- **Related:** #22, #63.

#### <a id="issue-22"></a>22. Coroutine queue twins lose or strand items when cancellation races a successful take
**High** · coroutines · correctness · effort M · Window (b) is Confirmed; window (a) is Confirmed statically.
**Location:** `coroutines/QueueSuspend.kt:25-36`; `coroutines/WorkQueueSuspend.kt:51-62`; `website/…/coroutines/index.md`

- **Problem:**
  - `suspend fun AbstractQueue.receive() = etcdInterruptible { dequeue() }`.
  - Its KDoc says "Cancellation … leaves the queue intact — no item is consumed". But by the time `dequeue()` returns, it has already CAS-deleted the item, and `withContext` discards the result on a late cancellation (the same mechanism as #21).
  - `awaitReceive`'s KDoc ("without leaving an orphan claim") fails the same way.
- **Failure scenario:**
  - **Window (b):** `dequeue()` returns and cancellation lands before the coroutine resumes, so the item is gone for good.
    - For `awaitReceive`, `claimed/<id>` and a claim marker on the consumer's live lease are left behind. The sweeper skips the item while the marker exists, so it is invisible until the instance closes.
  - **Window (a):** an interrupt during the delete or claim txn escapes from `awaitRpc` as `InterruptedException`, while the txn may still commit (see #50).
- **Fix:**
  - Capture the result inside the block.
  - On `CancellationException` with a captured `WorkItem`, call `requeue()` under `NonCancellable`.
  - For plain queues, offer an `onUndelivered` callback, or re-enqueue under `NonCancellable`.
  - Rewrite both KDocs and the docs page to say delivery is at-most-once under cancellation, and point to `DistributedWorkQueue` for at-least-once.
- **Related:** #21, #50.

### Medium

#### <a id="issue-23"></a>23. Non-retriable RPC failures escape as a raw checked `ExecutionException`
**Medium** · common, discovery · api-design / java-interop · effort S · Confirmed
**Location:** `common/RpcRetry.kt:60-62` (`if (!e.isRetriableRpcFailure()) throw e`), `:75-88`, `:100-117` (`awaitRpc`); `discovery/ServiceRegistry.kt:124-134`, `:147-152`

- **Problem:** `retryRpc` rethrows non-retriable failures unchanged, and `awaitRpc` doesn't catch `ExecutionException` at all. Callers therefore receive a raw `java.util.concurrent.ExecutionException`. Because of #4, that is currently *every* status failure.
- **Failure scenario:**
  - A PERMISSION_DENIED, or a leader-change UNAVAILABLE on `registerService`'s establish txn, throws `ExecutionException` out of an API declared `@Throws(EtcdRecipeException::class)`.
  - Java callers can't catch it: `catch (ExecutionException)` is rejected by javac as "never thrown", and `catch (EtcdRecipeException)` doesn't match it.
  - This leaves PR #89's "report registration failures distinctly" incomplete.
- **Fix:** In `retryRpc` and `awaitRpc`, unwrap `ExecutionException`/`CompletionException` and throw `EtcdRecipeRuntimeException("$opName failed", cause)`. `isLeaseNotFound` walks the cause chain, so it keeps working, and `ServiceRegistry`'s existing catch then handles the failure.
- **Related:** #4, #43, #63.

#### <a id="issue-24"></a>24. A per-attempt timeout doesn't cancel the gRPC call, so timed-out writes can land after later ones
**Medium** · common · resilience / correctness · effort M · Plausible. The non-cancellation was verified in the jetcd and Failsafe sources; the reordering depends on the network.
**Location:** `common/RpcRetry.kt:73-88` (the `cancel(true)` comment); `common/KVExtensions.kt:34-36`, `:72-76`; `common/ChildrenExtensions.kt:123`

- **Problem:**
  - `cancel(true)` acts on jetcd's `.thenApply(resultConvert)` dependent stage. Cancelling a dependent stage reaches neither Failsafe nor the Vert.x gRPC call.
  - jetcd sets no deadline on KV calls, and `waitForReady` defaults to true.
  - The retry then sends a second copy while the first is still in flight.
- **Failure scenario:**
  1. Attempt 1 of `putValue(k, "A")` or `deleteKey(k)` is stuck on a half-dead subchannel and times out.
  2. Attempt 2 succeeds.
  3. The caller writes `k = "B"`, or recreates `k`.
  4. Attempt 1 is delivered late and silently reverts `B`, or deletes the new key.

  During an outage, every timed-out attempt, including health probes (#32), leaves a parked call that is replayed as a burst when the cluster recovers. A retried `deleteChildren` whose first attempt timed out but applied returns an empty "deleted keys" list.
- **Fix:**
  - Correct the comment.
  - Don't retry puts or deletes after a timeout. Retry only on statuses that mean "not applied", matching jetcd's own NoSafeRedo classification.
  - Consider a client-wide gRPC deadline via a `ClientInterceptor` in `withRecipeDefaults()`, so that a timeout really cancels the call.
- **Related:** #4, #51.

#### <a id="issue-25"></a>25. Interrupts are handled inconsistently by the RPC engine and swallowed by `leaseRevoke`
**Medium** · common · concurrency · effort S · Confirmed
**Location:** `common/RpcRetry.kt:57-59`, `:66` (the backoff sleep sits outside the try), `:100-117` (`awaitRpc`); `common/LeaseExtensions.kt:84-95` (`leaseRevoke` catches `Throwable`)

- **Problem:** Interrupts surface differently depending on where they land:

  | Where the interrupt lands | What the caller sees | Interrupt flag |
  |---|---|---|
  | During the RPC wait | `EtcdRecipeRuntimeException` | restored |
  | During the backoff sleep, or inside a transaction (`awaitRpc`) | raw checked `InterruptedException` | cleared |
  | Inside `leaseRevoke` (it catches `Throwable`, including `InterruptedException`) | nothing | cleared |

  APIs that declare `@Throws(InterruptedException)` therefore surface an interrupt as some other type when it lands mid-RPC.
- **Failure scenario:**
  - A worker is hit by `executor.shutdownNow()` inside `withLock`. `unlock`'s `leaseRevoke` swallows the interrupt, the worker loop never sees it, and shutdown hangs.
  - A recipe loop that catches `Throwable` loses an interrupt that lands during a backoff, and can't be stopped.
- **Fix:**
  - Move the sleep inside the try/catch.
  - Give `awaitRpc` the same conversion plus a re-interrupt.
  - In `leaseRevoke`, call `Thread.currentThread().interrupt()` again when the cause is an interrupt.
  - Pick one documented contract, such as "flag restored, `EtcdRecipeRuntimeException` with an `InterruptedException` cause", and test it.
- **Related:** #23, #63.

#### <a id="issue-26"></a>26. `connectionState` never leaves `SUSPENDED` after jetcd recovers a stream on its own
**Medium** · common, lock · correctness / api-design · effort S–M · Confirmed
**Location:** `common/WatchExtensions.kt:121-138`; `common/SelfHealingKeepAlive.kt:131-162`; `lock/AcquisitionLease.kt:67`

- **Problem:**
  - Transient watch errors (jetcd's `WatchImpl` reschedules) and keep-alive errors (`LeaseImpl` restarts the stream after 500 ms) emit `Suspended`.
  - The next successful response only clears a flag or increments a metric. Nothing reports the recovery.
  - `AcquisitionLease` also reports `Suspended(-1L, e)` instead of the real lease id, and records no keep-alive metrics.
- **Failure scenario:**
  - A network blip leaves a recipe SUSPENDED for the rest of its life. The mutex has no watch, so nothing ever moves it out.
  - A Curator-style listener that pauses work on SUSPENDED never resumes, and `connectionStateAsFlow` stays stuck.
  - The docs promise that "SUSPENDED → RECONNECTED is a blip that healed".
- **Fix:**
  - In `ResilientWatcher`, emit `Resubscribed(keyName, resumeRevision)` when a response arrives while `suspendedReported` is set.
  - In `SelfHealingKeepAlive` and `AcquisitionLease`, emit a "resumed" event on the first renewal after a `Suspended`. Either reuse `Restored(id, id)` or add a new subtype; a new sealed subtype breaks exhaustive `when` expressions in user code.
  - Use the real lease id, and add tests for both paths.
- **Related:** #10, #27, #74.

#### <a id="issue-27"></a>27. `connectionState` is last-writer-wins across independent streams, and notifications can arrive out of order
**Medium** · common, coroutines · correctness / concurrency · effort M · Confirmed
**Location:** `common/EtcdConnector.kt:142-176`, `:183` (`isHealthy`); `coroutines/ConnectionFlows.kt:42-43`

- **Problem:**
  - Every stream's events call `transitionTo(...)` unconditionally, and `isHealthy()` is `state != LOST && !closeCalled`.
  - A transition exchanges the state and then notifies listeners with no lock held.
  - `connectionStateAsFlow` reads the state before it registers its listener.
- **Failure scenario:**
  - (a) `LeaderSelector`'s participation heal ends in `Failed` (LOST), and then its leader watcher reports `Resubscribed` (RECONNECTED). `isHealthy()` is now true, although participation is gone for good. `DistributedBarrier`, the work queue, the semaphore and the read-write lock also report from several streams, so the same can happen to each of them.
  - (b) Two reporter threads can deliver `(LOST, SUSPENDED)` after `(SUSPENDED, LOST)`, so listeners and the conflated flow end up on the wrong final state.
  - (c) A transition that lands between `connectionStateAsFlow`'s snapshot and `addConnectionStateListener` is lost.
- **Fix:**
  - Track state per stream, keyed by watch key or lease, and expose the worst one as the aggregate. Or make a LOST that came from `Failed` sticky.
  - Serialize transition and notification together (see #10).
  - In the flow, register the listener first and use it only as a signal to re-read: `callbackFlow { … }.conflate().map { connectionState }.distinctUntilChanged()`.
- **Related:** #10, #26, #38.

#### <a id="issue-28"></a>28. The compaction marker is lost when a resync attempt fails
**Medium** · common · correctness / resilience · effort S · Confirmed
**Location:** `common/WatchExtensions.kt:251-282`

- **Problem:**
  - The compaction revision is derived from the last error: `val compactRevision = compactedRevisionOf(lastCause)`.
  - If `resyncWith()` then throws, `lastCause` becomes the GET error. The next attempt therefore skips the resync, resubscribes at the compacted `resumeRevision`, and emits `Resubscribed` (RECONNECTED) even though nothing was reconciled.
  - etcd cancels the watch for compaction again, and `startRecovery()` resets `attempt = 0` and `recoveryStart`.
- **Failure scenario:** A compaction combined with a flaky GET means caches never reconcile. A bounded `WatchResilience` policy never reaches `Failed`, and the loop runs indefinitely, reporting RECONNECTED in between.
- **Fix:** Keep a separate `pendingCompactRevision` that is cleared only after a successful resync. Don't reset the attempt budget when a recovery is already in progress.
- **Related:** #29, #58, #76.

#### <a id="issue-29"></a>29. Un-anchored watches lose events across a recovery, and createNotify advances the resume revision early
**Medium** · common · correctness / docs · effort S · Confirmed (jetcd `WatchImpl` source)
**Location:** `common/WatchExtensions.kt:64-69` (KDoc: "no events are lost or duplicated"), `:113`, `:196-205` (`advanceRevision`)

- **Problem:**
  - `resumeRevision` starts at `baseOption.revision`, where 0 means "current".
  - `advanceRevision` treats any response as progress, including the created notification: `max(resumeRevision, header.revision + 1)`.
  - The created response's header carries the current store revision C, not the requested start revision R.
- **Failure scenario:**
  - (a) An un-anchored `Client.watcher` or `watchAsFlow` that hasn't seen any events dies fatally and resubscribes at "current". Any PUTs made during the backoff are lost. The in-tree recipes work around this by re-probing, but library users aren't told to.
  - (b) A watch with `withRevision(R)` and createNotify jumps to C+1 before events R..C have been replayed. A fatal death mid-replay loses them.
- **Fix:**
  - Handle `isCreatedNotify` explicitly: set `resumeRevision` from it only when it is still 0.
  - When the watch is un-anchored, force createNotify internally and filter the created response out of `block` if the caller didn't ask for it.
  - Correct the KDoc.
  - Consider dropping events with `modRevision < resumeRevision`, to cover jetcd's own transient resume.
- **Related:** #34, #49, #76.

#### <a id="issue-30"></a>30. `putValuesWithKeepAlive` never revokes its lease, and its multi-key puts aren't atomic
**Medium** · common · correctness / docs · effort S · Confirmed
**Location:** `common/KeepAliveExtensions.kt:87-116`; `common/LeaseExtensions.kt:33-37`; `website/…/lease.md`, `LeaseSnippets.kt:81-83`

- **Problem:**
  - When the block ends, only the keep-alive registration is closed. The lease isn't revoked, so the keys live up to one TTL longer.
  - The keys are written with sequential puts, not in one transaction.
  - There is no `rpc` parameter.
- **Failure scenario:**
  - `putValueWithKeepAlive("/services/worker-1", …, 60)` stays discoverable for up to 60 s after the block returns or throws.
  - A reader can see half of a multi-key registration.
  - The docs say "the key lives exactly as long as the block" and that the keys "appear together".
- **Fix:** Use `try { keepAliveWith(...) } finally { leaseRevoke(lease, rpc) }`, write the puts in one transaction, and add a trailing `rpc` parameter.
- **Related:** #31.

#### <a id="issue-31"></a>31. The RPC budget isn't threaded through several helpers and call sites
**Medium** · common, discovery, election, counter · resilience · effort S · Confirmed
**Location:**
- Helpers with no `rpc` parameter: `common/ChildrenExtensions.kt:90-95` (`getChildrenValues`), `common/KVExtensions.kt:70` (`deleteKeys`), `common/KeepAliveExtensions.kt:87-116`
- Call sites that don't pass it:
  - `discovery/ServiceDiscovery.kt:89` (`serviceCache()` ignores the config), `:125` (`queryForNames`), `:131` (`queryForInstances`)
  - `discovery/ServiceProvider.kt:92` (`getAllInstances`)
  - `election/LeaderSelector.kt:529` (`leaseRevoke`) and `getParticipants`
  - `counter/DistributedAtomicLong.kt:131` (the create txn)

- **Problem:** These calls use `RpcResilience.DEFAULT` (5 × 30 s, and none of the user's metrics) whatever the recipe's `ResilienceConfig` says.
- **Failure scenario:**
  - A recipe configured for a 1–2 s budget, or for DISABLED, still waits about 2.5 min in `queryForInstances`, while holding the `ServiceDiscovery` monitor (`@Synchronized`). The same happens on the counter's first use.
  - None of those RPCs reach the configured `EtcdMetrics`.
  - `LeaderSelector`'s 30 s revoke delays `close()` during a partition and widens the window for #35.
- **Fix:**
  - Add a trailing `rpc: RpcResilience = RpcResilience.DEFAULT` parameter to the three helpers, and pass `resilience.rpc` at every call site listed above.
  - Pass `resilienceConfig` in `serviceCache()`, and drop the docs caveat about it.
  - A cheap lint would catch regressions: grep recipe code for extension calls made without `rpc`.
- **Related:** #19, #30, #32.

#### <a id="issue-32"></a>32. `ping()` and the Spring health indicator can block about 150 s, and RBAC-scoped clusters always report DOWN
**Medium** · common, spring · api-design / resilience · effort S · Confirmed
**Location:**
- `common/ClientExtensions.kt:111-113`; `common/EtcdConnector.kt:185-191`; `common/RpcResilience.kt:40-42`
- `etcd-recipes-spring-boot-starter/…/EtcdHealthIndicator.kt:33`, `EtcdAutoConfiguration.kt:50-57`; `website/…/integrations/spring-boot.md:157-158`

- **Problem:**
  - `Client.ping(rpc = RpcResilience.DEFAULT)` uses `bounded(4)`, which means 5 attempts of 30 s each, and `waitForReady` parks each attempt for the full timeout.
  - Any exception returns false.
  - The health indicator calls `ping()` with the default, and it has no `@ConditionalOnEnabledHealthIndicator`.
- **Failure scenario:**
  - During an etcd outage, every `/actuator/health` call hangs for about 2.5 min. That blocks the aggregate endpoint and piles up servlet threads.
  - `management.health.etcd.enabled=false` has no effect.
  - With prefix-scoped RBAC, the GET on `health-check-probe` gets PERMISSION_DENIED, so health is permanently DOWN even though etcd is reachable.
- **Fix:**
  - Default `ping` to a single attempt with about a 2 s timeout, and add `EtcdConnector.ping(rpc)`.
  - Treat any server status reply (PERMISSION_DENIED, NOT_FOUND) as "reachable", or probe via the maintenance `statusMember` call or a configurable key.
  - Add an `etcd.recipes.health.timeout` property and `@ConditionalOnEnabledHealthIndicator("etcd")`.
  - Document the bound.
- **Related:** #24, #70.

#### <a id="issue-33"></a>33. Unit tests mock jetcd using the library's own assumptions, so resilience bugs can't surface
**Medium** · tests · tests · effort M · Confirmed
**Location:** `etcd-recipes-core/src/test/kotlin/io/etcd/recipes/common/RpcRetryTests.kt`, `SelfHealingKeepAliveTests.kt`, `ResilientWatcherTests.kt`, `ConnectionStateTests.kt`

- **Problem:** No test anywhere uses `StatusRuntimeException`. The retry tests build an `EtcdException` through `EtcdExceptionFactory`, which is exactly why #4 went unnoticed. These behaviors have no tests:
  - establish throwing during a heal (#8);
  - a resync failure after compaction (#28);
  - a transient suspension followed by jetcd's own recovery (#26);
  - an interrupt during the backoff (#25);
  - a LOST state overwritten by another stream (#27).
- **Fix:**
  - Build failures the way jetcd does: `CompletableFuture.failedFuture(Status.X.asRuntimeException())`.
  - Add one targeted test per item above.
  - Extend the container fault tests (etcd pause, leader change) to cover `ServiceRegistry` and the barriers, not only `TransientKeyValue`.
- **Related:** #4, #8, #9, #25–#28.

#### <a id="issue-34"></a>34. Leader-key watches are not revision-anchored, so a hand-off during setup is missed
**Medium** · election, coroutines · correctness · effort S · Plausible. The logic is confirmed, and jetcd's async watch create was verified in source.
**Location:** `election/LeaderSelector.kt:218`, `:251-268`, `:292-297`; `election/LeaderObserver.kt:94-116`; `coroutines/ElectionFlows.kt:80`, `:104-105`; `website/…/flows.md`

- **Problem:**
  - `LeaderSelector` watches with `watchOption { withNoPut(true) }` and no revision. It sets `watchStarted` as soon as `watcher()` returns, but jetcd only sends the create request later.
  - `LeaderObserver` and `leadershipAsFlow` seed their state with a GET, discard its revision, and then watch with `WatchOption.DEFAULT`.
  - The barriers already fixed exactly this class of bug (`DistributedBarrier.kt:185-199`); the election recipes never got the fix.
- **Failure scenario:**
  - **Selector:** candidate B loses the CAS at revision R. A leader with a short term revokes at R+1, and B's watch registers at R+2. B never sees the DELETE and stands by forever. At a simultaneous cluster start with a one-shot leader, nobody leads.
  - **Observer and flow:** a hand-off between the seed read and the watch going live leaves the observed leader stale until the next hand-off, possibly hours later.
- **Fix:** In all three, probe the leader key (a GET or txn header) and anchor the watch with `withRevision(rev + 1)`. In `leadershipAsFlow`, use `awaitGetResponse` and emit from that response.
- **Related:** #14, #29, #65.

#### <a id="issue-35"></a>35. Step-down leaves `attemptLeadership` set, so a DELETE can start a second concurrent term
**Medium** · election · concurrency · effort S · Plausible
**Location:** `election/LeaderSelector.kt:450`, `:494-496`, `:526-540`, `:566-579`

- **Problem:** `stepDownFromLeadership` clears `electedLeader`, but `attemptLeadership` is reset only in the old term's `finally`. Phase 1's guard is `if (isLeader || !attemptLeadership.get()) return false`, so it lets a new attempt through in between.
- **Failure scenario:**
  1. The node won at `start()`, so its term runs on the start worker.
  2. The lease is lost, and the node steps down.
  3. The old term is still unwinding: it is slow to react to the interrupt, it is relinquishing, or it is inside a 30 s `leaseRevoke` during a partition.
  4. The dispatcher processes the replayed DELETE. Phase 1 passes and the CAS wins.
  5. `takeLeadership` runs again, concurrently, on the dispatcher, and overwrites `leadershipThreadRef` and `leaseLostDuringLeadership`.
  6. The old term's `finally` then clears the state that now belongs to the new term.

  The new term holds the key with a live keep-alive, but it is invisible (`isLeader` is false), it can't be stepped down, and `start()` has been re-enabled.
- **Fix:**
  - Set `attemptLeadership` to false in `stepDownFromLeadership`.
  - Bail out of Phase 1 if `leadershipThreadRef` is non-null.
  - Ignore keep-alive events whose `leaseId != leadershipLeaseId`.
  - Clear `leadershipThreadRef` before relinquishing.

  #36's restructuring makes all of this unnecessary.
- **Related:** #14, #36.

#### <a id="issue-36"></a>36. A term won via the watch runs on the watch dispatcher, so `close()` can return while it still holds the key
**Medium** · election · concurrency / resource-leak · effort M · Confirmed
**Location:** `election/LeaderSelector.kt:257-268`, `:361`; `common/WatchExtensions.kt:165-170`

- **Problem:**
  - The DELETE callback runs all of Phase 2, including `takeLeadership`, on the `ResilientWatcher` dispatcher.
  - `close()` goes through `terminateWatch` to the `ResilientWatcher` close, whose `awaitTermination(5 s)` gives up with a warning.
  - `startThreadComplete` is then set even though the term is still running.
- **Failure scenario:**
  - The node was elected via DELETE, and `takeLeadership` does 60 s of work without parking on `waitUntilFinished`.
  - `close()` returns after about 5 s, while `isLeader` is still true and the leader key and keep-alive are still held, so successors stay blocked.
  - The user may then close the `Client` while leader code is still running.
  - When the win came via the start worker instead, `close()` waits for the term, so the two paths behave differently.
  - A term run on the dispatcher also has no MDC (#79).
- **Fix:** Have the watch and recovery callbacks only signal the start worker that the leader key is gone. The start worker runs attempts in a loop, so every term runs on one executor thread that `close()` waits for. This also removes #35's re-entry and gives #14 its retry loop.
- **Related:** #13, #14, #35.

#### <a id="issue-37"></a>37. A `LeaderSelector` closed without ever winning can't be restarted
**Medium** · election · correctness / api-design · effort S · Confirmed
**Location:** `election/LeaderSelector.kt:164`, `:189-190`, `:205`, `:357-364`, `:535`

- **Problem:**
  - `startCallAllowed` is set to false by `start()` and back to true only in a winning term's `finally`. `doClose` never resets it.
  - `connectionState` also stays LOST across a reuse after a step-down.
- **Failure scenario:**
  - `start()` as a standby, then `close()`, then `start()` throws "Previous call to start() not complete". That contradicts the documented "reusable across terms".
  - The same happens after the start worker's Phase 1 has thrown.
- **Fix:** Reset `startCallAllowed` under `startCallLock` at the end of `doClose` and in the start worker's `finally`, and reset the connection state on restart. Add a test that reuses a selector that never won.

#### <a id="issue-38"></a>38. Composite recipes (`LeaderLatch`, `ServiceProvider`) hide the health of the recipes they wrap
**Medium** · election, discovery · resilience / api-design · effort S–M · Confirmed
**Location:** `election/LeaderLatch.kt:147-181`; `discovery/ServiceProvider.kt:78-85`

- **Problem:**
  - `LeaderLatch` never forwards its inner selector's recovery or lease events, or its listeners. It only bulk-copies exceptions when a term ends: `if (sel.hasExceptions) sel.exceptions.forEach { recordException(it) }`.
  - `ServiceProvider` builds a private `ServiceCache` with no recovery or exception listeners.
- **Failure scenario:**
  - A standby latch's watch is abandoned (`Failed`), or its participation lease expires. The term never ends, so the latch's `connectionState` stays CONNECTED, `isHealthy()` stays true, and its listeners never fire. `concepts.md` promises LOST in this case.
  - A provider's cache watch is abandoned. The provider still reports healthy while `getInstance()` serves a list that may be frozen for hours.
- **Fix:**
  - Forward exceptions and state or recovery events from each inner recipe: in `LeaderLatch.runTermLoop`, for each new selector; in `ServiceProvider.start`, before calling `cache.start()`.
  - Drop the end-of-term bulk copy.
  - This needs a protected state-reporting hook on `EtcdConnector`.
- **Related:** #27.

#### <a id="issue-39"></a>39. The uncommitted `DistributedBarrierWithCount` close-race fix leaves a pre-park window open
**Medium** · barrier · concurrency · effort S · Confirmed
**Location:** `barrier/DistributedBarrierWithCount.kt:145`, `:179-190`, `:197-218`, `:335-337` (working tree); tests `DesignFixesTests.kt:88-135` and `DistributedBarrierWithCountCloseRaceTests.kt`

- **Problem:** The raw-read change correctly removes the `checkCloseNotCalled` throws on the waiter and dispatcher threads. Three gaps remain:
  - **(a)** `close()` lands while the waiter is still in the ready-CAS txn or in `leaseGrant`, before the establish hook runs. `onCancel` sets `keepAliveClosed`, the hook declines, and `EstablishDeclinedException` is caught at `:214`. `waitOnBarrier` then throws a checked `EtcdRecipeException("Failed to set waitingPath")` with no cause, instead of returning false.
  - **(b)** `close()` lands between `checkCloseNotCalled` (`:145`) and `activeWaiter.store` (`:183`). It finds no hook to cancel, so the waiter registers and parks until its timeout, which is forever for the no-arg overload.
  - **(c)** There is a single `activeWaiter` slot, so when several waits run concurrently on one instance, all but the last are uncancellable.

  The new test closes only after `waiterCount ≥ 1`, so it misses window (a), despite its docstring ("no matter where the waiter has got to").
- **Fix:**
  - `catch (e: EstablishDeclinedException) { if (cancelled.get()) return false; throw EtcdRecipeException(…, e) }`.
  - After `activeWaiter.store(active)`, add `if (closeCalled.load()) active.onCancel()`.
  - Make `activeWaiter` a concurrent set, and cancel all of it in `doClose`.
  - Add a test that closes immediately after `waitOnBarrier` starts.
  - Replace the fully qualified `java.util.concurrent.atomic.AtomicReference` at `:256` (#87).
- **Related:** #40, #43, #86.

#### <a id="issue-40"></a>40. The count-barrier trip releases the tripper before the global release is committed
**Medium** · barrier · correctness · effort S–M · Plausible. The ordering is confirmed; the bad outcome needs a failed txn.
**Location:** `barrier/DistributedBarrierWithCount.kt:161-176` (`checkWaiterCount`)

- **Problem:** When the count reaches `memberCount`, `closeKeepAlive()` runs first: it deletes the tripper's own waiting key and unparks it. Only then does the non-retried txn that deletes `/ready` run.
- **Failure scenario:**
  - The `/ready` delete fails with UNAVAILABLE or a timeout during a blip.
  - On the dispatcher path, the exception is only logged and the tripper returns true. On the waiter path, it throws.
  - The other N-1 waiters see `/ready` still present and a count of N-1. Nothing re-triggers a check, so they park until their timeout, which is forever for the no-arg overload.
- **Fix:** Delete `/ready` first, guarded on the createRevision seen in the same check and retried on a retriable failure. Only then call `closeKeepAlive()`. On a final failure, call `recordException` and stay parked.
- **Related:** #41.

#### <a id="issue-41"></a>41. Count-barrier waiting keys aren't scoped to a generation
**Medium** · barrier · correctness · effort M · Plausible
**Location:** `barrier/DistributedBarrierWithCount.kt:118`, `:142`, `:166`, `:187-190`

- **Problem:** `getChildCount(waitingPath)` counts every key under `waiting/`, and `/ready` is simply re-created by the next arriver.
- **Failure scenario:**
  - N parties loop on one path. The tripper returns first and immediately calls `waitOnBarrier` again. It re-creates `/ready` and registers before the others have removed their keys, so the count is already ≥ N and it trips generation k+1 on its own.
  - The same happens to an over-subscribed arrival just after a trip, and to a waiter that missed its release during a compaction resync.
- **Fix:** Key waiters under the ready generation (`waiting/<readyCreateRevision>/<token>`) and count only that generation. Otherwise, document the barrier as one-shot per path.
- **Related:** #40.

#### <a id="issue-42"></a>42. `DistributedBarrier.setBarrier()` after `removeBarrier()` on the same instance silently fails
**Medium** · barrier · correctness / api-design · effort S · Confirmed
**Location:** `barrier/DistributedBarrier.kt:74`, `:111-112`, `:152-166`

- **Problem:**
  - `barrierRemoved` is a permanent per-instance flag, and the establish hook returns false whenever it is set.
  - The flag is also stored *after* `deleteKey`, so it doesn't guard the heal race its comment describes.
- **Failure scenario:** set → remove → set. The second `setBarrier()` gets `EstablishDeclinedException` and returns false, which the docs describe as "another client got there first". No barrier is left: waiters either pass straight through or block with no owner.
- **Fix:** Make the flag per `setBarrier()` call, captured by the hook, and set it before `keepAliveLease?.close()` in `removeBarrier()`.

#### <a id="issue-43"></a>43. A broad `catch (EtcdRecipeRuntimeException)` reports infrastructure failures as a lost CAS
**Medium** · barrier, election · correctness / resilience · effort S · Confirmed
**Location:** `barrier/DistributedBarrier.kt:121-126`; `barrier/DistributedBarrierWithCount.kt:214-218`; `election/LeaderSelector.kt:404-408`

- **Problem:**
  - Exhausting `leaseGrant`'s retries, a txn timeout, and an interrupted retry all throw `EtcdRecipeRuntimeException`.
  - These three sites catch that type and report it as "CAS lost".
  - PR #89 introduced `EstablishDeclinedException` for exactly this distinction, but applied it only to `ServiceRegistry`.
- **Failure scenario:**
  - A brief etcd blip after the presence check makes `setBarrier()` return false ("someone else holds it").
  - Count waiters throw `EtcdRecipeException("Failed to set waitingPath")` with no cause; the docs say that means a token collision.
  - An interrupt during the grant surfaces as `EtcdRecipeException` instead of `InterruptedException`, which feeds #63.
  - Participation failures lose their cause.
- **Fix:** Catch only `EstablishDeclinedException`. Let other failures propagate, or wrap them with the cause.
- **Related:** #23, #39, #63.

#### <a id="issue-44"></a>44. `close()` races in-flight lock and permit acquisitions
**Medium** · lock · concurrency / resource-leak · effort S · Confirmed
**Location:** `lock/DistributedMutex.kt:181-199`, `:315-333`; `lock/DistributedReadWriteLock.kt:222-238`, `:259-297`, `:400-417`; `lock/DistributedSemaphore.kt:230-246`, `:386-398`

- **Problem:**
  - The acquire paths run `checkCloseNotCalled`, then the lease-grant RPC, then `attempts += attempt`, with no re-check in between.
  - `doClose` drains holds first and aborts attempts second.
- **Failure scenario:**
  - **(a)** T is inside `leaseGrant` when `close()` runs. `close()` sees no holds and no attempts. T then registers and parks, and nothing ever aborts it. When the lock frees, `lock()` returns true on a closed recipe with a live keep-alive.
  - **(b)** T's lock completes. `doClose` snapshots `threadData`, which is still empty. T then publishes its hold and wins the HOLDING CAS before `doClose` reaches `attempts`. `close()` returns with the lock still held.
  - **(b′)** Mutex variant: `doClose` releases T's just-published hold and marks it dispossessed, but T's CAS still succeeds and clears the dispossessed entry. `lock()` returns true for a lock that has been released, and T's later `unlock()` throws `IllegalMonitorStateException`.
- **Fix:**
  - In all three `doClose` methods, abort attempts first (WAITING→DEAD, count down the latches), then drain holds.
  - In `acquire`, re-check `closeCalled` after registering the attempt and after a winning CAS; if closed, roll back and throw.
- **Related:** #18, #52.

#### <a id="issue-45"></a>45. `DistributedMutex` retries non-retriable lock failures forever, and `tryLock` reports them as a timeout
**Medium** · lock · correctness / resilience · effort S · Confirmed
**Location:** `lock/DistributedMutex.kt:217-226`

- **Problem:** The loop retries every failure: `catch (e: Exception) { recordException(e); …; Thread.sleep(LEASE_HEAL_PAUSE_MS); continue }`.
- **Failure scenario:**
  - etcd RBAC denies the role access to `lockPath`.
  - `lock()` never returns or throws. It grants and revokes about 4 leases a second and appends about 4 exceptions a second to the unbounded exceptions list (#75).
  - `tryLock(t)` returns false, which looks like ordinary contention.
- **Fix:** Retry only lease death (`isLeaseNotFound`, or the attempt being DEAD), "no leader", and `isRetriableRpcFailure()`. Rethrow anything else wrapped in `EtcdRecipeRuntimeException`. This depends on #4, so that the retriable classification is real.
- **Related:** #4, #75.

#### <a id="issue-46"></a>46. Semaphore `release()` frees another thread's live permit before consuming a lost one
**Medium** · lock · correctness · effort S · Confirmed
**Location:** `lock/DistributedSemaphore.kt:163-179` (`release`), KDoc `:80-82`, `PermitData.owner` at `:104` (never used)

- **Problem:** `release()` calls `holds.pollFirst()` before it consults `dispossessedCount`, and it ignores the owner recorded in `PermitData`.
- **Failure scenario:**
  1. `permits = 2`. One instance holds P1 (thread A) and P2 (thread B).
  2. P1's lease expires, and its slot goes to process C.
  3. A finishes and calls `release()`. That revokes P2, B's live permit, and returns true.
  4. Process D is admitted into P2's slot while B is still in its critical section.

  B, C and D now run concurrently, which is more than N, and B's own `release()` returns false. `withPermit` makes this common, because many coroutines share one instance. The KDoc's claim that "the matching release returns false" is wrong whenever more than one permit is held locally.
- **Fix:**
  - Release in this order: a live hold owned by the current thread; then a lost slot owned by the current thread (track lost slots per owner instead of a bare counter); only then fall back to any hold.
  - Alternatively, consume `dispossessedCount` before live holds, which errs toward under-issuing.
  - Fix the KDoc either way.
- **Related:** #64.

#### <a id="issue-47"></a>47. Java can't catch `InterruptedException` from the concrete `DistributedMutex` / `DistributedSemaphore` types
**Medium** · lock · java-interop · effort S · Confirmed (a `javac` probe)
**Location:** `lock/DistributedMutex.kt:104`, `:111`, `:120`; `lock/DistributedSemaphore.kt:139`, `:145`, `:153`; `lock/EtcdLock.kt:73`

- **Problem:** Only the `EtcdLock` interface declares `@Throws(InterruptedException::class)`, and Kotlin doesn't carry that to overrides. `javap` shows no `throws` clauses on the concrete classes.
- **Failure scenario:**
  - `DistributedMutex m; try { m.lock(); } catch (InterruptedException e) {}` doesn't compile: "exception InterruptedException is never thrown in body of corresponding try statement". The same goes for `s.acquire()`.
  - The website's Java snippets compile only because they declare `throws` at method level.
- **Fix:** Add `@Throws(InterruptedException::class)` to `DistributedMutex.lock` and `tryLock` (both overloads) and to `DistributedSemaphore.acquire` and `tryAcquire`. Optionally add it to `withLock` and `withPermit`.

#### <a id="issue-48"></a>48. `tryLock`/`tryAcquire` deadlines don't bound the setup and cleanup RPCs
**Medium** · lock · resilience / api-design · effort M · Confirmed
**Location:** `lock/DistributedMutex.kt:188-198`, `:224`; `lock/DistributedReadWriteLock.kt:241-257`, `:266`, `:302`; `lock/DistributedSemaphore.kt:249-267`, `:313`; `lock/WaiterSupport.kt:74`

- **Problem:** The deadline only applies to the wait itself. These all ignore it:
  - the lease grant (`retryRpc`: 5 × 30 s by default);
  - the transactions and GETs;
  - the `isKeyNotPresent` recheck;
  - the revoke in the `finally` block;
  - the 250 ms sleep.
- **Failure scenario:** During an etcd brownout, `tryLock(500.milliseconds)` on a request path takes about 2 min to return or throw. The KDoc says it returns "false when time ran out".
- **Fix:**
  - When a deadline is set, derive a per-call `RpcResilience` whose `operationTimeout` is `min(configured, remaining)` and whose retry policy stops at the deadline.
  - Cap the sleeps by the remaining time.
  - Or, document that the deadline covers only the wait.

#### <a id="issue-49"></a>49. The work queue's empty-wait watch isn't revision-anchored (the lost wakeup that PR #68 fixed elsewhere)
**Medium** · queue · concurrency · effort S · Confirmed
**Location:** `queue/DistributedWorkQueue.kt:466-482` (`awaitItem`); compare `queue/AbstractQueue.kt:116-122`, `:137`

- **Problem:**
  - The watch is `watchOption { isPrefix(true); withNoDelete(true) }` with no start revision, plus a pre-live poll.
  - Commit `0f5942a` (PR #68) and `QueueWatchRecoveryTests.kt:116-121` both establish that this combination loses PUTs that land while the watch is being established.
  - PR #68 fixed `AbstractQueue`, the barriers and the locks. The work queue, added earlier in PR #56, was missed; its design spec called for a waiter shared with `AbstractQueue`.
- **Failure scenario:**
  1. `claimHead` sees `items/` empty at revision R.
  2. The pre-live poll runs.
  3. A PUT commits before etcd has processed the watch create, so it is never delivered.
  4. The consumer sleeps for min(`sweepInterval` = 30 s, the delayed head, the deadline).

  `receive(10.seconds)` returns null while an item sits in the queue.
- **Fix:** Expose the head GET's `header.revision` and pass `withRevision(rev + 1)` in `awaitItem`. Better, factor out `AbstractQueue`'s waiter as the spec intended. Mirror the "anchors its PUT-watch" test in `QueueWatchRecoveryTests`.
- **Related:** #29.

#### <a id="issue-50"></a>50. An ambiguous take commit loses the item (plain queues) or strands the claim (work queue)
**Medium** · queue · correctness / docs · effort M · Plausible
**Location:** `queue/AbstractQueue.kt:69`, `:106`, `:123`, `:246-250` (`deleteRevKey`); `queue/DistributedWorkQueue.kt:343-362`

- **Problem:** Transactions are never retried. `awaitRpc` throws on a timeout (whose cancel doesn't un-send the request, #24), on an `ExecutionException`, or on an interrupt, while the delete or claim may still have committed.
- **Failure scenario:**
  - **Plain queue:** the delete-CAS commits but the response is lost. `dequeue`/`poll`/`tryDequeue` throws, and the item is gone. The docs only warn about dying *after* `dequeue` returns.
  - **Work queue:** the claim commits, but the caller gets an exception. `claims/<id>` sits on the healthy consumer lease, so `reclaimOrphans` skips it until that instance closes. This contradicts the promise "without leaving an orphan claim".
- **Fix:**
  - In `claimHead`, on failure: clear and remember the interrupt, then re-read `claims/<id>`. If the claim is ours (value = `clientId`, lease = `leaseId`), either return the `WorkItem` or release it via the requeue txn. Then rethrow, or restore the interrupt.
  - For `AbstractQueue`, document that an exception from a take may mean the item was consumed.
- **Related:** #22, #24.

#### <a id="issue-51"></a>51. Retried enqueue puts can duplicate an item that was already consumed
**Medium** · queue · correctness · effort S · Plausible
**Location:** `queue/DistributedQueue.kt:56`; `queue/DistributedWorkQueue.kt:177`, `:202`; `common/KVExtensions.kt:34-43`

- **Problem:** `putValue` goes through `retryRpc`, justified by the comment "values here are last-writer-wins, so a duplicate apply … is harmless". That isn't true for append-style unique keys such as queue items.
- **Failure scenario:**
  1. The first put commits, but the client sees a timeout (today) or UNAVAILABLE (once #4 is fixed).
  2. A watching consumer takes the item within milliseconds.
  3. The retry, about 250 ms later, recreates the key.
  4. A second consumer takes it.

  That is two deliveries from a queue whose take is supposed to go to exactly one consumer. If the item hasn't been consumed yet, the retry instead bumps its mod revision and moves it to the tail of the MOD-ordered FIFO.
- **Fix:** Enqueue with a non-retried, create-only txn (together with #3) and surface ambiguous failures to the caller. Or document enqueue as at-least-once under ambiguous failure.
- **Related:** #3, #4, #24.

#### <a id="issue-52"></a>52. `AbstractQueue` takes don't react to `close()`
**Medium** · queue · concurrency · effort S · Confirmed
**Location:** `queue/AbstractQueue.kt:94-127`, `:178-185` (there is no `doClose`)

- **Problem:**
  - `checkCloseNotCalled` runs only on entry.
  - The wait latch can only be released by a PUT, a recovery event or an interrupt.
  - `DesignFixesTests` fix #2 set the precedent that `close()` unblocks barrier waiters; queues don't follow it.
- **Failure scenario:**
  - A consumer thread runs `while (running) queue.dequeue()`, and shutdown calls `queue.close()`. On an empty queue the thread stays parked forever, which hangs `join` or `awaitTermination`.
  - If an item does arrive, it is deleted and handed to a closed instance.
- **Fix:**
  - Track the active waiter latches.
  - In `doClose`, record a close cause and count them down.
  - Throw from `waitForFirstChild` when woken by close.
  - Check `closeCalled` at the top of the `takeWithDeadline` loop.
- **Related:** #18, #44.

#### <a id="issue-53"></a>53. `enqueue(value, Duration.INFINITE)` writes a poison key that breaks every receive
**Medium** · queue · correctness · effort S · Confirmed
**Location:** `queue/DistributedWorkQueue.kt:196-202`, `:414-416`, `:427-432`

- **Problem:**
  - `readyAt = currentTimeMillis() + delay.inWholeMilliseconds` overflows to a negative number for `INFINITE`, whose `inWholeMilliseconds` is `Long.MAX_VALUE`.
  - Formatting with `%019d` then yields `"-922…"`.
  - `readyMillisOf` does `basename.substringBefore('-').toLong()`, which is `"".toLong()`, so it throws `NumberFormatException`.
- **Failure scenario:**
  - `'-'` sorts before `'0'`, so the bad key becomes the permanent head of `delayed/`.
  - `promoteMatured()` and `delayedHeadRemaining()` throw at the start of every `receive`/`tryReceive`, on every consumer.
  - `sweepSafely` hides the error at DEBUG (#89).
  - The queue is unusable until someone deletes the key by hand.
- **Fix:** Add `require(delay.isFinite())` and use overflow-checked arithmetic. Make `promoteMatured` and `delayedHeadRemaining` tolerate a basename they can't parse: move it to the DLQ and call `recordException`.

#### <a id="issue-54"></a>54. The orphan sweep issues one transaction per in-flight claim on every idle wake
**Medium** · queue · performance · effort S–M · Confirmed (arithmetic from the code path)
**Location:** `queue/DistributedWorkQueue.kt:232-238`, `:321-326`, `:373-394`

- **Problem:**
  - `reclaimOrphans` reads every `claimed/` payload, then calls `client.isKeyPresent("$claimsPath/$id")`, a txn, for each item.
  - It runs from `tryReceive` whenever the head is empty, and on every `receiveWithDeadline` iteration.
- **Failure scenario:**
  - With K idle consumers and N items in flight, one enqueue wakes all K. The K-1 losers each do a range read plus N txns.
  - For K = N = 50 that is about 2,500 txns per enqueue, or about 250k RPC/s at 100 enqueues/s.
  - A consumer polling with `tryReceive` pays N txns per poll.
- **Fix:** Take one keys-only range of `claims/` and one of `claimed/`, diff them in memory, and fetch payloads only for the orphans. Rate-limit the empty-path sweep per instance.

#### <a id="issue-55"></a>55. `enqueueAll`'s within-batch order relies on etcd's unstable sort
**Medium** · queue · correctness / docs · effort S · Plausible (depends on etcd's sort implementation)
**Location:** `queue/DistributedQueue.kt:59-73`; `queue/AbstractQueue.kt:102` (take by `SortTarget.MOD`); `website/…/queues.md`

- **Problem:**
  - All entries in a batch share one mod revision.
  - The take sorts by MOD with `limit(1)`. etcd sorts the whole range with Go's `sort.Sort` (pdqsort, which is not stable beyond 12 elements) and then truncates.
  - The docs claim the keys keep within-batch order equal to argument order.
- **Failure scenario:** On a queue with more than 12 items, a batch can come out as b, a, c. `QueuePollTests.kt:114` uses 3 items, where the sort degrades to insertion sort, which is stable, so the test passes.
- **Fix:** Break ties on the client. For example, fetch only the minimum-mod-revision entries (`withMinModRevision`/`withMaxModRevision` = M, key-ascending, limit 1). Add a test with more than 12 items.
- **Related:** #92.

#### <a id="issue-56"></a>56. `TransientKeyValue` parks an executor thread for its whole lifetime, and `start()` waits with no timeout
**Medium** · keyvalue · api-design / concurrency · effort M · Confirmed
**Location:** `keyvalue/TransientKeyValue.kt:73`, `:103-139`; `website/…/getting-started/concepts.md:173`

- **Problem:**
  - The submitted task waits on `keepAliveWaitLatch` until `close()`.
  - `start()` blocks on `keepAliveStartedLatch` with no bound.
  - `SelfHealingKeepAlive` already owns its own threads, so the parked thread does nothing useful.
- **Failure scenario:**
  - A user follows the docs' "pass your own pool" tip and shares a single-thread or small executor across instances. The second instance's task never runs, so its constructor (with `autoStart = true`) or `start()` hangs forever.
  - With a larger pool, each instance silently holds one thread for its whole life.
- **Fix:** Create the `selfHealingKeepAlive` synchronously in `start()`, or submit it without parking. Close the healer in `doClose()`, and drop the executor, or keep it only for dispatching listeners if needed.
- **Related:** #88.

#### <a id="issue-57"></a>57. `PathChildrenCache` fires INITIALIZED after the watch is live: out of order, a snapshot per listener, and a deadlock
**Medium** · cache · concurrency · effort S–M · Confirmed
**Location:** `cache/PathChildrenCache.kt:119-165` (`start`), `:137-151` (`finally`), `:210` (`setupWatcher`)

- **Problem:**
  - `setupWatcher(anchorRevision)` runs inside `loadDataAndStartWatcher`.
  - Only afterward does the `finally` block deliver INITIALIZED, on the executor thread. It also recomputes `initialDataVal = currentData` separately for each listener.
- **Failure scenario:**
  - **Stale listener state:** the anchored watch replays writes made after the snapshot, on the dispatcher thread.
    1. The executor computes `initialData` with x = v1.
    2. The dispatcher applies x = v2 and delivers CHILD_UPDATED(v2).
    3. INITIALIZED then arrives, and a listener doing `state = initialData` reverts to v1 for good.
  - **Double counting:** listeners can see CHILD_ADDED(x) before INITIALIZED, and then again inside `initialData`.
  - **Inconsistent snapshots:** different listeners get different snapshots.
  - **Deadlock:** `start()` is `@Synchronized` and waits in `waitOnStartComplete()` while holding the monitor. An INITIALIZED listener that calls `rebuild()`, `clear()` or `close()` deadlocks.
- **Fix:**
  - Build one immutable initial list from `resp.kvs`, and fire INITIALIZED inside `loadDataAndStartWatcher` *before* `setupWatcher`. The anchored watch replays everything after the snapshot, so nothing is lost.
  - Move the wait outside the synchronized section.
- **Related:** #20, #58, #95.

#### <a id="issue-58"></a>58. A compaction resync updates caches silently, so listeners and flows never see the gap's changes
**Medium** · cache, discovery · correctness · effort M · Confirmed
**Location:** `cache/PathChildrenCache.kt:312-324` (`reconcile`); `discovery/ServiceCache.kt:144-159`; `cache/NodeCache.kt:155-161`

- **Problem:**
  - `reconcile` does `cacheMap.keys.retainAll(fresh.keys); cacheMap.putAll(fresh)` without calling any listener. `NodeCache` just assigns `latestBytes`.
  - The only signal is a `Resynced` recovery event. Its KDoc says derived state "must have reconciled it in the resync hook", but that hook is private to the cache.
- **Failure scenario:**
  - A watch falls behind a compaction while instance A was removed, B was added and C was updated.
  - `currentData`/`instances` converge. But the `childEvent`, `cacheChanged` and `nodeChanged` listeners, and every `eventsAsFlow` collector, never hear about it.
  - Routing tables and connection pools derived from listener events stay wrong indefinitely.
  - The docs say compaction "re-syncs transparently", and `PathChildrenCacheResyncTests` asserts only on the map.
- **Fix:**
  - Give `reconcile` an `emitEvents` flag that computes the old-versus-fresh diff.
  - When called from `resyncWith`, which already runs on the dispatcher so ordering holds, emit REMOVED/ADDED/UPDATED. For `ServiceCache` that is DELETE/PUT; for `NodeCache`, CREATED/UPDATED/DELETED.
  - Keep the `start()` path silent.
  - Assert listener events in the three resync tests.
- **Related:** #28, #60.

#### <a id="issue-59"></a>59. One malformed or newer-schema service instance breaks discovery for the whole service
**Medium** · discovery · correctness / resilience · effort M · Confirmed
**Location:** `discovery/ServiceCache.kt:103`, `:112`, `:180-184`; `discovery/ServiceInstance.kt:55-58`

- **Problem:**
  - The cache stores raw bytes and decodes them on every read: `serviceMap.values.map { ServiceInstance.toObject(it) }`.
  - The wire format is `Json { encodeDefaults = true }`, without `ignoreUnknownKeys`.
  - The DELETE path decodes the previous value outside any try.
- **Failure scenario:**
  - A non-JSON value is written under `names/<svc>/` by etcdctl or another tool, or a newer library version writes JSON with any added field.
  - `instances` then throws on every call, so `ServiceProvider.getInstance()` and `queryForInstances` fail for *all* instances: a full discovery outage.
  - When that entry is deleted, `toObject` throws mid-batch. The remaining events in the same `WatchResponse` are never applied, even though the revision has already advanced, so they are never redelivered.
  - The error is only logged by `ResilientWatcher` and never reaches `recordException`.
  - PUT decoding also happens once per listener.
- **Fix:**
  - Decode once, on arrival, in both the watch block and `reconcile`. On failure, call `recordException` and skip the entry. Store the decoded `ServiceInstance`.
  - Move the DELETE decode inside a try.
  - Read with `ignoreUnknownKeys = true`.
  - Apply the same per-entry skip in `ServiceDiscovery.queryForInstances` and in `ServiceProvider`'s direct mode.

#### <a id="issue-60"></a>60. `PathChildrenCache.rebuild()` can permanently undo a watch event
**Medium** · cache · correctness / docs · effort M · Confirmed
**Location:** `cache/PathChildrenCache.kt:294-324`; `website/…/recipes/caches.md` ("can momentarily *un*-apply an event")

- **Problem:** `rebuild()`'s `reconcile` runs on the caller's thread. It applies a snapshot taken at revision R with `retainAll` and `putAll`, while the dispatcher is concurrently applying events after R to the same map.
- **Failure scenario:**
  1. During the rebuild's GET, key k is deleted at R+1. CHILD_REMOVED fires, and the dispatcher removes k.
  2. `putAll(fresh)` then puts k back.
  3. A deleted key gets no further events, so the phantom child stays in `currentData` forever.

  In the same way, an updated key can go stale, or a newly added key can disappear until it next changes. The comment's "last-writer-wins / ordering only" reasoning is wrong, and so is the docs' "momentarily".
- **Fix:** Track `lastAppliedRevision` under a small lock shared by the watch block and `reconcile`. Apply the snapshot only if `lastAppliedRevision ≤ snapshotRevision`; otherwise re-run the GET. Fix the docs.
- **Related:** #58.

#### <a id="issue-61"></a>61. `ServiceProvider` error counts never reset, and down-entries accumulate
**Medium** · discovery · correctness / resource-leak · effort S · Confirmed
**Location:** `discovery/ServiceProvider.kt:115-132`

- **Problem:**
  - `noteError` does `computeIfAbsent(instance) { DownEntry() }` and then `errors.incrementAndFetch()`, with no time window.
  - `isDown` removes an entry only when `downUntil` is set and has passed, and only for instances that are still being selected.
  - `down.remove(instance)` can delete an ejection that another thread has just re-armed.
- **Failure scenario:**
  - An instance with one sporadic failure a day is ejected for `downPeriod` every third day. Curator's DownInstanceManager resets the count after the timeout.
  - With k8s churn, every pod that ever had an error keeps a `DownEntry` until `close()`.
- **Fix:**
  - Record `firstErrorAt` and reset `errors` when the window exceeds `downPeriod`.
  - In `availableInstances()`, prune entries whose instance is gone or whose window has expired.
  - Use `down.remove(instance, entry)`.

#### <a id="issue-62"></a>62. Re-registering after `LeaseEvent.Failed` leaks the old healer
**Medium** · discovery · resource-leak · effort S · Confirmed
**Location:** `discovery/ServiceRegistry.kt:137`

- **Problem:** `serviceContextMap[service.id] = context` overwrites the previous context without closing it.
- **Failure scenario:**
  - After `Failed`, the docs say "the instance is genuinely gone from etcd until you re-register it".
  - `registerService(sameInstance)` succeeds and replaces the context, but the old `SelfHealingKeepAlive`'s scheduled executor thread is never shut down. One thread leaks per failure/re-register cycle.
  - If the key was deleted out-of-band while the old lease is still alive, that lease is renewed forever and `close()` never revokes it.
- **Fix:** `serviceContextMap.put(service.id, context)?.let { runCatching { it.healer.close() } }`. Close only the healer: `context.close()` would delete the fresh registration.
- **Related:** #9, #19.

#### <a id="issue-63"></a>63. `interruptibleOn` misses re-wrapped interrupts, so some cancellations surface as `EtcdRecipeException`
**Medium** · coroutines · correctness / api-design · effort S · Confirmed
**Location:** `coroutines/Bridges.kt:41-54`; affected callers `coroutines/DiscoverySuspend.kt:24-25` (`awaitRegisterService`) and `coroutines/BarrierSuspend.kt:45`, `:48`

- **Problem:**
  - The bridge only catches `EtcdRecipeRuntimeException`, and converts it to `CancellationException` when there is an `InterruptedException` in its cause chain.
  - `ServiceRegistry.registerService` re-wraps the interrupted grant failure in the checked `EtcdRecipeException`, which the catch never sees.
  - `DistributedBarrierWithCount` throws a new exception with no cause at all.
- **Failure scenario:**
  - `withTimeout(5.seconds) { sd.awaitRegisterService(inst) }` runs while etcd is unreachable. The caller gets `EtcdRecipeException("Service registration failed …: leaseGrant(...) interrupted")` instead of `TimeoutCancellationException`.
  - Under `launch { … }; job.cancel()`, this becomes an uncaught error on routine shutdown, which is exactly what the bridge's docs promise never happens.
  - `barrier.await()` fails the same way, with the cause-less "Failed to set waitingPath".
- **Fix:**
  - In `interruptibleOn`, let `CancellationException` through and catch every other `Exception`.
  - If `!currentCoroutineContext().isActive`, throw a `CancellationException` with the original as its cause. The KDoc claims this check is racy; that looks wrong, because `JobSupport` flips to cancelling before it delivers the interrupt. Confirm with a test.
  - Keep the cause-chain walk as a secondary signal, and always attach the original cause.
  - Fixing #43 removes the cause-less case.
- **Related:** #21, #23, #43.

#### <a id="issue-64"></a>64. `interruptOnPermitLoss` / `interruptOnLockLoss` misfire under the suspend surface
**Medium** · coroutines · concurrency / api-design · effort M · Confirmed
**Location:** `coroutines/LockRecipesSuspend.kt:59-72`, `:96-101`, `:117-125`; `lock/DistributedSemaphore.kt:227`, `:381`; `lock/DistributedMutex.kt:301`; `lock/DistributedReadWriteLock.kt:395`

- **Problem:**
  - (a) `awaitAcquire` runs `acquireInternal` on a `Dispatchers.IO` worker, so the recorded owner is a pooled thread that goes on to run other coroutines.
  - (b) For the mutex and read-write lock, the owner is the confined thread, which sits idle while `action` runs in the caller's coroutine. `isHeldByCurrentThread` and `holdCount` are keyed by thread.
- **Failure scenario:**
  - **(a) Semaphore:** on permit loss, the lease callback interrupts whatever that IO worker is running *now*.
    - A victim coroutine's `etcdInterruptible` RPC fails with a wrapped `InterruptedException`, and `interruptibleOn` turns it into `CancellationException` even though that coroutine was never cancelled. A `launch`ed job silently disappears.
    - Non-coroutine NIO code on the same pool gets `ClosedByInterruptException`.
    - Meanwhile the `withPermit` action that actually lost its permit keeps running.
  - **(b) Mutex and read-write lock:** the opt-in interrupt is a no-op, and the action has no per-hold way to observe the loss.
- **Fix:**
  - Run the semaphore acquire on a dedicated short-lived thread, as `confinedLockDispatcher` does for locks.
  - In `withLock`, run `action` alongside a watcher on `lockLostAsFlow` that cancels it when the hold is gone.
  - Otherwise, document that the interrupt options and the thread-keyed properties don't apply to suspend holders.
- **Related:** #46.

#### <a id="issue-65"></a>65. `leadershipAsFlow`: a blocking uncancellable GET, swallowed re-read failures, and a fixed RPC budget
**Medium** · coroutines · concurrency / resilience · effort S · Confirmed
**Location:** `coroutines/ElectionFlows.kt:74-88`

- **Problem:**
  - The initial read uses the blocking `getValue` (`retryRpc`) inside the `callbackFlow` producer, without `runInterruptible`.
  - The recovery listener's re-read runs on the watch dispatcher, inside `ResilientWatcher.emit`'s `runCatching`, so failures are only logged.
  - There is no `rpc` parameter.
  - With `capacity = 0`, the initial `trySendBlocking` is a nested `runBlocking`.
- **Failure scenario:**
  - Collecting on `Dispatchers.Main`, or on a single-thread dispatcher, while etcd is unreachable pins that thread for up to about 2.5 min. Cancelling the collector doesn't interrupt it.
  - After a `Resynced` (or a `Resubscribed` with `resumeRevision` 0), a failed re-read leaves the flow silently stale.
  - `capacity = 0` can deadlock a single-threaded collector dispatcher.
- **Fix:**
  - Use `awaitGetResponse(leaderKey, rpc = rpc)` and `send()` for the initial emit.
  - Add an `rpc` parameter.
  - On a failed recovery re-read, emit `WatchFailed` and `close(e)`, or retry.
  - Anchor the watch as described in #34.
- **Related:** #34, #66.

#### <a id="issue-66"></a>66. A terminal watch failure never completes the watch and leadership flows
**Medium** · coroutines · correctness / api-design · effort S · Confirmed. It only triggers under DISABLED or a bounded `WatchResilience` policy.
**Location:** `coroutines/WatchFlowEvent.kt:82-85`, `:100-103`; `coroutines/ElectionFlows.kt:90-96`, `:124`

- **Problem:**
  - `ResilientWatcher` emits `Failed` and stops for good, but the flows close only in `awaitClose`, that is, when the collector cancels.
  - `watchEventsAsFlow`'s `transform` drops every `Recovery` element.
- **Failure scenario:**
  - `client.watchEventsAsFlow(key, resilience = WatchResilience.DISABLED).collect { … }`: the watched revision is compacted, `Failed` is emitted, and the collector suspends forever with no event and no exception.
  - `leadershipAsFlow` does emit `WatchFailed` ("observation has stopped for good"), but never completes, so `collect` hangs unless the user adds `takeWhile`.
- **Fix:**
  - `watchAsFlow`: call `close()` after sending `Recovery(Failed)`.
  - `watchEventsAsFlow`: throw `EtcdRecipeRuntimeException("watch abandoned", cause)` from the `transform`.
  - `leadershipAsFlow`: send `WatchFailed`, then `close()`.

#### <a id="issue-67"></a>67. The suspend RPC engine never records `EtcdMetrics.recordRpc`
**Medium** · coroutines · resilience / api-design · effort S · Confirmed
**Location:** `coroutines/RpcSuspend.kt:45-106` (nothing in `coroutines/` references metrics)

- **Problem:**
  - The blocking `retryRpc` and `awaitRpc` record metrics in a `finally` (`common/RpcRetry.kt:68-70`, `:114-116`).
  - Metrics (PR #70) landed after the suspend engine (PR #62) and were never ported to it.
  - `suspendAwaitRpc` also drops the `TimeoutException` cause that `awaitRpc` attaches.
- **Failure scenario:** `client.awaitPutValue(k, v, rpc = resilience.withMetrics(micrometer).rpc)` leaves the `etcd.rpc` timer and the `etcd.rpc.retries` counter at zero. The same is true for every coroutine user of the KV, children, txn, lease and lock twins. The docs say "The observable contract is the same" (`docs/coroutines/index.md`).
- **Fix:**
  - Mirror the blocking start/failed/try-finally recording in both suspend functions, counting cancellation as a failure as the blocking side does for interrupts.
  - Pass the `TimeoutException` as the cause.
  - In `recordRpc`'s KDoc, change "blocking RPC" to "RPC", and document that `opName` has the form `op(key)`; the Micrometer binding relies on that.
- **Related:** #4 (shares the retry predicate).

#### <a id="issue-68"></a>68. Every published artifact forces `logback-classic`, guava, and common-utils onto consumers
**Medium** · build · build · effort S · Confirmed for the dependency. The Spring failure is Spring's documented behavior.
**Location:** `build.gradle.kts:89-95`; `README.md:560`; `llms.txt:57`

- **Problem:**
  - The root block adds `logback-classic`, guava, common-utils (core and guava) and kotlin-logging as `implementation` to every module. All five 0.12.0 POMs therefore list `logback-classic` at runtime scope.
  - `core-utils-jvm` also pulls in `kotlin-reflect` and `kotlinx-datetime`.
- **Failure scenario:**
  - A Spring Boot app on `spring-boot-starter-log4j2` adds the starter. Spring's `LogbackLoggingSystem` takes precedence, so, depending on which SLF4J provider wins, startup either fails ("LoggerFactory is not a Logback LoggerContext but Logback is on the classpath") or silently loses the log4j2 configuration.
  - Any app on log4j2, reload4j or slf4j-simple gets duplicate-provider warnings.
  - The README and `llms.txt` claim "nothing beyond jetcd, coroutines, and logging", which is false.
- **Fix:**
  - Library modules should depend only on the logging API: kotlin-logging, which brings slf4j-api.
  - Move `logback-classic` to `testRuntimeOnly`, and to `runtimeOnly` in examples and test-runners.
  - Audit guava and common-utils usage in main source (#104).
  - Correct the docs.
- **Related:** #5, #99, #104.

#### <a id="issue-69"></a>69. The Ktor plugin closes its client on `ApplicationStopping`, before user teardown runs
**Medium** · ktor · correctness · effort S · Confirmed (Ktor 3.5.2 source)
**Location:** `etcd-recipes-ktor/src/main/kotlin/io/etcd/recipes/ktor/EtcdPlugin.kt:64-65`; `website/…/integrations/ktor.md:101-104`

- **Problem:**
  - The plugin subscribes `on(MonitoringEvent(ApplicationStopping)) { … close() }` at install time.
  - Ktor raises handlers in registration order.
  - The docs tell users to close long-lived recipes "on ApplicationStopping alongside the plugin's own teardown".
- **Failure scenario:**
  - Any user handler registered after `install(EtcdPlugin)` runs against a closed client.
  - `ServiceRegistry`, election, lock and lease recipes then can't revoke. Registrations and leader or lock keys linger until their TTL, which delays failover and keeps routing traffic to a dead instance.
- **Fix:** Close the plugin-owned client on `ApplicationStopped`. Add a test that a user `ApplicationStopping` handler can still use `etcdClient`, and update `ktor.md`.

#### <a id="issue-70"></a>70. RPC-backed Micrometer gauges can stall the metrics scrape during an outage
**Medium** · micrometer · correctness · effort S–M · Confirmed for the code path
**Location:** `etcd-recipes-micrometer/src/main/kotlin/io/etcd/recipes/micrometer/EtcdGauges.kt:42`, `:64`; `queue/AbstractQueue.kt:89`; `lock/DistributedSemaphore.kt:185`

- **Problem:** The queue-depth and available-permits gauges call `getChildCount(path, resilience.rpc)` inline, which can take up to 5 × 30 s with the defaults.
- **Failure scenario:**
  - A Prometheus or OTLP scrape evaluates gauges inline, so a single bound gauge can hold the scrape for minutes.
  - The scrape exceeds its timeout (Prometheus's default is 10 s), and the app's metrics vanish during the very incident they should show.
- **Fix:** Evaluate these gauges with a tight budget, which needs a core accessor overload that takes `rpc`, or serve a value cached by a background refresher. At minimum, document the outage behavior next to the existing "RPC per scrape" warning.
- **Related:** #32, #98.

#### <a id="issue-71"></a>71. A deadlocked test can hang CI for 45 min without identifying the test
**Medium** · tests / ci · tests · effort S–M · Confirmed for the unbounded waits. The skipped report upload is Plausible.
**Location:** `etcd-recipes-core/src/test/kotlin/io/etcd/recipes/common/TestExtensions.kt:115`; `io/kotest/provided/ProjectConfig.kt`; `.github/workflows/ci.yml:21`, `:64`

- **Problem:**
  - `blockingThreads` waits on `finishedLatch.await()` with no timeout. It has 21 call sites, and there are about 34 more timeout-less `await()` calls across 11 test files.
  - Kotest 6.2.4's default 10-minute timeout is coroutine-based, so it can't interrupt a thread parked in `CountDownLatch.await()`. `blockingTest` defaults to false, and `ProjectConfig` sets neither option.
  - The Gradle `Test` task has no timeout.
  - The CI job has `timeout-minutes: 45` and uploads reports only `if: failure()`.
- **Failure scenario:** A deadlock regression burns the full 45 minutes; the library has had several (see `LeaderSelectorCloseDeadlockTests`). The job-level cancellation then likely skips the `failure()` upload, so the hung class is never identified.
- **Fix:**
  - Give `blockingThreads` and the latches a deadline with a clear failure message.
  - Set `blockingTest = true` and a sane timeout in `ProjectConfig`.
  - Add `timeout.set(Duration.ofMinutes(30))` on the `Test` tasks.
  - Upload reports on `failure() || cancelled()`.
- **Related:** #101, #105.

### Low

#### <a id="issue-72"></a>72. `close()` from a watcher's or healer's own thread always stalls 5 s
**Low** · common · concurrency · effort S · Confirmed
**Location:** `common/WatchExtensions.kt:152-176`; `common/SelfHealingKeepAlive.kt:113-129`

- **Problem:** `dispatcher.awaitTermination(5, SECONDS)` can't complete while the caller is itself the task running on that executor.
- **Failure scenario:** A `PathChildrenCache` or `NodeCache` listener, or a lease or background-exception listener on the healer thread, calls `recipe.close()`. The call waits 5 s and logs a misleading "did not terminate" warning.
- **Fix:** Record the executor's thread in its `ThreadFactory`, and skip the await when `close()` is called from that thread.
- **Related:** #12, #36.

#### <a id="issue-73"></a>73. Closing the `Client` while a watcher is open makes it retry forever, silently
**Low** · common · resource-leak · effort S · Confirmed
**Location:** `common/WatchExtensions.kt:140-144`, `:251-282`

- **Problem:**
  - jetcd's `WatchImpl.close()` completes every watcher, which the library treats as a fatal death.
  - Re-watching then throws the closed-client exception.
  - That is retried forever under the default `exponentialBackoff`, with nothing logged.
- **Failure scenario:** If shutdown closes the `Client` before the recipes, the watcher thread wakes every 15 s or less, and the state stays SUSPENDED until the recipe is closed.
- **Fix:** Treat a closed client (or a CANCELLED status) as terminal and emit `Failed`. Log each failed attempt at debug level.

#### <a id="issue-74"></a>74. `keepAlive()` reports transient stream errors as "renewal stopped"
**Low** · common · docs / api-design · effort S · Confirmed
**Location:** `common/LeaseExtensions.kt:39-66`

- **Problem:**
  - Every `onError` logs "lease will expire on its TTL" at ERROR and calls `onKeepAliveError`.
  - But jetcd's `LeaseImpl` restarts the stream after 500 ms, and renewal continues. `SelfHealingKeepAlive`'s KDoc (`:46-47`) describes this correctly.
- **Failure scenario:** During an outage, ERROR logs and callbacks fire about every 500 ms. Callers who follow the docs tear down on a harmless blip.
- **Fix:** Report "gone" only on `onCompleted` or `isLeaseNotFound()`. Surface other errors at WARN through a separate `onTransientError`.
- **Related:** #26.

#### <a id="issue-75"></a>75. The `EtcdConnector` exception list grows without bound
**Low** · common · resource-leak · effort S · Confirmed
**Location:** `common/EtcdConnector.kt:39`, `:110`

- **Problem:** `exceptionList.value += throwable` has no cap.
- **Failure scenario:** Long-lived recipes accumulate full stack traces indefinitely. Frequent sources include:
  - transient keep-alive errors, about every 500 ms per lease during an outage;
  - codec decode failures;
  - listener exceptions;
  - retry-forever loops (#45).
- **Fix:** Keep only the last N entries (for example 100) plus a dropped counter, and document the cap.

#### <a id="issue-76"></a>76. The compaction fallback resumes one revision too late
**Low** · common · correctness · effort S · Confirmed (per etcd semantics)
**Location:** `common/WatchExtensions.kt:258`

- **Problem:** `resumeRevision = resyncWith?.invoke() ?: (compactRevision + 1)`. etcd's `compact_revision` is the minimum revision a watcher can still receive.
- **Failure scenario:** When there is no `resyncWith`, events at exactly `compactRevision` are skipped.
- **Fix:** Resume at `compactRevision`, and update `ResilientWatcherTests:184`.
- **Related:** #28, #29.

#### <a id="issue-77"></a>77. `Duration`-typed APIs hidden from Java (`EtcdRecipes.distributedPriorityQueue`, `LeaderLatch` `closeJoinTimeout`)
**Low** · common, election · java-interop · effort S · Confirmed (`javap`)
**Location:** `common/EtcdRecipes.kt:57-60`; the `election/LeaderLatch.kt` constructor that takes `closeJoinTimeout: Duration`

- **Problem:**
  - Kotlin's `Duration` value class mangles the factory's name to `distributedPriorityQueue-HG0u8IE(String, long)`, and there is no `@JvmOverloads`.
  - `LeaderLatch`'s constructor that takes `closeJoinTimeout` exists only as a private synthetic constructor.
- **Failure scenario:**
  - `DistributedPriorityQueue` has no Java-visible constructor either, so Java and Spring users can't obtain a priority queue at all.
  - Java code can't set `closeJoinTimeout`.
- **Fix:**
  - Add `@JvmOverloads`, which yields a plain `distributedPriorityQueue(String)`.
  - Add `java.time.Duration` or `(long, TimeUnit)` overloads.
  - Consider a `javap`-based check in CI, since this class of bug is invisible to Kotlin tests.

#### <a id="issue-78"></a>78. Passwords appear in `toString()`, and half-configured mTLS is silently ignored
**Low** · common, spring · security / api-design · effort S · Confirmed
**Location:** `common/EtcdConnectionConfig.kt:38-46`; `common/ClientExtensions.kt:71-77`; `etcd-recipes-spring-boot-starter/…/EtcdProperties.kt:31-34`

- **Problem:**
  - `EtcdConnectionConfig` and `EtcdProperties` are both data classes holding `password: String?`, so the generated `toString()` includes the password.
  - If only one of `clientCertPath` and `clientKeyPath` is set, the key manager is skipped with no error.
- **Failure scenario:**
  - Any log line or debug print of either object leaks the credential. Actuator masks configprops, but ordinary logs don't.
  - With half the mTLS config, the client silently connects without a client certificate.
- **Fix:** Override `toString()` to redact the password, or use a redacting wrapper type. Require both mTLS paths or neither.

#### <a id="issue-79"></a>79. Gaps in background logging context (MDC)
**Low** · cross-cutting · observability · effort S · Confirmed
**Location:**
- `cache/PathChildrenCache.kt:229-275`, `:327-343`
- `discovery/ServiceCache.kt:92-132`, `:161-178`
- `election/LeaderSelector.kt:257-264`, `:546-558`
- `election/LeaderObserver.kt:102`, `:149`
- `barrier/DistributedBarrier.kt` (`onBarrierLeaseEvent`)
- `barrier/DistributedBarrierWithCount.kt:293`
- `queue/DistributedWorkQueue.kt:148-154` (the sweeper)
- `common/SelfHealingKeepAlive.kt:183-244`
- `common/WatchExtensions.kt:240`, `:277`

- **Problem:** These watch blocks, recovery handlers and background tasks aren't wrapped in `withRecipeLoggingContext`. `NodeCache` is wrapped (`NodeCache.kt:98`, `:187`).
- **Failure scenario:** Their logs run on shared-name threads (`etcd-watch-dispatcher`, `etcd-lease-healer`, `workqueue-sweeper`), so they can't be attributed to a recipe. That includes any `takeLeadership` run on the dispatcher (#36).
- **Fix:**
  - Wrap each of these.
  - Pass an optional MDC context into `selfHealingKeepAlive` and `watcher`, or capture `MDC.getCopyOfContextMap()` at construction.
  - Include the queue path in the sweeper thread's name.

#### <a id="issue-80"></a>80. `DistributedAtomicLong` resilience gaps (no close check, uncapped backoff, undocumented ambiguous commits)
**Low** · counter · resilience / docs · effort S · Confirmed
**Location:** `counter/DistributedAtomicLong.kt:40-46`, `:107-124`

- **Problem:**
  - The CAS loop has no `closeCalled` check.
  - Its backoff is linear and uncapped: `sleep((count * 100).random().milliseconds)`.
  - A timed-out commit is ambiguous, but it just propagates as an exception, and nothing documents that the increment may already have been applied.
  - `withDistributedAtomicLong` has no `resilience` parameter.
  - (The create txn's missing `rpc` is covered in #31.)
- **Failure scenario:** A caller that retries after a timeout can double-count.
- **Fix:**
  - Cap the backoff, and check `closeCalled` in the loop.
  - Add KDoc stating that an exception means the outcome is unknown.
  - Add a `resilience` parameter to the factory.
  - Optionally, fold the GET into the txn's `Else(get)` to save one round trip per retry.
- **Related:** #15, #31.

#### <a id="issue-81"></a>81. Read-write lock and semaphore releases rely on a single, un-retried revoke
**Low** · lock · resilience · effort S · Confirmed
**Location:** `lock/DistributedReadWriteLock.kt:183`; `lock/DistributedSemaphore.kt:167`; `lock/AcquisitionLease.kt:86-90`

- **Problem:** Release is just `registration.close(); client.leaseRevoke(lease, rpc)`. `leaseRevoke` makes a single attempt and swallows any failure. The mutex, by contrast, first sends a retried unlock.
- **Failure scenario:** One UNAVAILABLE during release leaves the entry in place until its TTL runs out. With a user TTL of 60 s, every successor stalls for 60 s.
- **Fix:** Retry the revoke under `retryRpc` in `AcquisitionLease.close()`, or first call `deleteKey(entryKey, rpc)`, which is idempotent and retried, before revoking.

#### <a id="issue-82"></a>82. Lock-loss bookkeeping is keyed by thread, and `holdCount` is read cross-thread without synchronization
**Low** · lock · concurrency · effort S · Plausible (the windows are narrow)
**Location:** `lock/DistributedMutex.kt:74`, `:175-177`, `:288-289`; `lock/DistributedReadWriteLock.kt:87`, `:382-383`

- **Problem:**
  - Loss handling does `threadData.remove(thread) … dispossessed[thread] = data.holdCount`, and `holdCount` is a plain `var`.
  - After release, the attempt's phase stays HOLDING.
- **Failure scenario:**
  - A reentrant `lock()` that races a lock loss increments an orphaned `LockData`. `dispossessed` then records 1 hold instead of 2, and the outer `withLock`'s `unlock()` throws `IllegalMonitorStateException`, masking the real exception.
  - A stale fatal event from an already-released attempt can remove a newer hold held by the same thread.
- **Fix:** Use `threadData.remove(thread, attemptData)`, CAS the phase from HOLDING to DEAD in `releaseHold`, and make `holdCount` `@Volatile`.

#### <a id="issue-83"></a>83. Locks expose no fencing token, and the default lease TTL is 2 s
**Low** · lock · api-design · effort S–M · Confirmed
**Location:** `lock/DistributedMutex.kt:70-76`; `lock/DistributedReadWriteLock.kt:83-89`; `lock/DistributedSemaphore.kt:101-107`; `DEFAULT_TTL_SECS = 2`

- **Problem:** A lost lock is detected only on the client, after the server has already promoted the next waiter: the deadline is receive time plus TTL, checked on a 1 s tick.
- **Failure scenario:** A GC pause or network blip of about 1.3 s or more produces two concurrent holders until the old one notices. Downstream resources have no way to reject the stale holder.
- **Fix:**
  - Expose a `fencingToken` or `holderRevision`. For the mutex, use the `LockResponse` header revision or the ownership key's createRevision. For the read-write lock and semaphore, use the entry's createRevision, which is already computed.
  - Consider a longer default TTL for the lock recipes, and document the trade-off.

#### <a id="issue-84"></a>84. Election and barrier API traps (`close()` before `start()`, unused `clientId`)
**Low** · election, barrier · api-design · effort S · Confirmed
**Location:** `election/LeaderSelector.kt:358` (`doClose` calls `checkStartCalled`); `barrier/DistributedDoubleBarrier.kt:47-50`

- **Problem:**
  - Closing a `LeaderSelector` that was never started throws "start() not called". `LeaderLatch` and `LeaderObserver` don't do this.
  - `DistributedDoubleBarrier.clientId` is never passed to its inner barriers.
- **Failure scenario:** `withLeaderSelector {}` doesn't call `start()`, so a block that never starts the selector throws out of `use`.
- **Fix:** Drop `checkStartCalled` from `doClose`, and pass `clientId` through to the inner barriers.

#### <a id="issue-85"></a>85. `LeaderObserver.onRecovery` replays leadership on every recovery
**Low** · election · correctness · effort S · Confirmed
**Location:** `election/LeaderObserver.kt:149-156`, compared with `election/LeaderSelector.kt:622`

- **Problem:** Its comment says it mirrors the `LeaderSelector` code, but it lacks that code's `gapPossible` guard.
- **Failure scenario:**
  - Every lossless `Resubscribed` fires a duplicate `takeLeadership(sameLeader)`, followed by replayed older events.
  - A `readLeader()` failure there is swallowed by `emit` instead of reaching `listener.onError`.
- **Fix:** Add the same `gapPossible` guard, and route failures to `onError` plus `recordException`.

#### <a id="issue-86"></a>86. `DistributedBarrier.close()` doesn't unpark waiters, and its internal reads are close-checked
**Low** · barrier · api-design / concurrency · effort S · Confirmed
**Location:** `barrier/DistributedBarrier.kt:82-85`, `:218`, `:245`, `:264-268`

- **Problem:**
  - Unlike `DistributedBarrierWithCount`, `close()` never unparks a waiter.
  - After `close()`, the waiter's recovery re-probe and the recheck at `:218` call the close-checked `isBarrierSet()`, which throws.
- **Failure scenario:** On the dispatcher thread the release is lost; on the waiter thread the throw escapes. This is the same class of bug as the in-flight fix in #39.
- **Fix:** Use a raw read internally, and add a cancellation hook like `DistributedBarrierWithCount`'s.
- **Related:** #39.

#### <a id="issue-87"></a>87. Code-quality leftovers: Java atomics reintroduced, unused fields, stale `ElectionPaths`
**Low** · code-quality · code-quality · effort S · Confirmed
**Location and problem:**
- `lock/DistributedReadWriteLock.kt:98` and `barrier/DistributedBarrierWithCount.kt:256` use a fully qualified `java.util.concurrent.atomic.AtomicReference`, which sidesteps the move to `kotlin.concurrent.atomics` (PR #81).
- `barrier/DistributedBarrierWithCount.kt:97-101`: `ActiveWait.keepAliveClosed` and `cancelled` are unused.
- `barrier/DistributedBarrierWithCount.kt:312`: the redundant `deleteKey` sits outside `runCatching`, so an etcd error turns a timeout (`false`) into a throw. Its comment is stale.
- `election/ElectionPaths.kt`: `participantKey`, `participantsPath` and `leaderToken` are unused, and the "LeaderSelector is source-frozen" comment is stale. `LeaderSelector` duplicates the key scheme instead of using `ElectionPaths`.

- **Fix:**
  - Switch to `kotlin.concurrent.atomics.AtomicReference` with `load`/`store`.
  - Delete the unused members, or use them.
  - Move the `deleteKey` inside `runCatching`.
  - Make `LeaderSelector` use `ElectionPaths`.
  - A detekt `ForbiddenImport`/`ForbiddenMethodCall` rule for `java.util.concurrent.atomic` would stop this from recurring.

#### <a id="issue-88"></a>88. `TransientKeyValue`: retrying `start()` after a failure can leave a published key that can't be removed
**Low** · keyvalue · resource-leak · effort S · Confirmed
**Location:** `keyvalue/TransientKeyValue.kt:99-101`, `:139-159`

- **Problem:**
  - `startCalled` is set only on success.
  - The latches are one-shot.
  - `startupError` is never cleared.
  - `doClose` begins with `checkStartCalled()`.
- **Failure scenario:** With `autoStart = false` and a user executor:
  1. `start()` fails because etcd is down.
  2. The caller retries after etcd recovers. `start()` immediately rethrows the stale error, while the new task grants a lease, publishes the key and parks.
  3. `close()` throws "start() not called".
  4. The key stays published by the self-healing keep-alive for the life of the process.

  Separately, `close()` on an instance that was never started throws, which is awkward inside `use {}`.
- **Fix:** Either reject a second `start()` after any attempt, or reset the latches and error state for each attempt. Make `doClose` tolerate an instance that was never started.
- **Related:** #56.

#### <a id="issue-89"></a>89. Work-queue sweeper failures are dropped at DEBUG
**Low** · queue · resilience · effort S · Confirmed
**Location:** `queue/DistributedWorkQueue.kt:148-154`, `:396-406`

- **Problem:** The sweeper catches everything and logs at DEBUG: `catch (e: Throwable) { logger.debug(e) { "Reclaim sweep failed; next interval will retry" } }`. `CLAUDE.md` asks for `recordException`, not log-and-drop.
- **Failure scenario:** A persistent failure, such as PERMISSION_DENIED or the #53 `NumberFormatException`, silently disables background reclaim, while `exceptions` stays empty and `isHealthy()` stays true.
- **Fix:** Route failures to `recordException`, rate-limited if needed. (The MDC and thread name are covered in #79.)

#### <a id="issue-90"></a>90. `ack()`/`requeue()` are guarded on the instance's `clientId`, not on the specific claim
**Low** · queue · correctness / api-design · effort S · Confirmed
**Location:** `queue/DistributedWorkQueue.kt:285-308`, `:349`

- **Problem:** Both guards check `If(equalTo("$claimsPath/$id", value(clientId)))`. The `clientId` identifies the queue instance, not the particular claim.
- **Failure scenario:** One queue instance is shared by a pool of worker threads.
  1. Thread T1 holds item X.
  2. The instance's lease expires (a GC pause longer than the visibility timeout) and then heals.
  3. X is reclaimed, and thread T2 on the same instance receives it as attempt 2.
  4. T1's stale `ack()` returns true and deletes T2's claim, and T2's own `ack()` then returns false.

  That contradicts the promise that ack "returns false when the claim was lost". A stale `requeue()` can also pull the item away from T2.
- **Fix:** Carry a per-claim token in `WorkItem`, such as the claim txn's header revision (the marker's createRevision) or a random token stored in the marker value, and guard `ack`/`requeue` on it.

#### <a id="issue-91"></a>91. Queue metrics and small interop and docs gaps
**Low** · queue, keyvalue, micrometer · docs / java-interop · effort S · Confirmed
**Location:** `common/EtcdMetrics.kt:77` (the `recordQueue` KDoc); `queue/AbstractQueue.kt:63-72`, `:107`, `:124`; `queue/DistributedQueue.kt`; `queue/DistributedWorkQueue.kt`; `etcd-recipes-micrometer/…/MicrometerEtcdMetrics.kt:41`; `website/…/observability.md:28`, `:146`; `keyvalue/TypedTransientKeyValue.kt:77-81`; `common/TypedKVExtensions.kt:32-50`

- **Problem:**
  - `etcd.queue` is documented as tagged `op=enqueue|dequeue`, but only `"dequeue"` is ever recorded, and `tryDequeue` records nothing.
  - The work queue records no metrics at all: not for receive, ack or dead-lettering.
  - `TypedTransientKeyValue.start()`'s KDoc calls it a "no-op-safe delegate", but with the default `autoStart = true` it throws "start() already called".
  - The typed `putValue`/`getValue` extensions lack `@JvmOverloads`, unlike the rest of `KVUtils`.
- **Failure scenario:** Dashboards and alerts on `op="enqueue"` are always empty.
- **Fix:** Instrument `enqueue`/`enqueueAll` and the work queue, or correct the docs. Fix the KDoc, and add `@JvmOverloads`.

#### <a id="issue-92"></a>92. Head selection makes etcd read and sort the whole range (O(N²) drain)
**Low** · queue, common · performance · effort M · Plausible (depends on etcd server behavior)
**Location:** `queue/AbstractQueue.kt:66`, `:102`, `:170`, `:195` (three range reads per blocking take); `queue/DistributedPriorityQueue.kt:129`; `common/ChildrenExtensions.kt:65-79` (`getSingleChild`)

- **Problem:**
  - When a sort order is set, etcd fetches the whole range, values included, then sorts and truncates to the limit.
  - `getFirstChild` (MOD) and `getLastChild` (KEY, DESCEND) therefore scan the whole queue or priority bucket on every take.
  - `getSingleChild` sets `SortOrder.ASCEND` even for KEY, which likely defeats etcd's limit pushdown.
  - Separately, each queue or lock wait creates a fresh `ResilientWatcher` with its own dispatcher thread, so an idle fleet churns one thread per waiter per wake.
- **Failure scenario:** Draining a 100k-item backlog costs O(N²).
- **Fix:**
  - Select the head with a keys-only range, then fetch its value with a single-key GET.
  - Omit sort options for KEY/ASCEND so etcd pushes the limit down.
  - Consider a sequence counter for FIFO, as the priority queue already has.
  - Consider sharing a dispatcher across short-lived watchers.
- **Related:** #55.

#### <a id="issue-93"></a>93. `TypedPathChildrenCache`: one throwing listener stops the event reaching the rest
**Low** · cache · correctness · effort S · Confirmed
**Location:** `cache/TypedPathChildrenCache.kt:68-80`

- **Problem:**
  - `listeners.forEach { it.childEvent(typedEvent) }` has no per-listener try.
  - The untyped cache does isolate listeners, so the wrapper changes behavior, and `Typed*` wrappers are supposed to add none.
- **Failure scenario:**
  - When listener #1 throws, listeners #2..N never receive that event.
  - A single child that can't be decoded suppresses INITIALIZED for all typed listeners.
- **Fix:** Invoke every typed listener, collect the failures, and rethrow the first with the rest attached as suppressed, so the untyped cache still records it. Skip undecodable children individually.

#### <a id="issue-94"></a>94. Discovery lifecycle and naming traps
**Low** · discovery, cache · code-quality / docs · effort S · Confirmed
**Location:** `discovery/ServiceDiscovery.kt:87-126`; `cache/PathChildrenCache.kt:85-86`; `discovery/ServiceCache.kt:96`

- **Problem and failure scenario:**
  - `serviceCacheList` and `serviceProviderList` are never pruned. Every `withServiceCache`/`withServiceProvider` call leaves a closed object in a `CopyOnWriteArrayList` for the façade's whole lifetime.
  - `PathChildrenCache`'s internal `Executors.newSingleThreadExecutor()` creates a non-daemon thread, so an unclosed cache in a priming mode blocks JVM exit. Its "maintain order" comment is stale.
  - `queryForNames()` returns full instance keys, one per instance (`…/names/worker/AbC1234`), not service names. Curator users and the docs snippet both expect names.
  - `ServiceCacheListener`'s `serviceName` argument is actually `"name/id"`.
- **Fix:**
  - Remove caches and providers from the lists when they close.
  - Use a daemon thread factory.
  - Make `queryForNames` return distinct service names, or rename and document it.
  - Rename the listener parameter.

#### <a id="issue-95"></a>95. `eventsAsFlow().onStart { }` doesn't guarantee the cache listener is registered
**Low** · coroutines · docs · effort S · Confirmed
**Location:** `coroutines/CacheFlows.kt:43-46`; `website/…/coroutines/flows.md:195`

- **Problem:** `onStart` runs before upstream collection begins, while `callbackFlow`'s producer, which calls `addListener`, is launched as a separate coroutine. So `onStart` can run before the listener exists.
- **Failure scenario:**
  1. `launch { cache.eventsAsFlow().onStart { ready.complete(Unit) }.collect { … } }`
  2. `ready.await()`
  3. `cache.awaitStart(POST_INITIALIZED_EVENT)`

  INITIALIZED can be emitted before the listener is registered, and a collector that waits for it hangs. The tests use `delay(1_000)` to work around this.
- **Fix:** Recommend `awaitStart(BUILD_INITIAL_CACHE)` followed by `awaitStartComplete()` instead, or add an `onSubscribed` callback that runs right after `addListener`.
- **Related:** #57.

#### <a id="issue-96"></a>96. The docs claim every blocking call has a suspending twin, but several don't
**Low** · coroutines · docs / api-design · effort M · Confirmed
**Location:** `website/…/coroutines/index.md:3-4`

- **Problem:** The docs claim every network-waiting blocking call has a suspending twin. There is none for:
  - `LeaderLatch.start`, `await` and `await(timeout)` (an indefinite wait);
  - `NodeCache.start`;
  - `TypedPathChildrenCache.start` and `waitOnStartComplete`;
  - `TypedTransientKeyValue.start`;
  - `TypedDistributedQueue` and `TypedDistributedPriorityQueue` take/put;
  - `ServiceProvider.start`, `getInstance` and `getAllInstances`;
  - `LeaderObserver.start`;
  - the work queue's dead-letter operations.

  Most of these recipes (PRs #65, #76, #78) landed after the coroutine PRs.
- **Failure scenario:** A coroutine user who wraps `latch.await()` in `withContext(IO)` can't cancel it.
- **Fix:** Add the twins, or narrow the claim. The `Typed*` twins are one-line `etcdInterruptible` wrappers.

#### <a id="issue-97"></a>97. `withLock(timeout)`'s "null means not acquired" is false for a nullable `T`
**Low** · coroutines · api-design · effort S · Confirmed
**Location:** `coroutines/LockRecipesSuspend.kt:74-82`

- **Problem:** The KDoc says a null return always means the lock was not acquired. That only holds when the action itself can't return null.
- **Failure scenario:** `mutex.withLock(5.seconds) { cache[key] }` returns null both when the lock wasn't acquired and when it was acquired but the key is absent.
- **Fix:** Bound the type parameter as `<T : Any>`, which is source-incompatible for nullable callers but acceptable before 1.0, or return a result wrapper.

#### <a id="issue-98"></a>98. Micrometer gauge binders can report the wrong recipe, and the documented example binds to recipes it then closes
**Low** · micrometer · api-design / docs · effort S · Confirmed
**Location:** `etcd-recipes-micrometer/…/EtcdGauges.kt:48`, `:54`; `etcd-recipes-micrometer/src/test/kotlin/website/micrometer/MicrometerSnippets.kt:74-81`; `website/…/observability.md:169-170`

- **Problem:**
  - `bindCacheSize` and `bindServiceCacheSize` both register `etcd.cache.entries` with no distinguishing tag.
  - For a duplicate name and tags, Micrometer returns the existing meter, which is still bound to the first object.
  - There is no unbind helper.
- **Failure scenario:**
  - Binding both a `PathChildrenCache` and a `ServiceCache` silently reports the first one's value twice.
  - Re-binding after recreating a recipe keeps the stale gauge.
  - The published snippet binds inside `.use { }`, which leaves a gauge on a closed recipe that reads NaN once it is garbage-collected.
- **Fix:**
  - Use distinct names, or add a `recipe` tag.
  - Return a closeable binding, or document `registry.remove(gauge)`.
  - Fix the snippet, and add a collision test.
- **Related:** #70.

#### <a id="issue-99"></a>99. `EtcdProperties` binding depends on a transitive `kotlin-reflect`, and the starter's tests don't check binding
**Low** · spring · correctness / tests · effort S · Plausible (latent)
**Location:** `etcd-recipes-spring-boot-starter/…/EtcdProperties.kt:30-39`; `EtcdAutoConfigurationTests.kt:43-59`

- **Problem:**
  - Every constructor parameter has a default, so `javap` shows a public no-arg constructor alongside the primary one.
  - Spring then can't deduce the bind constructor, and falls back to `BeanUtils.findPrimaryConstructor`, which needs `kotlin-reflect`.
  - `kotlin-reflect` is present today only through `core-utils-jvm` (#68).
  - The tests assert only that beans exist. They check no bound values, no Actuator-absent case (`FilteredClassLoader`), and no missing-endpoints behavior.
  - No configuration metadata is generated.
- **Failure scenario:** If that transitive dependency goes away, or a consumer excludes it, `etcd.recipes.*` properties silently stop binding.
- **Fix:** Annotate the constructor with `@ConstructorBinding`, add binding and Actuator-absent tests, and optionally generate `spring-configuration-metadata.json`.
- **Related:** #68.

#### <a id="issue-100"></a>100. `TransientKeyValueTest` counts every key under `/keyvalue`, which other test classes write to in parallel
**Low** · tests · tests · effort S · Confirmed for the collision; the flake timing is Plausible
**Location:** `etcd-recipes-core/src/test/kotlin/io/etcd/recipes/keyvalue/TransientKeyValueTest.kt:58-91`

- **Problem:**
  - The test asserts `getChildCount("/keyvalue") shouldBe 0` and `shouldBe 25`.
  - `TypedTransientKeyValueTests` and `BugFixesTests.kt:322` write under `/keyvalue/…`.
  - `maxParallelForks` ≥ 2 runs test classes concurrently against the shared local etcd.
- **Failure scenario:**
  - `make tests` fails spuriously. CI doesn't see it because it uses one container per fork.
  - Keys left over from an aborted run also break the test.
- **Fix:** Namespace the path with `"/keyvalue/${javaClass.simpleName}"`, including `singleKVTest`'s path.

#### <a id="issue-101"></a>101. Some regression tests can't fail for the bug they target
**Low** · tests · tests · effort S–M · Confirmed
**Location:** `etcd-recipes-core/src/test/kotlin/io/etcd/recipes/barrier/DistributedBarrierWithCountWatcherTests.kt:72`, `:119`; `container/ContainerLeaderSelectorTest.kt:53-60`; `etcd-recipes-test-runners/…/ElectionParticipantRunner.kt:52`

- **Problem and failure scenario:**
  - The barrier watcher test sleeps 2 s before injecting the peer. If setup is slower (a loaded 2-core runner), the initial count already sees both waiters, so the watcher branch it targets is never exercised. The barrier is also never closed.
  - The container election test ("exactly once") checks only the `tookLeadership` and `relinquished` booleans, with a leadership callback that returns immediately. Overlapping leaders are undetectable.
  - Across the test tree there are about 57 fixed sleeps in 30 test files.
- **Fix:**
  - Replace the barrier test's sleep with `pollUntil { getChildCount("$path/waiting") == 1L }`, and close the barrier with `use`.
  - Have the election runners record take and release timestamps, or CAS an "active leader" key, and assert that terms don't overlap.
  - Burn down the fixed sleeps opportunistically.
- **Related:** #71.

#### <a id="issue-102"></a>102. The minimum Kotlin version for consumers (2.3+) is undocumented
**Low** · build / docs · build · effort S · Plausible (the standard N+1 metadata rule)
**Location:** `build.gradle.kts` (`-Xcollection-literals`); `README.md:505-507`

- **Problem:** Published classes carry Kotlin metadata version 2.4.0, and the main source uses collection literals. The README states only "Java 17+". Boot 4.1.x and Ktor 3.5.x are fine.
- **Failure scenario:** Kotlin consumers on 2.2 or older, such as Spring Boot 4.0 projects, get "incompatible metadata" compile errors.
- **Fix:** Document the minimum Kotlin version in the README and the getting-started page. Consider setting `-api-version`/`-language-version` to widen compatibility.

#### <a id="issue-103"></a>103. Documentation drift (`CLAUDE.md`, README, `llms.txt`, CHANGELOG, version string)
**Low** · docs · docs · effort S · Confirmed
**Location:** `CLAUDE.md:11`, `:23`, `:32`; `README.md:589`; `website/README.md`; `.github/workflows/docs.yml`; `etcd-recipes-core/src/test/kotlin/io/etcd/recipes/common/TestExtensions.kt:64`; `etcd-stop.sh:2-4`; `Makefile:53`; `llms.txt:65`; `CHANGELOG.md`; `gradle.properties`

- **Problem:**
  - `CLAUDE.md` says the Gradle wrapper is pinned to 9.6.1; it is 9.7.1.
  - `CLAUDE.md` says the tests use Kluent, which isn't a dependency.
  - `CLAUDE.md`, the README and the website README say `make docs-check` is "what CI runs". `docs.yml` runs only `zensical build`; the snippet compile happens in `ci.yml` via `check`.
  - The README says `./gradlew lintKotlin  # kotlinter + detekt`, but `lintKotlin` doesn't run detekt.
  - `TestExtensions`' error message, `etcd-stop.sh` and the Makefile refer to a nonexistent `./etcd.sh` / `make etcd`.
  - `llms.txt` claims every recipe has both Kotlin and Java demos. Java covers only five areas, and there is no Kotlin `TransientKeyValue` example.
  - CHANGELOG `[Unreleased]` is empty, although PR #89 added public API (`EstablishDeclinedException`, and a `cause` on `EtcdRecipeException`).
  - `gradle.properties` is still 0.12.0 on post-release master, so `publish-local` overwrites the released 0.12.0 in `~/.m2`.

  All intra-site links, anchors and version strings checked out.
- **Fix:** Correct each item. Bump to `0.12.1-SNAPSHOT` (or `0.13.0-SNAPSHOT`), and fill in `[Unreleased]`.

#### <a id="issue-104"></a>104. Most examples depend on helper libraries that consumers won't have
**Low** · examples / docs · docs · effort S–M · Confirmed
**Location:** 35 of the 52 files under `etcd-recipes-examples/src/main`, for example `java/…/basics/SetAndDeleteValue.java:19`

- **Problem:** The examples import `com.google.common.collect.Lists`, `com.pambrose.common.util.sleep`, `com.pambrose.common.concurrent.thread` and `MiscJavaFuncs.sleepSecs`. These are available only through the root-level `implementation` dependencies, which are runtime-only for consumers.
- **Failure scenario:** Examples copied into a user project don't compile. The doc-site snippets correctly avoid these libraries.
- **Fix:** Use the stdlib equivalents (`listOf`/`List.of`, `Thread.sleep`, `kotlin.concurrent.thread`). Once #5 and #68 scope dependencies per module, the examples module can no longer pick these up by accident.
- **Related:** #5, #68.

#### <a id="issue-105"></a>105. CI hygiene (token permissions, action versions, ungated docs deploy)
**Low** · ci · ci / security · effort S · Confirmed for the configuration; the impact is Plausible
**Location:** `.github/workflows/ci.yml` (no `permissions` block; `checkout`, `setup-java` and `upload-artifact` at `@v4`); `.github/workflows/docs.yml:35`, `:61-75`

- **Problem:**
  - `ci.yml` has no `permissions:` block, while `docs.yml` sets `contents: read`.
  - `ci.yml`'s actions are pinned to the Node 20 generation, while `docs.yml` uses `checkout@v7`.
  - The docs deploy runs on every push to master, regardless of `ci.yml`'s result.
- **Failure scenario:**
  - On older repos, the default token may be read/write.
  - A site whose snippets no longer compile can still be deployed.
- **Fix:** Add `permissions: contents: read`, align the action versions, and gate the deploy on CI (via `workflow_run`), or run the snippet compile in `docs.yml`.
- **Related:** #71.
