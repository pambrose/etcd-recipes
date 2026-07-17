# Spring Boot

`etcd-recipes-spring-boot-starter` turns a few lines of `application.yml` into a connected,
shared, gracefully-closed etcd client.

```kotlin
implementation("com.pambrose:etcd-recipes-spring-boot-starter:0.12.0")
```

That is the entire installation. There is no `@EnableEtcd`, no annotation to add, and
nothing to import: the jar ships a
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` naming
`EtcdAutoConfiguration`, so Boot finds it on the classpath and activates it.

## What gets auto-configured

```kotlin
@AutoConfiguration
@EnableConfigurationProperties(EtcdProperties::class)
@ConditionalOnClass(Client::class)
class EtcdAutoConfiguration
```

`@ConditionalOnClass(Client::class)` keeps the whole thing inert unless jetcd is actually
present, and every bean below is `@ConditionalOnMissingBean`:

| Bean | Type | Notes |
| --- | --- | --- |
| `etcdClient` | `Client` | `@Bean(destroyMethod = "close")` — closed on context teardown |
| `etcdRecipes` | `EtcdRecipes` | The factory from [Integrations](index.md#etcdrecipes), bound to `etcdClient` |
| `etcdHealthIndicator` | `HealthIndicator` | Only with Actuator present — see [below](#actuator-health) |

Because they are all conditional, defining your own bean of the same type makes the
starter back off and use yours — including for the beans built *on top* of it. Declare
your own `Client` and the auto-configured `EtcdRecipes` and health indicator will both
wrap it:

```kotlin
--8<-- "kotlin/website/spring/SpringSnippets.kt:client-bean"
```

## Configuration

`EtcdProperties` is constructor-bound and immutable, under the prefix `etcd.recipes`.
Relaxed binding applies, so `connect-timeout`, `connectTimeout`, and `CONNECT_TIMEOUT` all
land in the same place.

| Property | Type | Default |
| --- | --- | --- |
| `etcd.recipes.endpoints` | `List<String>` | *(empty — you must set this)* |
| `etcd.recipes.user` | `String?` | `null` |
| `etcd.recipes.password` | `String?` | `null` |
| `etcd.recipes.namespace` | `String?` | `null` |
| `etcd.recipes.connect-timeout` | `Duration` | `5s` |
| `etcd.recipes.retry-max-duration` | `Duration` | `30s` |
| `etcd.recipes.tls.ca-cert-path` | `String?` | `null` |
| `etcd.recipes.tls.client-cert-path` | `String?` | `null` |
| `etcd.recipes.tls.client-key-path` | `String?` | `null` |

=== "application.yml"

    ```yaml
    etcd:
      recipes:
        endpoints:
          - "http://etcd-1:2379"
          - "http://etcd-2:2379"
        # Prefixes every key this client touches. The recipes never see it.
        namespace: /myapp/
        user: orders-svc
        password: ${ETCD_PASSWORD}
        connect-timeout: 5s
        retry-max-duration: 30s
        tls:
          ca-cert-path: /etc/etcd/ca.crt
          client-cert-path: /etc/etcd/client.crt
          client-key-path: /etc/etcd/client.key
    ```

=== "application.properties"

    ```properties
    etcd.recipes.endpoints[0]=http://etcd-1:2379
    etcd.recipes.endpoints[1]=http://etcd-2:2379
    # Prefixes every key this client touches. The recipes never see it.
    etcd.recipes.namespace=/myapp/
    etcd.recipes.user=orders-svc
    etcd.recipes.password=${ETCD_PASSWORD}
    etcd.recipes.connect-timeout=5s
    etcd.recipes.retry-max-duration=30s
    etcd.recipes.tls.ca-cert-path=/etc/etcd/ca.crt
    etcd.recipes.tls.client-cert-path=/etc/etcd/client.crt
    etcd.recipes.tls.client-key-path=/etc/etcd/client.key
    ```

`endpoints` is the only property with no workable default. Everything else is optional;
omit the `tls` block entirely for a plaintext connection.

`toConnectionConfig()` maps `EtcdProperties` onto the
[`EtcdConnectionConfig`](index.md#etcdconnectionconfig) that `connectToEtcd` consumes —
the properties type exists only to be a Boot-shaped front end for it.

!!! tip "Durations are Spring-parsed, not raw numbers"

    `connect-timeout: 5s` works because Boot's relaxed binding converts a duration string
    into a `java.time.Duration`. `5s`, `500ms`, and `PT5S` are all valid. A bare `5` is
    interpreted as milliseconds — write the unit and avoid the question.

## Injecting `EtcdRecipes`

The factory bean is the intended entry point for application code:

=== "Kotlin"

    ```kotlin
    --8<-- "kotlin/website/spring/SpringSnippets.kt:service"
    ```

=== "Java"

    ```java
    --8<-- "java/website/spring/SpringSnippets.java:service"
    ```

!!! warning "The recipe is not a bean — do not make it one"

    `recipes.mutex(...)` constructs a new `DistributedMutex` on every call, and you own
    its lifecycle. That is why the examples above scope it to the unit of work with `use` /
    try-with-resources rather than holding it in a field.

    Promoting a lock to a singleton bean is the tempting mistake here: a mutex held open
    for the lifetime of the context holds an etcd lease for that long too, and a
    `@Bean`-managed `close()` only runs at shutdown. Long-lived recipes (a
    `PathChildrenCache`, a `LeaderLatch`) are reasonable beans; short-lived ones (locks,
    queue handles) are not.

## Actuator health

The nested `EtcdHealthConfiguration` is `@ConditionalOnClass(HealthIndicator::class)`, so
the health indicator appears only when Actuator is on your classpath. The starter declares
Actuator as `compileOnly` — it will never pull it in for you.

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

With it present, `EtcdHealthIndicator` maps `Client.ping()` — a bounded, non-mutating,
count-only GET through the RPC retry funnel — onto Actuator's `UP` / `DOWN`:

```json
{
  "status": "UP",
  "components": {
    "etcd": { "status": "UP" }
  }
}
```

!!! note "This probes etcd, it does not just check a flag"

    `ping()` issues a real RPC, which is what makes it worth having: a client object exists
    and looks fine long after the cluster it points at has gone away. It returns `false`
    rather than throwing when etcd is unreachable or the client is closed, so the
    indicator reports `DOWN` instead of erroring the health endpoint.

    Expose it deliberately, though. If your readiness probe includes this indicator, an
    etcd blip will roll your pods out of service — which is correct for a service that
    cannot function without etcd, and wrong for one that merely prefers it.
