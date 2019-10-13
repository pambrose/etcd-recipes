# etcd Recipes

[![Build Status](https://travis-ci.org/pambrose/etcd-recipes.svg?branch=master)](https://travis-ci.org/pambrose/etcd-recipes)
[![Coverage Status](https://coveralls.io/repos/github/pambrose/etcd-recipes/badge.svg)](https://coveralls.io/github/pambrose/etcd-recipes)
[![codebeat badge](https://codebeat.co/badges/d61556d4-22e8-44c3-b8f8-db7613fae7fc)](https://codebeat.co/projects/github-com-pambrose-etcd-recipes-master)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/e185b9c637b040bab55bdecf38b0de76)](https://www.codacy.com/manual/pambrose/etcd-recipes?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=pambrose/etcd-recipes&amp;utm_campaign=Badge_Grade)
[![Maintainability](https://api.codeclimate.com/v1/badges/b183ced841479fbdb242/maintainability)](https://codeclimate.com/github/pambrose/etcd-recipes/maintainability)
[![Kotlin](https://img.shields.io/badge/%20language-Kotlin-red.svg)](https://kotlinlang.org/)

## Setup
Start a localhost server with:
```bash
etcd --listen-client-urls=http://localhost:2379 --advertise-client-urls=http://localhost:2379
```

## Relevant Links

### etcd
*   [Introduction to etcd v3](https://www.youtube.com/watch?v=hQigKX0MxPw)
*   [etcd for Beginners](https://www.youtube.com/watch?v=L9xkXzpEY6Q)
*   [etc3 Documentation](https://github.com/etcd-io/etcd/blob/master/Documentation/docs.md)
*   [etcd 2 to 3: new APIs and new possibilities](https://www.compose.com/articles/etcd2to3-new-apis-and-new-possibilities/)
*   [Apache Zookeeper vs etcd3](https://medium.com/@Imesha94/apache-curator-vs-etcd3-9c1362600b26)
*   [Serializability and Distributed Software Transactional Memory with etcd3](https://coreos.com/blog/transactional-memory-with-etcd3.html)
*   [Transaction Example](https://banzaicloud.com/blog/jetcd_bug/)
*   [play.etcd.io](http://play.etcd.io/play)
*   [Go etcd3 API](https://godoc.org/github.com/coreos/etcd/clientv3)

### Elections
*   [etcd3 leader election using Python](https://www.sandtable.com/etcd3-leader-election-using-python/)

### ZK and Curator
*   [ZooKeeper Tutorial](https://data-flair.training/blogs/zookeeper-tutorial/)
*   [Curator Examples](https://github.com/yiming187/curator-example/tree/master/src/main/java/com/ctrip/zk/curator/example)