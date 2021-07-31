/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.bazel.kotlin.builder.utils

import java.util.function.Predicate
import java.util.regex.Pattern

fun <T, C : MutableCollection<T>> C.addAll(vararg entries: T): C = this.also { addAll(entries) }

/**
 * Utility function to add multiple entries to a list with a leader.
 */
fun <T, C : MutableCollection<T>> C.addAll(
  leader: T,
  entries: List<T>
): C =
  this.also { add(leader); addAll(entries) }

private fun extensionMatcher(vararg ext: String): Predicate<String> =
  Pattern.compile("^(.+?)${ext.joinToString("|\\.", prefix = "(\\.", postfix = ")$")}")
    .asPredicate()

val IS_JVM_SOURCE_FILE = extensionMatcher("kt", "java")
