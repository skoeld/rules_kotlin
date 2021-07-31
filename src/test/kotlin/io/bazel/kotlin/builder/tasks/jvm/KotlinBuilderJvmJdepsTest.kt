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
package io.bazel.kotlin.builder.tasks.jvm

import com.google.common.truth.Truth.assertThat
import com.google.devtools.build.lib.view.proto.Deps
import io.bazel.kotlin.builder.KotlinJvmTestBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.Consumer

@RunWith(JUnit4::class)
class KotlinBuilderJvmJdepsTest {
  val ctx = KotlinJvmTestBuilder()

  @Test
  fun `no kotlin source produces empty jdeps`() {

    val deps = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AnotherClass.java",
          """
          package something;
          
          class AnotherClass {
          }
        """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
        c.compileJava()
        c.outputJavaJdeps()
      }
    )
    val jdeps = depsProto(deps)

    assertThat(jdeps.dependencyCount).isEqualTo(0)
    assertThat(jdeps.ruleLabel).isEqualTo(deps.label())
  }

  @Test
  fun `no dependencies`() {

    val deps = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
            package something
            
            class AClass{}
          """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )
    val jdeps = depsProto(deps)

    assertThat(jdeps.dependencyCount).isEqualTo(0)
    assertThat(jdeps.ruleLabel).isEqualTo(deps.label())
  }

  @Test
  fun `java class reference`() {

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
          package something
          
          class AClass{}
        """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AnotherClass.java",
          """
          package something;
          
          class AnotherClass {
            public AClass ref = null;
          }
        """
        )
        c.outputJar()
        c.compileJava()
        c.outputJavaJdeps()
        c.addDirectDependencies(dependentTarget)
      }
    )
    val jdeps = javaDepsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `java class static reference`() {

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "JavaClass.java",
          """
          package something;
          
          class JavaClass {
            public static boolean staticMethod() { return true; }
          }
        """
        )
        c.outputJar()
        c.compileJava()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
          package something
          
          val result = JavaClass.staticMethod()
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.outputJdeps()
        c.addDirectDependencies(dependentTarget)
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `java constant reference`() {

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Constants.java",
          """
          package something;
          
          public interface Constants {
              int HELLO_CONSTANT = 100;
          }
        """
        )
        c.outputJar()
        c.outputJavaJdeps()
        c.compileJava()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AnotherClass.kt",
          """
          package something.other
          
          import something.Constants
          
          class AnotherClass {
            val ref = Constants.HELLO_CONSTANT
          }
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.outputJdeps()
        c.addDirectDependencies(dependentTarget)
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `java annotation reference`() {

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "JavaAnnotation.java",
          """
          package something;
          
          import java.lang.annotation.Retention;
          import java.lang.annotation.RetentionPolicy;
          
          @Retention(RetentionPolicy.RUNTIME)          
          public @interface JavaAnnotation {
          }
        """
        )
        c.outputJar()
        c.outputJavaJdeps()
        c.compileJava()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AnotherClass.kt",
          """
          package something.other
          
          import something.JavaAnnotation
 
          abstract class AnotherClass {
            @JavaAnnotation
            internal abstract fun hasAnnotation()
          }
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.outputJdeps()
        c.addDirectDependencies(dependentTarget)
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `annotation on class is an explict dep`() {

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Annotation.kt",
          """
          package something;
          
          @Target(AnnotationTarget.CLASS)
          @Retention(AnnotationRetention.SOURCE)
          annotation class ClassAnnotation
        """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AnotherClass.kt",
          """
          package something

          @ClassAnnotation
          class AnotherClass { }
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.outputJdeps()
        c.addDirectDependencies(dependentTarget)
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `cyclic generic type references`() {

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AbstractObjectAssert.java",
          """
          package pkg.assertion;
          
          public class AbstractObjectAssert<SELF extends AbstractObjectAssert<SELF, ACTUAL>, ACTUAL> {
          }
        """
        )
        c.outputJar()
        c.outputJavaJdeps()
        c.compileJava()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "FooAssert.kt",
          """
          package something.other
          
          import pkg.assertion.AbstractObjectAssert
          
          class FooAssert : AbstractObjectAssert<FooAssert, String>()

          fun fooAssert(): AbstractObjectAssert<*, String> = AbstractObjectAssert<FooAssert, String>()
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.outputJdeps()
        c.addDirectDependencies(dependentTarget)
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `java annotation on property is an explict dep`() {

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "JavaAnnotation.java",
          """
          package something;
          
          import java.lang.annotation.Retention;
          import java.lang.annotation.RetentionPolicy;
          
          @Retention(RetentionPolicy.RUNTIME)          
          public @interface JavaAnnotation {
          }
        """
        )
        c.outputJar()
        c.compileJava()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AnotherClass.kt",
          """
          package something

          class AnotherClass {
            @JavaAnnotation
            val property = 42
          }
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.outputJdeps()
        c.addDirectDependencies(dependentTarget)
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `java annotation with field target on companion object property is an explict dep`() {

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "LayoutRes.java",
          """
          package androidx.annotation;
          import static java.lang.annotation.ElementType.FIELD;
          import java.lang.annotation.Target;
          @Target({FIELD})
          public @interface LayoutRes {
          }
        """
        )
        c.outputJar()
        c.compileJava()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AnotherClass.kt",
          """
          package something

          import androidx.annotation.LayoutRes

          class AnotherClass {
              companion object {
                
                @JvmField @LayoutRes
                val property = 42
            }
          }
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.outputJdeps()
        c.addDirectDependencies(dependentTarget)
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `unused dependency listed`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
          package something
          
          class AClass{}
        """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasNoReferenceToDep.kt",
          """
          package something
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).isEmpty()
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `unused transitive dependency not listed`() {
    val transitiveDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "TransitiveClass.kt",
          """
          package something
          
          class TransitiveClass{}
        """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
          package something
          
          class AClass{}
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(transitiveDep)
        c.outputJdeps()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasNoReferenceToDep.kt",
          """
          package something
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).isEmpty()
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `kotlin property reference`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
            package something

            class AClass{}
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasPropertyDependency.kt",
          """
            package something
            
            val property2 =  AClass()
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `kotlin indirect property reference on object`() {
    val transitivePropertyTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Bar.kt",
          """
            package something

            class Bar {
              fun helloWorld() {}
            }
            """
        )
        c.outputJar()
        c.compileKotlin()
      }
    )

    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Foo.kt",
          """
            package something

            class Foo {
              val bar = Bar()
            }
            """
        )
        c.outputJar()
        c.addDirectDependencies(transitivePropertyTarget)
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasPropertyDependency.kt",
          """
            package something
            
            fun something(foo: Foo) {
              val foo =  Foo()
              foo.bar.helloWorld()
            }
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.addTransitiveDependencies(transitivePropertyTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(dependentTarget.singleCompileJar())
    assertExplicit(jdeps).contains(transitivePropertyTarget.singleCompileJar())
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `kotlin extension property reference`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
            package something

            val String.doubleLength
                get() = length * 2
            """
        )
        c.outputJar()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasPropertyDependency.kt",
          """
            package something
            
            val property2 = "Hello".doubleLength
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `kotlin extension property reference 2`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
            package something
            
            object Extensions {
              @JvmStatic
              fun String.doubleLength() = length * 2
            }
            """
        )
        c.outputJar()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasPropertyDependency.kt",
          """
            package something
            
            import something.Extensions.doubleLength
            
            val property2 = "Hello".doubleLength()
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `kotlin property definition`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "JavaClass.java",
          """
            package something;
   
            public class JavaClass {
              public interface InnerJavaClass {
  
              }          
            }
          """
        )
        c.outputJar()
        c.outputJavaJdeps()
        c.compileJava()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasPropertyDefinition.kt",
          """
            package something
            
            interface HasPropertyDefinition {

                val callFactory: JavaClass.InnerJavaClass
            }
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `java enum reference`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "InnerJavaEnum.java",
          """
            package something;

            public enum InnerJavaEnum {
                A_VALUE;
            }
          """
        )
        c.outputJar()
        c.compileJava()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasPropertyDefinition.kt",
          """
            package something
            
            class Foo {

                val result = InnerJavaEnum.A_VALUE.name
            }
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `kotlin method reference`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
            package something

            fun String.aFunction() {}
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasFunctionDependency.kt",
          """
            package something.other
            
            import something.aFunction
            
            val functionRef = String::aFunction
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(dependentTarget.singleCompileJar())
  }

  @Test
  fun `kotlin generic type reference`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AClass.kt",
          """
            package something

            class AClass{}
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasGenericTypeDependency.kt",
          """
            package something
            
            val property2 =  listOf<AClass>()
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `inlined constant dependency recorded`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ContainsConstant.kt",
          """
          package dependency
            
          object ConstHolder {
            const val CONSTANT_VAL = 42
          }
        """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasPropertyDependency.kt",
          """
            package something
            import dependency.ConstHolder
            val property2 = ConstHolder.CONSTANT_VAL
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `object inlined constant dependency recorded`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasConstants.kt",
          """
          package dependency
          object HasConstants {
            const val CONSTANT_VAL = 42
          }
        """
        )
        c.outputJar()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasPropertyDependency.kt",
          """
            package something
            import dependency.HasConstants.CONSTANT_VAL
            val property2 = CONSTANT_VAL
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `companion object inlined constant dependency recorded`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ContainsConstant.kt",
          """
          package dependency
          class HasCompanion {
            companion object {
              const val CONSTANT_VAL = 42
            }            
          }
        """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasPropertyDependency.kt",
          """
            package something
            import dependency.HasCompanion.Companion.CONSTANT_VAL
            val property2 = CONSTANT_VAL
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `constructor param inner class recorded`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "JavaClass.java",
          """
          package something;
 
          public class JavaClass {
            public interface InnerJavaClass {

            }          
          }
        """
        )
        c.outputJar()
        c.outputJavaJdeps()
        c.compileJava()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasConstructorDependency.kt",
          """
            package something.otherthan
            
            import something.JavaClass
            
            class HasConstructorDependency constructor(javaClass: JavaClass.InnerJavaClass) {}
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `call function in dependency`() {
    val dependentTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ContainsFunction.kt",
          """
          package dependency
            
          fun someFunction() = 42
        """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "HasFunctionDependency.kt",
          """
            package something
            import dependency.someFunction
            val property2 = someFunction()
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(dependentTarget)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(dependentTarget.singleCompileJar())
    assertImplicit(jdeps).isEmpty()
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `implement interface reference should be an explicit dependency`() {
    val indirectInterfaceDef = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "IndirectInterface.kt",
          """
            package something

            interface IndirectInterface {
                fun doFoo()
            }
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )
    val directInterfaceDef = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "DirectInterface.kt",
          """
            package something

            interface DirectInterface : IndirectInterface {
                fun doBar()
            }
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
        c.addDirectDependencies(indirectInterfaceDef)
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ReferencesClassWithSuperClass.kt",
          """
            package something

            interface SubInterface : DirectInterface
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.outputJdeps()
        c.addDirectDependencies(directInterfaceDef)
        c.addTransitiveDependencies(indirectInterfaceDef)
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(directInterfaceDef.singleCompileJar())
    assertImplicit(jdeps).containsExactly(indirectInterfaceDef.singleCompileJar())
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `indirect super class reference should be an implicit dependency`() {
    val implicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Base.kt",
          """
            package something

            open class Base(p: Int)
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val explicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Derived.kt",
          """
            package something

            class Derived(p: Int) : Base(p)
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
        c.addDirectDependencies(implicitSuperClassDep)
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ReferencesClassWithSuperClass.kt",
          """
            package something

            val classRef = Derived(42)
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(explicitSuperClassDep)
        c.addTransitiveDependencies(implicitSuperClassDep)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(explicitSuperClassDep.singleCompileJar())
    assertImplicit(jdeps).containsExactly(implicitSuperClassDep.singleCompileJar())
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }
  @Test
  fun `property reference should collect indirect super class as implicit dependency`() {
    val implicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Base.kt",
          """
            package something

            open class Base(p: Int)
            """
        )
        c.outputJar()
        c.compileKotlin()
      }
    )

    val explicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Derived.kt",
          """
            package something

            object Derived : Base(41) {
              @JvmField
              val SOME_CONST = 42
            }
            """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(implicitSuperClassDep)
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ReferencesClassWithSuperClass.kt",
          """
            package something

            val classRef = Derived.SOME_CONST
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(explicitSuperClassDep)
        c.addTransitiveDependencies(implicitSuperClassDep)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsExactly(explicitSuperClassDep.singleCompileJar())
    assertImplicit(jdeps).containsExactly(implicitSuperClassDep.singleCompileJar())
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `class declaration all super class references should be an implicit dependency`() {
    val implicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Base.kt",
          """
            package something

            open class Base
            """
        )
        c.outputJar()
        c.compileKotlin()
      }
    )

    val explicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Derived.kt",
          """
            package something

            open class Derived : Base()
            """
        )
        c.addSource(
          "Derived2.kt",
          """
            package something

            open class Derived2 : Derived()
            """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(implicitSuperClassDep)
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "DependingClass.kt",
          """
            package something

            abstract class DependingClass : Derived2()
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(explicitSuperClassDep)
        c.addTransitiveDependencies(implicitSuperClassDep)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertExplicit(jdeps).containsExactly(explicitSuperClassDep.singleCompileJar())
    assertImplicit(jdeps).containsExactly(implicitSuperClassDep.singleCompileJar())
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `generic type as constructor parameter`() {
    val implicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Base.kt",
          """
            package something.base

            open class Base(p: Int)
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val explicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Derived.kt",
          """
            package something.derived
            
            import something.base.Base

            class Derived(p: Int) : Base(p)
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
        c.addDirectDependencies(implicitSuperClassDep)
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AnotherClass.java",
          """
          package something.reference;
          
          class AnotherClass<T> {
          }
        """
        )
        c.addSource(
          "ReferencesGenericTypeWithSuperClass.kt",
          """
            package something.reference
            
            import something.derived.Derived

            internal class HasConstructorDependency constructor(genericRef: AnotherClass<Derived>) {}
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(explicitSuperClassDep)
        c.addTransitiveDependencies(implicitSuperClassDep)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(explicitSuperClassDep.singleCompileJar())
    assertImplicit(jdeps).contains(implicitSuperClassDep.singleCompileJar())
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `generic type as lazy property`() {
    val implicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Base.kt",
          """
            package something.base

            open class Base(p: Int)
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
      }
    )

    val explicitSuperClassDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Derived.kt",
          """
            package something.derived
            
            import something.base.Base

            class Derived(p: Int) : Base(p)
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
        c.addDirectDependencies(implicitSuperClassDep)
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "AnotherClass.java",
          """
          package something.reference;
          
          class AnotherClass<T> {
          }
        """
        )
        c.addSource(
          "ReferencesGenericTypeWithSuperClass.kt",
          """
            package something.reference
            
            import something.derived.Derived

            private val lazyProperty by lazy { AnotherClass<Derived>() }
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.compileJava()
        c.addDirectDependencies(explicitSuperClassDep)
        c.addTransitiveDependencies(implicitSuperClassDep)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(explicitSuperClassDep.singleCompileJar())
    assertImplicit(jdeps).contains(implicitSuperClassDep.singleCompileJar())
    assertUnused(jdeps).isEmpty()
    assertIncomplete(jdeps).isEmpty()
  }

  @Test
  fun `function call return type`() {
    val depWithReturnTypesSuperType = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "SomeSuperType.kt",
          """
            package something

            open class SomeSuperType
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
        c.setLabel("depWithReturnType")
      }
    )
    val depWithReturnType = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "SomeType.kt",
          """
            package something

            class SomeType : SomeSuperType() {
            }
            """
        )
        c.outputJar()
        c.outputJdeps()
        c.compileKotlin()
        c.setLabel("depWithReturnType")
        c.addDirectDependencies(depWithReturnTypesSuperType)
      }
    )

    val depWithFunction = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ContainsFunction.kt",
          """
            package something

            fun returnSomeType() = SomeType()
            """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(depWithReturnType)
        c.setLabel("depWithFunction")
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ReferencesClassWithSuperClass.kt",
          """
            package something

            fun foo() {
              returnSomeType()
            }
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(depWithFunction)
        c.addTransitiveDependencies(depWithReturnType)
        c.outputJdeps()
        c.setLabel("dependingTarget")
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(depWithFunction.singleCompileJar())
    assertImplicit(jdeps).contains(depWithReturnType.singleCompileJar())
    assertImplicit(jdeps).doesNotContain(depWithReturnTypesSuperType)
  }

  @Test
  fun `constructor parameters are required implicit dependencies`() {
    val fooDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "FooClass.kt",
          """
          package something

          class FooClass
          """
        )
        c.outputJar()
        c.compileKotlin()
      }
    )
    val barDep = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "BarClass.kt",
          """
          package something

          class BarClass(private val foo: FooClass = FooClass()) { }
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(fooDep)
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ReferencesClassWithSuperClass.kt",
          """
          package something

          class Dummy {
            val result = BarClass()
          }
        """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(barDep)
        c.addTransitiveDependencies(fooDep)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertExplicit(jdeps).contains(barDep.singleCompileJar())
    assertImplicit(jdeps).contains(fooDep.singleCompileJar())
  }

  @Test
  fun `function call return type type parameter should not be a dependency`() {
    val depWithTypeParameter = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "SomeType.kt",
          """
            package something

            class SomeType {
              val booleanValue = true
            }
            """
        )
        c.outputJar()
        c.compileKotlin()
        c.setLabel("depWithReturnType")
      }
    )

    val depWithFunction = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ContainsFunction.kt",
          """
            package something

            fun returnSomeType() = setOf<SomeType>()
            """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(depWithTypeParameter)
        c.setLabel("depWithFunction")
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ReferencesClassWithSuperClass.kt",
          """
            package something

            fun foo() {
              returnSomeType()
            }
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(depWithFunction)
        c.addTransitiveDependencies(depWithTypeParameter)
        c.outputJdeps()
        c.setLabel("dependingTarget")
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).contains(depWithFunction.singleCompileJar())
    assertExplicit(jdeps).doesNotContain(depWithTypeParameter.singleCompileJar())
    assertImplicit(jdeps).doesNotContain(depWithTypeParameter.singleCompileJar())
  }

  @Test
  fun `function call parameter type nested type parameters should be an explicit dependency`() {

    val foo = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Foo.kt",
          """
            package something

            class Foo { }
            """
        )
        c.outputJar()
        c.compileKotlin()
      }
    )

    val bar = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "Bar.kt",
          """
            package something

            class Bar<T> {
              val booleanValue = true
            }
            """
        )
        c.outputJar()
        c.compileKotlin()
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "FunctionWithTypeParams.kt",
          """
            package something

            fun foo(param: Set<Bar<Foo>>) {

            }
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(foo, bar)
        c.outputJdeps()
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertExplicit(jdeps).contains(bar.singleCompileJar())
    assertExplicit(jdeps).contains(foo.singleCompileJar())
  }

  @Test
  fun `assignment from function call`() {
    val depWithReturnTypesSuperType = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "SomeSuperType.kt",
          """
            package something

            open class SomeSuperType
            """
        )
        c.outputJar()
        c.compileKotlin()
        c.setLabel("depWithReturnType")
      }
    )
    val depWithReturnType = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "SomeType.kt",
          """
            package something

            class SomeType : SomeSuperType() {
              val stringValue = "Hello World"
            }
            """
        )
        c.outputJar()
        c.compileKotlin()
        c.setLabel("depWithReturnType")
        c.addDirectDependencies(depWithReturnTypesSuperType)
      }
    )

    val depWithFunction = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ContainsFunction.kt",
          """
            package something

            fun returnSomeType() = SomeType()
            """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(depWithReturnType)
        c.setLabel("depWithFunction")
      }
    )

    val dependingTarget = ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.addSource(
          "ReferencesClassWithSuperClass.kt",
          """
            package something

            fun foo() {
              val assignment = returnSomeType()
              print(assignment.stringValue)
            }
          """
        )
        c.outputJar()
        c.compileKotlin()
        c.addDirectDependencies(depWithFunction)
        c.addTransitiveDependencies(depWithReturnType, depWithReturnTypesSuperType)
        c.outputJdeps()
        c.setLabel("dependingTarget")
      }
    )
    val jdeps = depsProto(dependingTarget)

    assertThat(jdeps.ruleLabel).isEqualTo(dependingTarget.label())

    assertExplicit(jdeps).containsAtLeast(depWithFunction.singleCompileJar(), depWithReturnType.singleCompileJar())
    assertImplicit(jdeps).doesNotContain(depWithReturnType.singleCompileJar())
    assertImplicit(jdeps).doesNotContain(depWithReturnTypesSuperType)
  }

  private fun depsProto(jdeps: io.bazel.kotlin.builder.Deps.Dep) =
    Deps.Dependencies.parseFrom(BufferedInputStream(Files.newInputStream(Paths.get(jdeps.jdeps()!!))))

  private fun javaDepsProto(jdeps: io.bazel.kotlin.builder.Deps.Dep) =
    Deps.Dependencies.parseFrom(BufferedInputStream(Files.newInputStream(Paths.get(jdeps.javaJdeps()!!))))

  private fun assertExplicit(jdeps: Deps.Dependencies) = assertThat(
    jdeps.dependencyList.filter { it.kind == Deps.Dependency.Kind.EXPLICIT }.map { it.path }
  )

  private fun assertImplicit(jdeps: Deps.Dependencies) = assertThat(
    jdeps.dependencyList.filter { it.kind == Deps.Dependency.Kind.IMPLICIT }.map { it.path }
  )

  private fun assertUnused(jdeps: Deps.Dependencies) = assertThat(
    jdeps.dependencyList.filter { it.kind == Deps.Dependency.Kind.UNUSED }.map { it.path }
  )

  private fun assertIncomplete(jdeps: Deps.Dependencies) = assertThat(
    jdeps.dependencyList.filter { it.kind == Deps.Dependency.Kind.INCOMPLETE }.map { it.path }
  )
}
