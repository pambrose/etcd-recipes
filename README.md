# etcd Recipes

[![JitPack](https://jitpack.io/v/pambrose/etcd-recipes.svg)](https://jitpack.io/#pambrose/etcd-recipes)
[![Build Status](https://travis-ci.org/pambrose/etcd-recipes.svg?branch=master)](https://travis-ci.org/pambrose/etcd-recipes)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/e185b9c637b040bab55bdecf38b0de76)](https://www.codacy.com/manual/pambrose/etcd-recipes?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=pambrose/etcd-recipes&amp;utm_campaign=Badge_Grade)
[![codebeat badge](https://codebeat.co/badges/d61556d4-22e8-44c3-b8f8-db7613fae7fc)](https://codebeat.co/projects/github-com-pambrose-etcd-recipes-master)
[![codecov](https://codecov.io/gh/pambrose/etcd-recipes/branch/master/graph/badge.svg)](https://codecov.io/gh/pambrose/etcd-recipes)
[![Coverage Status](https://coveralls.io/repos/github/pambrose/etcd-recipes/badge.svg)](https://coveralls.io/github/pambrose/etcd-recipes)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=pambrose_etcd-recipes&metric=alert_status)](https://sonarcloud.io/dashboard?id=pambrose_etcd-recipes)
[![Known Vulnerabilities](https://snyk.io/test/github/pambrose/etcd-recipes/badge.svg)](https://snyk.io/test/github/pambrose/etcd-recipes)
[![Kotlin](https://img.shields.io/badge/%20language-Kotlin-red.svg)](https://kotlinlang.org/)
[![Java](https://img.shields.io/badge/%20language-Java-red.svg)](https://kotlinlang.org/)

[etcd-recipes](https://github.com/pambrose/etcd-recipes) is a Kotlin/Java/JVM client library 
for [etcd](https://etcd.io) v3, a distributed, reliable key-value store. It attempts to provide the same 
kind of support for [etcd](https://etcd.io) that 
[Curator](https://curator.apache.org) does for [ZooKeeper](https://zookeeper.apache.org).

## Examples

The repo includes [Java](https://github.com/pambrose/etcd-recipes/tree/master/etcd-recipes-examples/src/main/java/io/etcd/recipes/examples) 
and [Kotlin](https://github.com/pambrose/etcd-recipes/tree/master/etcd-recipes-examples/src/main/kotlin/io/etcd/recipes/examples) 
examples.

## Usage
```kotlin
connectToEtcd(urls) { client ->
    client.putValue("test_key", "test_value")
    sleep(5.seconds)
    client.delete("test_key")
}
```

## Compatability
etcd-recipes is built on top of [jetcd](https://github.com/etcd-io/jetcd), which works with etcd v3.

etcd-recipies is written in Kotlin, but is usable by Java and any other JVM clients.


## Download

Jars are available at [jitpack.io](https://jitpack.io/#pambrose/etcd-recipes).

### Gradle

```
# Add kotlinx and jitpack.io to repositories
repositories {
    mavenCentral()
    maven { url "https://kotlin.bintray.com/kotlinx" }
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation "com.github.pambrose.etcd-recipes:etcd-recipes:0.9.21"
}
```

### Maven

``` 
<repositories>
    <repository>
        <id>kotlinx</id>
        <name>kotlinkx Releases</name>
        <url>https://kotlin.bintray.com/kotlinx</url>
    </repository>
    <repository>
        <id>jitpack.io</id>
        <name>jitpack.io Releases</name>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
      <groupId>com.github.pambrose.etcd-recipes</groupId>
      <artifactId>etcd-recipes</artifactId>
      <version>0.9.21</version>
    </dependency>
</dependencies>
```