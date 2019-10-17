# etcd Recipes

[![Build Status](https://travis-ci.org/pambrose/etcd-recipes.svg?branch=master)](https://travis-ci.org/pambrose/etcd-recipes)
[![Coverage Status](https://coveralls.io/repos/github/pambrose/etcd-recipes/badge.svg)](https://coveralls.io/github/pambrose/etcd-recipes)
[![codebeat badge](https://codebeat.co/badges/d61556d4-22e8-44c3-b8f8-db7613fae7fc)](https://codebeat.co/projects/github-com-pambrose-etcd-recipes-master)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/e185b9c637b040bab55bdecf38b0de76)](https://www.codacy.com/manual/pambrose/etcd-recipes?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=pambrose/etcd-recipes&amp;utm_campaign=Badge_Grade)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=pambrose_etcd-recipes&metric=alert_status)](https://sonarcloud.io/dashboard?id=pambrose_etcd-recipes)
[![Maintainability](https://api.codeclimate.com/v1/badges/b183ced841479fbdb242/maintainability)](https://codeclimate.com/github/pambrose/etcd-recipes/maintainability)
[![Known Vulnerabilities](https://snyk.io/test/github/pambrose/etcd-recipes/badge.svg)](https://snyk.io/test/github/pambrose/etcd-recipes)
[![Kotlin](https://img.shields.io/badge/%20language-Kotlin-red.svg)](https://kotlinlang.org/)


[etcd-recipes](https://github.com/pambrose/etcd-recipes) is a Kotlin/Java/JVM client library 
for [etcd](https://etcd.io) v3, a distributed, reliable key-value store. It attempts to provide the same 
kind of support for [etcd](https://etcd.io) that 
[Curator](https://curator.apache.org) does for [ZooKeeper](https://zookeeper.apache.org).

## Examples

[Java](https://github.com/pambrose/etcd-recipes/tree/master/src/main/java/io/etcd/recipes/examples) 
and [Kotlin](https://github.com/pambrose/etcd-recipes/tree/master/src/main/kotlin/io/etcd/recipes/examples) 
examples are included in the repo.

## Usage
```kotlin
connectToEtcd(urls) { client ->
    client.withKvClient { kvClient ->
        kvClient.putValue("test_key", "test_value")
        sleep(5.seconds)
        kvClient.delete("test_key")
    }
}
```

## Compatability
etcd-recipes is built on top of [jetcd](https://github.com/etcd-io/jetcd), which works with etcd v3.

etcd-recipies is written in Kotlin, but is usable by Java and any other JVM clients.


## Download

Jars are available at [jitpack.io](https://jitpack.io/#pambrose/etcd-recipes).

### Gradle

```
# Add jitpack.io to the repositories
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation "com.github.pambrose:etcd-recipes:0.9.0"
}
```

### Maven

``` 
<dependency>
  <groupId>com.github.pambrose</groupId>
  <artifactId>etcd-recipes</artifactId>
  <version>0.9.0</version>
</dependency>
```

## Running tests
   
```nashorn js
make tests
```