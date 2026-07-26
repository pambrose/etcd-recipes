#!/bin/sh

etcd --listen-client-urls=http://localhost:2379 --advertise-client-urls=http://localhost:2379

# docker run --detach -p 2379:2379 -p 2380:2380 --name etcd quay.io/coreos/etcd:latest /usr/local/bin/etcd --advertise-client-urls http://0.0.0.0:2379 --listen-client-urls http://0.0.0.0:2379
