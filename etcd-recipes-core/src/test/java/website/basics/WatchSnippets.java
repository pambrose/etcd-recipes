/*
 * Copyright © 2026 Paul Ambrose
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package website.basics;

import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Watch.Watcher;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.recipes.common.WatchResilience;
import kotlin.Unit;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import static io.etcd.recipes.common.BuilderUtils.getOption;
import static io.etcd.recipes.common.BuilderUtils.watchOption;
import static io.etcd.recipes.common.KVUtils.getResponse;
import static io.etcd.recipes.common.WatchUtils.getKeyAsString;
import static io.etcd.recipes.common.WatchUtils.getValueAsString;
import static io.etcd.recipes.common.WatchUtils.watcher;
import static io.etcd.recipes.common.WatchUtils.watcherWithLatch;
import static io.etcd.recipes.common.WatchUtils.withWatcher;

public class WatchSnippets {

  public void basic(Client client) {
    // --8<-- [start:basic]
    // watcher returns a Closeable Watch.Watcher: the watch lives until you close it.
    try (Watcher watcher = watcher(client, "/config/name", response -> {
      response.getEvents().forEach(event ->
        System.out.printf("%s %s = %s%n",
          event.getEventType(), getKeyAsString(event), getValueAsString(event)));
      return Unit.INSTANCE;
    })) {
      System.out.println("Watching /config/name; closed=" + watcher.isClosed());
    }
    // --8<-- [end:basic]
  }

  public void scoped(Client client) {
    // --8<-- [start:with-watcher]
    // withWatcher binds the watch's lifetime to the receiver block and closes it on exit,
    // including on an exception. Prefer it over watcher() + a hand-written finally.
    CountDownLatch seen = new CountDownLatch(3);
    withWatcher(client, "/config/name",
      response -> {
        response.getEvents().forEach(event -> seen.countDown());
        return Unit.INSTANCE;
      },
      watcher -> {
        awaitQuietly(seen);
        return Unit.INSTANCE;
      });
    // --8<-- [end:with-watcher]
  }

  public void watchPrefix(Client client) {
    // --8<-- [start:watch-prefix]
    // watchOption builds a jetcd WatchOption. isPrefix(true) turns a single-key watch
    // into a range watch over every key beneath the prefix — one stream, not one per key.
    WatchOption option = watchOption(builder -> builder.isPrefix(true));

    withWatcher(client, "/services/", option,
      response -> {
        response.getEvents().forEach(event ->
          System.out.printf("%s %s%n", event.getEventType(), getKeyAsString(event)));
        return Unit.INSTANCE;
      },
      watcher -> {
        System.out.println("Watching everything under /services/");
        return Unit.INSTANCE;
      });
    // --8<-- [end:watch-prefix]
  }

  public void withLatch(Client client, CountDownLatch endWatch) {
    // --8<-- [start:watcher-with-latch]
    // Splits PUT from DELETE for you and blocks the calling thread until the latch drops.
    // UNRECOGNIZED event types are ignored rather than surfaced.
    watcherWithLatch(client, "/services/", endWatch,
      event -> {
        System.out.println("Put: " + getKeyAsString(event));
        return Unit.INSTANCE;
      },
      event -> {
        System.out.println("Deleted: " + getKeyAsString(event));
        return Unit.INSTANCE;
      },
      watchOption(builder -> builder.isPrefix(true)));
    // --8<-- [end:watcher-with-latch]
  }

  public void resilient(Client client) {
    // --8<-- [start:resilient]
    try (Watcher watcher = watcher(client, "/services/",
      watchOption(builder -> builder.isPrefix(true)),
      // jetcd retries transient stream errors itself. This recovers what it gives up
      // on: compaction, halt statuses, and "etcdserver: no leader".
      WatchResilience.DEFAULT,
      event -> System.out.println("Recovery: " + event),
      () -> {
        // Called only after a compaction, on the dispatcher thread. Events in the gap are
        // gone for good, so re-read the world, rebuild derived state, and return the
        // revision to re-anchor at.
        GetResponse response =
          getResponse(client, "/services/", getOption(builder -> builder.isPrefix(true)));
        rebuildStateFrom(response.getKvs());
        return response.getHeader().getRevision() + 1;
      },
      response -> {
        response.getEvents().forEach(event -> System.out.println(getKeyAsString(event)));
        return Unit.INSTANCE;
      })) {
      System.out.println("Watching with recovery; closed=" + watcher.isClosed());
    }
    // --8<-- [end:resilient]
  }

  public void callbackOffload(Client client, BlockingQueue<String> work) {
    // --8<-- [start:callback-offload]
    // The callback runs on one dedicated dispatcher thread, not a pool: until it returns,
    // no further event for this watcher is delivered. Keep it a pure handoff and let a
    // thread you own do the etcd calls and the lock taking.
    try (Watcher watcher = watcher(client, "/services/", response -> {
      response.getEvents().forEach(event -> work.add(getKeyAsString(event)));
      return Unit.INSTANCE;
    })) {
      System.out.println("Callback only enqueues; a worker drains the queue");
    }
    // --8<-- [end:callback-offload]
  }

  private void rebuildStateFrom(List<KeyValue> kvs) {
    System.out.println("Rebuilt derived state from " + kvs.size() + " keys");
  }

  private void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
