/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package io.bazel.kotlin.builder.utils

import com.google.common.truth.Truth
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ArgMapTest {
  @Test
  fun optionalSingleIfExistsDespiteCondition() {
    val key = object : Flag {
      override val flag = "mirror mirror"
    }
    val value = listOf("on the wall")
    val args = ArgMap(mapOf(Pair(key.flag, value)))
    Truth.assertThat(args.optionalSingleIf(key) { false }).isEqualTo(value[0])
    Truth.assertThat(args.optionalSingleIf(key) { true }).isEqualTo(value[0])
  }

  @Test
  fun optionalSingleIfMandatoryOnConditionFalse() {
    val key = object : Flag {
      override val flag = "mirror mirror"
    }
    val args = ArgMap(mapOf())
    Assert.assertThrows(
      "Option is mandatory when condition is false",
      IllegalArgumentException::class.java
    ) {
      args.optionalSingleIf(key) { false }
    }
    Truth.assertThat(args.optionalSingleIf(key) { true }).isNull()
  }

  @Test
  fun hasAll() {
    val empty = object : Flag {
      override val flag = "pessimist"
    }
    val full = object : Flag {
      override val flag = "optimist"
    }
    val args = ArgMap(
      mapOf(
        Pair(empty.flag, emptyList()),
        Pair(full.flag, listOf("half"))
      )
    )
    Truth.assertThat(args.hasAll(full)).isTrue()
    Truth.assertThat(args.hasAll(empty, full)).isFalse()
    Truth.assertThat(
      args.hasAll(object : Flag {
        override val flag = "immaterial"
      })
    ).isFalse()
  }
}
