/*
 * Copyright (C) 2020 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.hilt.android.plugin

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.TestExtension
import com.android.build.gradle.TestedExtension
import com.android.build.gradle.api.AndroidBasePlugin
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.api.UnitTestVariant
import dagger.hilt.android.plugin.util.CopyJarTransform
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute

/**
 * A Gradle plugin that checks if the project is an Android project and if so, registers a
 * bytecode transformation.
 *
 * The plugin also passes an annotation processor option to disable superclass validation for
 * classes annotated with `@AndroidEntryPoint` since the registered transform by this plugin will
 * update the superclass.
 */
class HiltGradlePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    var configured = false
    project.plugins.withType(AndroidBasePlugin::class.java) {
      configured = true
      configureHilt(project)
    }
    project.afterEvaluate {
      check(configured) {
        // Check if configuration was applied, if not inform the developer they have applied the
        // plugin to a non-android project.
        "The Hilt Android Gradle plugin can only be applied to an Android project."
      }
      verifyDependencies(it)
    }
  }

  private fun configureHilt(project: Project) {
    val extension = project.extensions.create(
      HiltExtension::class.java, "hilt", HiltExtensionImpl::class.java
    )
    configureCompileClasspath(project, extension)
    configureTransform(project, extension)
    configureProcessorFlags(project)
  }

  private fun configureCompileClasspath(project: Project, extension: HiltExtension) {
    val androidExtension = project.extensions.findByType(BaseExtension::class.java)
      ?: throw error("Android BaseExtension not found.")
    when (androidExtension) {
      is AppExtension -> {
        // For an app project we configure the app variant and both androidTest and test variants,
        // Hilt components are generated in all of them.
        androidExtension.applicationVariants.all {
          configureVariantCompileClasspath(project, extension, it)
        }
        androidExtension.testVariants.all {
          configureVariantCompileClasspath(project, extension, it)
        }
        androidExtension.unitTestVariants.all {
          configureVariantCompileClasspath(project, extension, it)
        }
      }
      is LibraryExtension -> {
        // For a library project, only the androidTest and test variant are configured since
        // Hilt components are not generated in a library.
        androidExtension.testVariants.all {
          configureVariantCompileClasspath(project, extension, it)
        }
        androidExtension.unitTestVariants.all {
          configureVariantCompileClasspath(project, extension, it)
        }
      }
      is TestExtension ->
        androidExtension.applicationVariants.all {
          configureVariantCompileClasspath(project, extension, it)
        }
      else -> error(
        "Don't know how to configure compile classpath for project with extension " +
          "'$androidExtension'"
      )
    }
    project.dependencies.apply {
      registerTransform(CopyJarTransform::class.java) { spec ->
        // Java/Kotlin library projects offer an artifact of type 'jar'.
        spec.from.attribute(ARTIFACT_TYPE_ATTRIBUTE, "jar")
        // Android library projects (with our without Kotlin) offer an artifact of type
        // 'android-classes', which AGP can offer as a jar.
        spec.from.attribute(ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
        spec.to.attribute(ARTIFACT_TYPE_ATTRIBUTE, DAGGER_ARTIFACT_TYPE_VALUE)
      }
    }
  }

  private fun configureVariantCompileClasspath(
    project: Project,
    extension: HiltExtension,
    variant: BaseVariant
  ) {
    if (!extension.enableClasspathAggregation) {
      // Option is not enabled, don't configure compile classpath.
      return
    }

    val artifactView = if (variant is TestVariant) {
      // For Android test variants, the tested runtime classpath is used since the test app has
      // tested dependencies removed.
      variant.testedVariant.runtimeConfiguration
    } else {
      variant.runtimeConfiguration
    }.incoming.artifactView { view ->
      view.attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, DAGGER_ARTIFACT_TYPE_VALUE)
    }

    // CompileOnly config names don't follow the usual convention:
    // <Variant Name>   -> <Config Name>
    // debug            -> debugCompileOnly
    // debugAndroidTest -> androidTestDebugCompileOnly
    // debugUnitTest    -> testDebugCompileOnly
    // release          -> releaseCompileOnly
    // releaseUnitTest  -> testReleaseCompileOnly
    val compileOnlyConfigName = when (variant) {
      is TestVariant ->
        "androidTest${variant.name.substringBeforeLast("AndroidTest").capitalize()}CompileOnly"
      is UnitTestVariant ->
        "test${variant.name.substringBeforeLast("UnitTest").capitalize()}CompileOnly"
      else ->
        "${variant.name}CompileOnly"
    }
    project.dependencies.add(compileOnlyConfigName, artifactView.files)
  }

  private fun configureTransform(project: Project, extension: HiltExtension) {
    val androidExtension = project.extensions.findByType(BaseExtension::class.java)
      ?: throw error("Android BaseExtension not found.")
    androidExtension.registerTransform(AndroidEntryPointTransform())

    // Create and configure a task for applying the transform for host-side unit tests. b/37076369
    val testedExtensions = project.extensions.findByType(TestedExtension::class.java)
    testedExtensions?.unitTestVariants?.all { unitTestVariant ->
      HiltTransformTestClassesTask.create(
        project = project,
        unitTestVariant = unitTestVariant,
        extension = extension
      )
    }
  }

  private fun configureProcessorFlags(project: Project) {
    val androidExtension = project.extensions.findByType(BaseExtension::class.java)
      ?: throw error("Android BaseExtension not found.")
    // Pass annotation processor flag to disable @AndroidEntryPoint superclass validation.
    androidExtension.defaultConfig.apply {
      javaCompileOptions.apply {
        annotationProcessorOptions.apply {
          PROCESSOR_OPTIONS.forEach { (key, value) -> argument(key, value) }
        }
      }
    }
  }

  private fun verifyDependencies(project: Project) {
    // If project is already failing, skip verification since dependencies might not be resolved.
    if (project.state.failure != null) {
      return
    }
    val dependencies = project.configurations.flatMap { configuration ->
      configuration.dependencies.map { dependency -> dependency.group to dependency.name }
    }
    if (!dependencies.contains(LIBRARY_GROUP to "hilt-android")) {
      error(missingDepError("$LIBRARY_GROUP:hilt-android"))
    }
    if (!dependencies.contains(LIBRARY_GROUP to "hilt-android-compiler") &&
      !dependencies.contains(LIBRARY_GROUP to "hilt-compiler")
    ) {
      error(missingDepError("$LIBRARY_GROUP:hilt-compiler"))
    }
  }

  companion object {
    val ARTIFACT_TYPE_ATTRIBUTE = Attribute.of("artifactType", String::class.java)
    const val DAGGER_ARTIFACT_TYPE_VALUE = "jar-for-dagger"

    const val LIBRARY_GROUP = "com.google.dagger"
    val PROCESSOR_OPTIONS = listOf(
      "dagger.fastInit" to "enabled",
      "dagger.hilt.android.internal.disableAndroidSuperclassValidation" to "true"
    )
    val missingDepError: (String) -> String = { depCoordinate ->
      "The Hilt Android Gradle plugin is applied but no $depCoordinate dependency was found."
    }
  }
}
