package com.google.protobuf.gradle

import org.apache.commons.io.FileUtils
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

/**
 * Utility class.
 */
final class ProtobufPluginTestHelper {
  private ProtobufPluginTestHelper() {
    // do not instantiate
  }

  static void appendPluginClasspath(File buildFile) {
    URL pluginClasspathResource =
        ProtobufPluginTestHelper.classLoader.findResource("plugin-classpath.txt")
    if (pluginClasspathResource == null) {
      throw new IllegalStateException('Did not find plugin classpath resource, ' +
          'run `testClasses` build task.')
    }

    List<String> pluginClasspath = pluginClasspathResource.readLines()
      .collect { it.replace('\\', '\\\\') } // escape backslashes in Windows paths
      .collect { "'$it'" }

    // Add the logic under test to the test build
    buildFile << """
        buildscript {
            dependencies {
    """

    //android gradle plugin needs guava 22 so check for that before including guava from pluginClasspath
    pluginClasspath.each { dependency ->
        if (dependency.contains("guava")) {
            buildFile << """
                if (!project.hasProperty("androidPluginVersion") ||
                    !project.findProperty("androidPluginVersion").startsWith("3.")) {
                    classpath files($dependency)
                }
            """
        } else {
            buildFile << """
                classpath files($dependency)
            """
        }
    }

    buildFile << """
            }
        }
    """
  }

  static BuildResult buildAndroidProject(
     File mainProjectDir, String androidPluginVersion, String gradleVersion, String fullPathTask) {
    // Prepend android plugin (and guava when appropriate) to the test root project so that Gradle
    // can resolve classpath correctly.

    File buildFile = new File(mainProjectDir, "build.gradle")
    List<String> previousFileContents = buildFile.readLines()
    buildFile.delete()
    buildFile.createNewFile()

    buildFile << """
buildscript {
    ext.androidPluginVersion = "${androidPluginVersion}"
    repositories {
        jcenter()
        maven { url "https://plugins.gradle.org/m2/" }
        maven { url "https://dl.google.com/dl/android/maven2/" }
    }
    dependencies {
        classpath "com.android.tools.build:gradle:\$androidPluginVersion"
        if (androidPluginVersion.startsWith("3.")) {
            //agp 3.+ needs guava 22 but the protobuf project uses a lower version
            classpath 'com.google.guava:guava:22.0'
        }
    }
}
"""
    previousFileContents.each { line ->
      buildFile << line + '\n'
    }

    File localBuildCache = new File(mainProjectDir, ".buildCache")
    if (localBuildCache.exists()) {
      localBuildCache.deleteDir()
    }
    return GradleRunner.create()
       .withProjectDir(mainProjectDir)
       .withArguments(
       // set android build cache to avoid using home directory on travis CI.
       "-Pandroid.buildCacheDir=$localBuildCache",
       fullPathTask,
       "-x", "lint", // linter causes .withDebug(true) to fail
       "--stacktrace")
       .withGradleVersion(gradleVersion)
       .forwardStdOutput(new OutputStreamWriter(System.out))
       .forwardStdError(new OutputStreamWriter(System.err))
       .withDebug(true)
       .build()
  }

  /**
   * Creates a temp test dir with name {@code testProjectName}, which is generated by
   * copying a list of overlay dirs on top of it.
   */
  static TestProjectBuilder projectBuilder(String projectName) {
    return new TestProjectBuilder(projectName)
  }

  static final class TestProjectBuilder {
    String testProjectName
    List<String> sourceDirs = []
    List<File> subProjects = []

    private TestProjectBuilder(String projectName) {
      this.testProjectName = projectName
    }

    TestProjectBuilder copyDirs(String... dirs) {
      sourceDirs.addAll(dirs)
      return this
    }

    TestProjectBuilder copySubProjects(File... subProjects) {
      this.subProjects.addAll(subProjects)
      return this
    }

    File build() {
      File projectDir = new File(System.getProperty('user.dir'), 'build/tests/' + testProjectName)
      FileUtils.deleteDirectory(projectDir)
      FileUtils.forceMkdir(projectDir)
      sourceDirs.each {
        FileUtils.copyDirectory(new File(System.getProperty("user.dir"), it), projectDir)
      }

      if (subProjects) {
        File settingsFile = new File(projectDir, 'settings.gradle')
        settingsFile.createNewFile()

        subProjects.each {
          File subProjectDir = new File(projectDir.path, it.name)
          FileUtils.copyDirectory(it, subProjectDir)

          settingsFile << """
          include ':$it.name'
          project(':$it.name').projectDir = "\$rootDir/$it.name" as File
          """
        }
      }

      File buildFile = new File(projectDir.path, "build.gradle")
      buildFile.createNewFile()
      appendPluginClasspath(buildFile)
      return projectDir
    }
  }
}
