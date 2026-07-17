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

package io.etcd.recipes.election

import com.pambrose.common.util.randomId
import io.etcd.recipes.common.EtcdConnector
import io.etcd.recipes.common.appendToPath

/**
 * The etcd key scheme shared by every election participant. This MUST stay in
 * lockstep with `LeaderSelector`'s private `withLeaderSuffix` /
 * `withParticipationSuffix` / `stripUniqueSuffix` (that file is source-frozen, so
 * the scheme cannot be extracted from it) so that latches, selectors, and observers
 * interoperate in one election.
 */
internal object ElectionPaths {
  const val LEADER_KEY = "LEADER"
  const val PARTICIPANTS_KEY = "participants"

  // ':' + the random token: the LEADER value is "<clientId>:<randomId(TOKEN_LENGTH)>".
  const val UNIQUE_SUFFIX_LENGTH = 1 + EtcdConnector.TOKEN_LENGTH

  fun leaderKey(electionPath: String): String = electionPath.appendToPath(LEADER_KEY)

  fun participantsPath(electionPath: String): String = electionPath.appendToPath(PARTICIPANTS_KEY)

  fun participantKey(
    electionPath: String,
    clientId: String,
  ): String = participantsPath(electionPath).appendToPath(clientId)

  /** The LEADER value written by a winning candidate: `<clientId>:<randomId(7)>`. */
  fun leaderToken(clientId: String): String = "$clientId:${randomId(EtcdConnector.TOKEN_LENGTH)}"

  /** Recovers the leader's clientId from a LEADER value by dropping the unique suffix. */
  fun stripLeaderClientId(value: String): String = value.dropLast(UNIQUE_SUFFIX_LENGTH)
}
