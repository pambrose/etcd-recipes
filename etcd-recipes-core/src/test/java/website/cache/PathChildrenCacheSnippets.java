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

package website.cache;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.recipes.cache.ChildData;
import io.etcd.recipes.cache.PathChildrenCache;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class PathChildrenCacheSnippets {

  public void basic(Client client) {
    // --8<-- [start:basic]
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/workers")) {
      // true = snapshot the prefix before the watch starts, so the cache is already
      // populated by the time start() returns.
      cache.start(true);

      for (ChildData child : cache.getCurrentData()) {
        System.out.println(child.getKey() + " -> " + child.getValue().toString(StandardCharsets.UTF_8));
      }
    }
    // --8<-- [end:basic]
  }

  public void startModes(Client client) {
    // --8<-- [start:start-modes]
    // NORMAL: no snapshot. The cache starts empty and fills only from live events.
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/workers")) {
      cache.start(PathChildrenCache.StartMode.NORMAL);
    }

    // BUILD_INITIAL_CACHE: snapshot first, then watch from the snapshot revision + 1.
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/workers")) {
      cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
    }

    // POST_INITIALIZED_EVENT: BUILD_INITIAL_CACHE, plus an INITIALIZED event carrying
    // the snapshot in getInitialData().
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/workers")) {
      cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
    }
    // --8<-- [end:start-modes]
  }

  public void waitOnStart(Client client) throws InterruptedException {
    // --8<-- [start:wait-on-start]
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/workers")) {
      // false = return before the snapshot has loaded, so the cache may still be empty.
      cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE, false);

      // ...then bound the wait yourself. Java uses the (long, TimeUnit) overload.
      if (cache.waitOnStartComplete(10, TimeUnit.SECONDS)) {
        System.out.println("Primed with " + cache.getCurrentData().size() + " children");
      } else {
        System.out.println("Snapshot still loading after 10s");
      }
    }
    // --8<-- [end:wait-on-start]
  }

  public void listener(Client client) {
    // --8<-- [start:listener]
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/workers")) {
      cache.addListener(event -> {
        // A switch expression rather than a statement: the compiler enforces that
        // every Type is handled, so a new event type becomes a compile error here
        // instead of an event this listener silently drops.
        String message = switch (event.getType()) {
          case CHILD_ADDED -> "Added " + event.getChildName();
          case CHILD_UPDATED -> "Updated " + event.getChildName();
          // getData() carries the value the child held immediately before removal.
          case CHILD_REMOVED -> "Removed " + event.getChildName();
          // Only INITIALIZED events populate getInitialData().
          case INITIALIZED -> "Primed: " + event.getInitialData().size();
        };
        System.out.println(message);
      });
      cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
    }
    // --8<-- [end:listener]
  }

  public void reads(Client client) {
    // --8<-- [start:reads]
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/workers")) {
      cache.start(true);

      // Sorted by child name, so iteration order is stable across clients.
      List<ChildData> children = cache.getCurrentData();
      System.out.println("Holding " + children.size() + " children");

      // The child name RELATIVE to the cache path, not a full path:
      // "/cache/workers/w1" returns null, "w1" is what you want.
      ByteSequence w1 = cache.getCurrentData("w1");
      System.out.println("w1 -> " + (w1 == null ? "absent" : w1.toString(StandardCharsets.UTF_8)));

      // A point-in-time copy of the whole prefix.
      Map<String, ByteSequence> snapshot = cache.getCurrentDataAsMap();
      System.out.println("Snapshot size: " + snapshot.size());
    }
    // --8<-- [end:reads]
  }

  public void rebuild(Client client) {
    // --8<-- [start:rebuild]
    try (PathChildrenCache cache = new PathChildrenCache(client, "/cache/workers")) {
      cache.start(true);

      // A coarse manual re-sync against etcd's current children. The watcher already
      // keeps the cache converged, so this is a repair tool, not part of steady state.
      cache.rebuild();

      // Drops every entry locally without touching etcd.
      cache.clear();
    }
    // --8<-- [end:rebuild]
  }
}
