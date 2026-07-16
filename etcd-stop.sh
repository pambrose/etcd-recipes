#!/bin/sh
# etcd-stop.sh — gracefully stop the local etcd started by etcd.sh

# Match the exact etcd we launch in etcd.sh (avoids killing unrelated etcd procs)
PIDS=$(pgrep -f 'etcd --listen-client-urls=http://localhost:2379')

if [ -z "$PIDS" ]; then
  echo "No matching etcd process is running."
  exit 0
fi

echo "Stopping etcd (PID: $PIDS) ..."
kill -TERM $PIDS  # SIGTERM: graceful shutdown, flushes WAL

# Wait up to 10s for a clean exit
for _ in $(seq 1 20); do
  pgrep -f 'etcd --listen-client-urls=http://localhost:2379' >/dev/null 2>&1 || {
    echo "etcd stopped cleanly."
    exit 0
  }
  sleep 0.5
done

echo "etcd did not exit after SIGTERM; sending SIGKILL ..."
kill -KILL $PIDS 2>/dev/null
echo "etcd killed."
