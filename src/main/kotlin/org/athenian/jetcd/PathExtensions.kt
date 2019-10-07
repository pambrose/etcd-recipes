/*
 *
 *  Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package org.athenian.jetcd

fun String.ensureTrailing(delim: String = "/"): String = "$this${if (endsWith(delim)) "" else delim}"

fun String.stripLeading(delim: String = "/"): String = if (startsWith(delim)) drop(1) else this

fun String.stripTrailing(delim: String = "/"): String = if (endsWith(delim)) dropLast(1) else this

fun String.appendToPath(suffix: String, delim: String = "/"): String =
    "${stripTrailing(delim)}$delim${suffix.stripLeading(delim)}"