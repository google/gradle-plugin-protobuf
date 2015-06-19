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

import org.gradle.api.Named

/**
 * Locates an executable that can either be found locally or downloaded from
 * repositories.  If configured multiple times, the last call wins.  If never
 * configured, will run from system search path.
 */
public class ExecutableLocator implements Named {

  private final String name

  @Nullable
  private String artifactSpec

  @Nullable
  private String path

  public ExecutableLocator(String name) {
    this.name = name
  }

  @Override
  public String getName() {
    return name
  }

  /**
   * Specifies an executable to be downloaded from repositories.
   * spec format: '<groupId>:<artifactId>:<version>'
   */
  public artifact(String spec) {
    this.artifactSpec = spec
    this.path = null
  }

  /**
   * Specifies a local executable.
   */
  public executable(String path) {
    this.path = path
    this.artifactSpec = null
  }

  @Nullable
  public String getArtifactSpec() {
    return artifactSpec
  }

  @Nullable
  public String getExecutablePath() {
    return path
  }
}