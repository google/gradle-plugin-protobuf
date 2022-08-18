/*
 * Copyright (c) 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.google.protobuf.gradle

import com.google.common.base.Preconditions
import groovy.transform.CompileStatic
import org.apache.commons.lang.StringUtils
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.ClasspathEntry
import org.gradle.plugins.ide.eclipse.model.EclipseModel
import org.gradle.plugins.ide.eclipse.model.SourceFolder
import org.gradle.plugins.ide.idea.GenerateIdeaModule
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.gradle.util.GUtil

/**
 * Utility classes.
 */
@CompileStatic
class Utils {
  /**
   * Returns the conventional name of a configuration for a sourceSet
   */
  static String getConfigName(String sourceSetName, String type) {
    // same as DefaultSourceSet.configurationNameOf
    String baseName = sourceSetName == SourceSet.MAIN_SOURCE_SET_NAME ?
            '' : GUtil.toCamelCase(sourceSetName)
    return StringUtils.uncapitalize(baseName + StringUtils.capitalize(type))
  }

  /**
   * Returns the conventional substring that represents the sourceSet in task names,
   * e.g., "generate<sourceSetSubstring>Proto"
   */
  static String getSourceSetSubstringForTaskNames(String sourceSetName) {
    return sourceSetName == SourceSet.MAIN_SOURCE_SET_NAME ?
        '' : GUtil.toCamelCase(sourceSetName)
  }

  private static final String ANDROID_BASE_PLUGIN_ID = "com.android.base"
  private static final List<String> ANDROID_PLUGIN_IDS = [
      'android',
      'android-library',
      'com.android.application',
      'com.android.feature',
      'com.android.instantapp',
      'com.android.library',
      'com.android.test',
  ]

  /**
   * Detects if an android plugin has been applied to the project
   */
  static boolean isAndroidProject(Project project) {
    // Projects are marked with com.android.base plugin from version 3.0.0 up
    // OR fall back to a list of plugin id's to support versions prior to 3.0.0
    return project.plugins.hasPlugin(ANDROID_BASE_PLUGIN_ID) ||
        ANDROID_PLUGIN_IDS.any { String pluginId ->
          project.plugins.hasPlugin(pluginId)
        }
  }

  /**
   * Returns the compile task name for Kotlin.
   */
  static String getKotlinAndroidCompileTaskName(Project project, String variantName) {
    // The kotlin plugin does not provide a utility for this.
    // Fortunately, the naming scheme is well defined:
    // https://kotlinlang.org/docs/reference/using-gradle.html#compiler-options
    Preconditions.checkState(isAndroidProject(project))
    return "compile" + GUtil.toCamelCase(variantName) + "Kotlin"
  }

  /**
   * Returns true if the source set is a test related source set.
   */
  static boolean isTest(String sourceSetOrVariantName) {
    return sourceSetOrVariantName == "test" ||
        sourceSetOrVariantName.toLowerCase().contains('androidtest') ||
        sourceSetOrVariantName.toLowerCase().contains('unittest')
  }

  /**
   * Adds the file to the IDE plugin's set of sources / resources. If the directory does
   * not exist, it will be created before the IDE task is run.
   */
  static void addToIdeSources(Project project, boolean isTest, File f, boolean isGenerated) {
    project.plugins.withId("idea") {
      IdeaModel model = project.getExtensions().findByType(IdeaModel)
      if (isTest) {
        model.module.testSourceDirs += f
      } else {
        model.module.sourceDirs += f
      }
      if (isGenerated) {
        model.module.generatedSourceDirs += f
      }
      project.tasks.withType(GenerateIdeaModule).each {
        it.doFirst {
          // This is required because the intellij plugin does not allow adding source directories
          // that do not exist. The intellij config files should be valid from the start even if a
          // user runs './gradlew idea' before running './gradlew generateProto'.
          f.mkdirs()
        }
      }
    }
  }

  /**
   * Add the the folder of generated source to Eclipse .classpath file.
   */
  static void addToEclipseSources(Project project, boolean isTest, String sourceSetName, File f) {
    project.plugins.withId("eclipse") {
      File projectDir = project.getProjectDir()

      EclipseModel model = project.getExtensions().findByType(EclipseModel)
      model.classpath.file.whenMerged { Classpath cp ->
        // buildship requires the folder exists on disk, otherwise
        // it will be ignored when updating classpath file, see:
        // https://github.com/eclipse/buildship/issues/1196
        f.mkdirs()

        String relativePath = projectDir.toURI().relativize(f.toURI()).getPath()
        // remove trailing slash
        if (relativePath.endsWith("/")) {
          relativePath = relativePath.substring(0, relativePath.length() - 1);
        }
        SourceFolder entry = new SourceFolder(relativePath, getOutputPath(cp, sourceSetName))
        entry.entryAttributes.put("optional", "true")
        entry.entryAttributes.put("ignore_optional_problems", "true")

        entry.entryAttributes.put("gradle_scope", isTest ? "test" : "main")
        entry.entryAttributes.put("gradle_used_by_scope", isTest ? "test" : "main,test")

        // this attribute is optional, but it could be useful for IDEs to recognize that it is
        // generated source folder from protobuf, thus providing some support for that.
        // e.g. Hint user to run generate tasks if the folder is empty or does not exist.
        entry.entryAttributes.put("protobuf_generated_source", "true")

        // check if output is not null here because test source folder
        // must have a separate output folder in Eclipse
        if (entry.output != null && isTest) {
          entry.entryAttributes.put("test", "true")
        }
        cp.entries.add(entry)
      }
    }
  }

  /**
   * Get the output path according to the source set name, if no valid path can be
   * found, <code>null</code> will return.
   */
  private static String getOutputPath(Classpath classpath, String sourceSetName) {
    String path = "bin/" + sourceSetName
    if (isValidOutput(classpath, path)) {
      return path
    }
    // fallback to default output
    return null
  }

  /**
   * Check if the output path is valid or not.
   * See: org.eclipse.jdt.internal.core.ClasspathEntry#validateClasspath()
   */
  private static boolean isValidOutput(Classpath classpath, String path) {
    Set<String> outputs = new HashSet<>()
    for (ClasspathEntry cpe : classpath.getEntries()) {
      if (cpe instanceof SourceFolder) {
        outputs.add(((SourceFolder) cpe).getOutput())
      }
    }
    for (String output : outputs) {
      if (Objects.equals(output, path)) {
        continue
      }
      // Eclipse does not allow nested output path
      if (output.startsWith(path) || path.startsWith(output)) {
        return false
      }
    }
    return true
  }
}
