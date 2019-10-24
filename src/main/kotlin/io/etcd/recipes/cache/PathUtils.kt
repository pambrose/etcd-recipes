/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.etcd.recipes.cache

class PathUtils {
    companion object {
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun validatePath(path: String, isSequential: Boolean) {
            validatePath(if (isSequential) path + "1" else path)
        }

        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun validatePath(path: String): String {
            return if (path.length == 0) {
                throw IllegalArgumentException("Path length must be > 0")
            } else if (path[0] != '/') {
                throw IllegalArgumentException("Path must start with / character")
            } else if (path.length == 1) {
                path
            } else if (path[path.length - 1] == '/') {
                throw IllegalArgumentException("Path must not end with / character")
            } else {
                var reason: String? = null
                var lastc = '/'
                val chars = path.toCharArray()
                for (i in 1 until chars.size) {
                    val c = chars[i]
                    if (c.toInt() == 0) {
                        reason = "null character not allowed @$i"
                        break
                    }
                    if (c == '/' && lastc == '/') {
                        reason = "empty node name specified @$i"
                        break
                    }
                    if (c == '.' && lastc == '.') {
                        if (chars[i - 2] == '/' && (i + 1 == chars.size || chars[i + 1] == '/')) {
                            reason = "relative paths not allowed @$i"
                            break
                        }
                    } else if (c == '.') {
                        if (chars[i - 1] == '/' && (i + 1 == chars.size || chars[i + 1] == '/')) {
                            reason = "relative paths not allowed @$i"
                            break
                        }
                    } else if (c.toInt() > 0 && c.toInt() < 31 || c.toInt() > 127 && c.toInt() < 159 || c > '\ud800' && c < '\uf8ff' || c > '\ufff0' && c < '\uffff') {
                        reason = "invalid charater @$i"
                        break
                    }
                    lastc = chars[i]
                }
                if (reason != null) {
                    throw IllegalArgumentException("Invalid path string \"$path\" caused by $reason")
                } else {
                    path
                }
            }
        }
    }
}