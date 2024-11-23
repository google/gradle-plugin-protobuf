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

import com.google.gradle.osdetector.OsDetector
import groovy.transform.CompileStatic
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Holds locations of all external executables, i.e., protoc and plugins.
 */
@CompileStatic
class ToolsLocator {

  final ExecutableLocator protoc
  final NamedDomainObjectContainer<ExecutableLocator> plugins

  static List<String> artifactParts(String artifactCoordinate) {
    String artifact
    String extension
    String group
    String name
    String version
    String classifier

    List<String> artifactCoordinateTokenized = artifactCoordinate.tokenize('@')
    (artifact, extension) = [artifactCoordinateTokenized[0], artifactCoordinateTokenized[1]]
    if (extension == null && artifactCoordinate.endsWith('@')) {
      extension = ''
    }
    List<String> artifactTokenized = artifact.tokenize(':')
    (group, name, version, classifier) =
      [artifactTokenized[0], artifactTokenized[1], artifactTokenized[2], artifactTokenized[3]]

    return [group, name, version, classifier, extension]
  }

  ToolsLocator(Project project) {
    protoc = new ExecutableLocator('protoc')
    plugins = project.container(ExecutableLocator)
  }

  /**
   * For every ExecutableLocator that points to an artifact spec: creates a
   * project configuration dependency for that artifact, registers the
   * configuration dependency as an input dependency with the specified tasks,
   * and adds a doFirst {} block to the specified tasks which resolves the
   * spec, downloads the artifact, and point to the local path.
   */
  void resolve(Project project) {
    if (protoc.artifact != null) {
      resolveLocator(project, protoc)
    } else if (protoc.path == null) {
      protoc.path = 'protoc'
    }
    for (ExecutableLocator pluginLocator in plugins) {
      if (pluginLocator.artifact != null) {
        resolveLocator(project, pluginLocator)
      } else if (pluginLocator.path == null) {
        pluginLocator.path = "protoc-gen-${pluginLocator.name}"
      }
    }
  }

  private void resolveLocator(Project project, ExecutableLocator locator) {
    // create a project configuration dependency for the artifact
    Configuration config = project.configurations.create("protobufToolsLocator_${locator.name}") { Configuration conf ->
      conf.visible = false
      conf.transitive = false
    }
    String groupId, artifact, version, classifier, extension
    OsDetector osdetector = project.extensions.getByName("osdetector") as OsDetector
    List<String> parts = artifactParts(locator.artifact)
    (groupId, artifact, version, classifier, extension) = [parts[0], parts[1], parts[2], parts[3], parts[4]]
    Map<String, String> notation = [
            group:groupId,
            name:artifact,
            version:version,
            classifier:classifier ?: osdetector.classifier,
            ext:extension ?: 'exe',
    ]
    project.dependencies.add(config.name, notation)
    locator.resolve(config, "$groupId:$artifact:$version".toString())
  }
}
