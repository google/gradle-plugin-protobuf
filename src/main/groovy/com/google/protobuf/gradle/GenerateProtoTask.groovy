/*
 * Original work copyright (c) 2015, Alex Antonov. All rights reserved.
 * Modified work copyright (c) 2015, Google Inc. All rights reserved.
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

import static java.nio.charset.StandardCharsets.US_ASCII

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.logging.LogLevel
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction

import javax.annotation.Nullable
import javax.inject.Inject

/**
 * The task that compiles proto files into Java files.
 */
// TODO(zhangkun83): add per-plugin output dir reconfiguraiton.
@CompileStatic
@CacheableTask
public abstract class GenerateProtoTask extends DefaultTask {
  // Windows CreateProcess has command line limit of 32768:
  // https://msdn.microsoft.com/en-us/library/windows/desktop/ms682425(v=vs.85).aspx
  static final int WINDOWS_CMD_LENGTH_LIMIT = 32760
  // Most OSs impose some kind of command length limit.
  // Rather than account for all cases, pick a reasonable default of 64K.
  static final int DEFAULT_CMD_LENGTH_LIMIT = 65536
  // Extra command line length when added an additional argument on Windows.
  // Two quotes and a space.
  static final int CMD_ARGUMENT_EXTRA_LENGTH = 3
  private static final String JAR_SUFFIX = ".jar"

  private final CopyActionFacade copyActionFacade = CopyActionFacade.Loader.create(project, objectFactory)
  // include dirs are passed to the '-I' option of protoc.  They contain protos
  // that may be "imported" from the source protos, but will not be compiled.
  private final ConfigurableFileCollection includeDirs = objectFactory.fileCollection()
  // source files are proto files that will be compiled by protoc
  private final ConfigurableFileCollection sourceDirs = objectFactory.fileCollection()
  private final NamedDomainObjectContainer<PluginOptions> builtins = objectFactory.domainObjectContainer(PluginOptions)
  private final NamedDomainObjectContainer<PluginOptions> plugins = objectFactory.domainObjectContainer(PluginOptions)
  private final ProjectLayout projectLayout = project.layout
  private final ToolsLocator toolsLocator = project.extensions.findByType(ProtobufExtension).tools

  @Input
  final Property<String> javaExecutablePath = objectFactory.property(String)
          .convention(project.extensions.findByType(ProtobufExtension).javaExecutablePath)

  // These fields are set by the Protobuf plugin only when initializing the
  // task.  Ideally they should be final fields, but Gradle task cannot have
  // constructor arguments. We use the initializing flag to prevent users from
  // accidentally modifying them.
  private Provider<String> outputBaseDir
  // Tags for selectors inside protobuf.generateProtoTasks; do not serialize with Gradle configuration caching
  @SuppressWarnings("UnnecessaryTransientModifier") // It is not necessary for task to implement Serializable
  transient private SourceSet sourceSet
  @SuppressWarnings("UnnecessaryTransientModifier") // It is not necessary for task to implement Serializable
  transient private Object variant
  private List<String> flavors
  private String buildType
  private boolean isTestVariant
  private final Provider<Boolean> isAndroidProject = providerFactory.provider { Utils.isAndroidProject(project) }
  private final Provider<Boolean> isTestProvider = providerFactory.provider {
    if (Utils.isAndroidProject(project)) {
      return isTestVariant
    }
    return Utils.isTest(sourceSet.name)
  }

  /**
   * If true, will set the protoc flag
   * --descriptor_set_out="${outputBaseDir}/descriptor_set.desc"
   *
   * Default: false
   */
  @Internal("Handled as input via getDescriptorSetOptionsForCaching()")
  boolean generateDescriptorSet

  /**
   * Configuration object for descriptor generation details.
   */
  public class DescriptorSetOptions {
    /**
     * If set, specifies an alternative location than the default for storing the descriptor
     * set.
     *
     * Default: null
     */
    @Nullable
    @Optional
    @OutputFile
    String path

    /**
     * If true, source information (comments, locations) will be included in the descriptor set.
     *
     * Default: false
     */
    @Input
    boolean includeSourceInfo

    /**
     * If true, imports are included in the descriptor set, such that it is self-containing.
     *
     * Default: false
     */
    @Input
    boolean includeImports
  }

  @Internal("Handled as input via getDescriptorSetOptionsForCaching()")
  final DescriptorSetOptions descriptorSetOptions = new DescriptorSetOptions()

  // protoc allows you to prefix comma-delimited options to the path in
  // the --*_out flags, e.g.,
  // - Without options: --java_out=/path/to/output
  // - With options: --java_out=option1,option2:/path/to/output
  // This method generates the prefix out of the given options.
  static String makeOptionsPrefix(List<String> options) {
    StringBuilder prefix = new StringBuilder()
    if (!options.isEmpty()) {
      options.each { option ->
        if (prefix.length() > 0) {
          prefix.append(',')
        }
        prefix.append(option)
      }
      prefix.append(':')
    }
    return prefix.toString()
  }

  static List<List<String>> generateCmds(List<String> baseCmd, List<File> protoFiles, int cmdLengthLimit) {
    List<List<String>> cmds = []
    if (!protoFiles.isEmpty()) {
      int baseCmdLength = baseCmd.sum { it.length() + CMD_ARGUMENT_EXTRA_LENGTH } as int
      List<String> currentArgs = []
      int currentArgsLength = 0
      for (File proto: protoFiles) {
        String protoFileName = proto
        int currentFileLength = protoFileName.length() + CMD_ARGUMENT_EXTRA_LENGTH
        // Check if appending the next proto string will overflow the cmd length limit
        if (baseCmdLength + currentArgsLength + currentFileLength > cmdLengthLimit) {
          // Add the current cmd before overflow
          cmds.add(baseCmd + currentArgs)
          currentArgs.clear()
          currentArgsLength = 0
        }
        // Append the proto file to the args
        currentArgs.add(protoFileName)
        currentArgsLength += currentFileLength
      }
      // Add the last cmd for execution
      cmds.add(baseCmd + currentArgs)
    }
    return cmds
  }

  static int getCmdLengthLimit() {
    return getCmdLengthLimit(System.getProperty("os.name"))
  }

  static int getCmdLengthLimit(String os) {
    return Utils.isWindows(os) ? WINDOWS_CMD_LENGTH_LIMIT : DEFAULT_CMD_LENGTH_LIMIT
  }

  static String escapePathUnix(String path) {
    return path.replace("'", "'\\''")
  }

  static String escapePathWindows(String path) {
    String escapedPath = path.replace("%", "%%")
    return escapedPath.endsWith("\\") ? escapedPath + "\\" : escapedPath
  }

  static void mkdirsForFile(File outputFile) throws IOException {
    if (!outputFile.getParentFile().isDirectory() && !outputFile.getParentFile().mkdirs()) {
      throw new IOException("unable to make directories for file: " + outputFile.getCanonicalPath())
    }
  }

  static void setExecutableOrFail(File outputFile) throws IOException {
    if (!outputFile.setExecutable(true)) {
      outputFile.delete()
      throw new IOException("unable to set file as executable: " + outputFile.getCanonicalPath())
    }
  }

  void setOutputBaseDir(Provider<String> outputBaseDir) {
    checkInitializing()
    Preconditions.checkState(this.outputBaseDir == null, 'outputBaseDir is already set')
    this.outputBaseDir = outputBaseDir
  }

  @OutputDirectory
  String getOutputBaseDir() {
    return outputBaseDir.get()
  }

  void setSourceSet(SourceSet sourceSet) {
    checkInitializing()
    Preconditions.checkState(!isAndroidProject.get(),
        'sourceSet should not be set in an Android project')
    this.sourceSet = sourceSet
  }

  void setVariant(Object variant, boolean isTestVariant) {
    checkInitializing()
    Preconditions.checkState(isAndroidProject.get(),
        'variant should not be set in a Java project')
    this.variant = variant
    this.isTestVariant = isTestVariant
  }

  void setFlavors(List<String> flavors) {
    checkInitializing()
    Preconditions.checkState(isAndroidProject.get(),
        'flavors should not be set in a Java project')
    this.flavors = Collections.unmodifiableList(new ArrayList<String>(flavors))
  }

  void setBuildType(String buildType) {
    checkInitializing()
    Preconditions.checkState(isAndroidProject.get(),
        'buildType should not be set in a Java project')
    this.buildType = buildType
  }

  @Internal("Inputs tracked in getSourceDirs()")
  SourceSet getSourceSet() {
    Preconditions.checkState(!isAndroidProject.get(),
        'sourceSet should not be used in an Android project')
    Preconditions.checkNotNull(sourceSet, 'sourceSet is not set')
    return sourceSet
  }

  @SkipWhenEmpty
  @PathSensitive(PathSensitivity.RELATIVE)
  @IgnoreEmptyDirectories
  @InputFiles
  FileCollection getSourceDirs() {
    return sourceDirs
  }

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  FileCollection getIncludeDirs() {
    return includeDirs
  }

  @Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
  Object getVariant() {
    Preconditions.checkState(isAndroidProject.get(),
        'variant should not be used in a Java project')
    Preconditions.checkNotNull(variant, 'variant is not set')
    return variant
  }

  /**
   * Not for external use. Used to expose inputs to Gradle.
   *
   * For each protoc and code gen plugin defined by an artifact specification, this list will contain a String with the
   * group, artifact, and version, as long as the version is a stable release version.
   *
   * Giving this as an input to the task allows gradle to ignore the OS classifier and use cached outputs generated from
   * different operating systems since the expectation is that different operating systems will produce the same
   * generated code.
   */
  @Input
  Provider<List<String>> getReleaseArtifacts() {
    providerFactory.provider {
      releaseExecutableLocators.collect { it.simplifiedArtifactName }
    }
  }

  /** Not for external use. Used to expose inputs to Gradle. */
  @InputFiles
  @PathSensitive(PathSensitivity.NONE)
  FileCollection getExecutables() {
    Provider<List> executables = providerFactory.provider {
      List<ExecutableLocator> release = releaseExecutableLocators
      allExecutableLocators.findAll { !release.contains(it) }
        .collect { it.path != null ? it.path : it.artifactFiles }
    }
    objectFactory.fileCollection().from(executables)
  }

  private List<ExecutableLocator> getReleaseExecutableLocators() {
    allExecutableLocators.findAll { it.path == null && !it.simplifiedArtifactName.endsWith ("-SNAPSHOT") }
  }

  private List<ExecutableLocator> getAllExecutableLocators() {
    [toolsLocator.protoc] + plugins.findResults { PluginOptions it -> toolsLocator.plugins.findByName(it.name) }
  }

  @Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
  Provider<Boolean> getIsAndroidProject() {
    return isAndroidProject
  }

  @Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
  boolean getIsTestVariant() {
    Preconditions.checkState(isAndroidProject.get(),
        'isTestVariant should not be used in a Java project')
    Preconditions.checkNotNull(variant, 'variant is not set')
    return isTestVariant
  }

  @Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
  List<String> getFlavors() {
    Preconditions.checkState(isAndroidProject.get(),
        'flavors should not be used in a Java project')
    Preconditions.checkNotNull(flavors, 'flavors is not set')
    return flavors
  }

  @TypeChecked(TypeCheckingMode.SKIP) // Don't depend on AGP
  @Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
  String getBuildType() {
    Preconditions.checkState(isAndroidProject.get(),
        'buildType should not be used in a Java project')
    Preconditions.checkState(
        variant.name == 'test' || buildType,
        'buildType is not set and task is not for local unit test variant')
    return buildType
  }

  void doneInitializing() {
    Preconditions.checkState(state == State.INIT, "Invalid state: ${state}")
    state = State.CONFIG
  }

  void doneConfig() {
    Preconditions.checkState(state == State.CONFIG, "Invalid state: ${state}")
    state = State.FINALIZED
  }

  @Internal("Tracked as an input via getDescriptorSetOptionsForCaching()")
  String getDescriptorPath() {
    if (!generateDescriptorSet) {
      throw new IllegalStateException(
          "requested descriptor path but descriptor generation is off")
    }
    return descriptorSetOptions.path != null ? descriptorSetOptions.path : "${outputBaseDir.get()}/descriptor_set.desc"
  }

  @Inject
  abstract ProviderFactory getProviderFactory()

  @Inject
  abstract ObjectFactory getObjectFactory()

  //===========================================================================
  //        Configuration methods
  //===========================================================================

  /**
   * Configures the protoc builtins in a closure, which will be manipulating a
   * NamedDomainObjectContainer<PluginOptions>.
   */
  public void builtins(Action<NamedDomainObjectContainer<PluginOptions>> configureAction) {
    checkCanConfig()
    configureAction.execute(this.builtins)
  }

  /**
   * Returns the container of protoc builtins.
   */
  @Internal("Tracked as an input via getBuiltinsForCaching()")
  public NamedDomainObjectContainer<PluginOptions> getBuiltins() {
    checkCanConfig()
    return builtins
  }

  /**
   * Configures the protoc plugins in a closure, which will be maniuplating a
   * NamedDomainObjectContainer<PluginOptions>.
   */
  public void plugins(Action<NamedDomainObjectContainer<PluginOptions>> configureAction) {
    checkCanConfig()
    configureAction.execute(this.plugins)
  }

  /**
   * Returns the container of protoc plugins.
   */
  @Internal("Tracked as an input via getPluginsForCaching()")
  public NamedDomainObjectContainer<PluginOptions> getPlugins() {
    checkCanConfig()
    return plugins
  }

  /**
   * Returns true if the task has a plugin with the given name, false otherwise.
   */
  public boolean hasPlugin(String name) {
    return plugins.findByName(name) != null
  }

  /**
   * Add a directory to protoc's include path.
   */
  public void addIncludeDir(FileCollection dir) {
    checkCanConfig()
    includeDirs.from(dir)
  }

  /**
   * Add a collection of proto source files to be compiled.
   */
  public void addSourceDirs(FileCollection dirs) {
    checkCanConfig()
    sourceDirs.from(dirs)
  }

  /**
   * Returns true if the Java source set or Android variant is test related.
   */
  @Internal("Not an actual input to the task, only used to find tasks of interest")
  public boolean getIsTest() {
    return isTestProvider.get()
  }

  @Internal("Already captured with getIsTest()")
  Provider<Boolean> getIsTestProvider() {
    return isTestProvider
  }

  /**
   * The container of command-line options for a protoc plugin or a built-in output.
   */
  public static class PluginOptions implements Named {
    private final List<String> options = []
    private final String name
    private String outputSubDir

    public PluginOptions(String name) {
      this.name = name
    }

    /**
     * Adds a plugin option.
     */
    public PluginOptions option(String option) {
      options.add(option)
      return this
    }

    @Input
    public List<String> getOptions() {
      return options
    }

    /**
     * Returns the name of the plugin or builtin.
     */
    @Input
    @Override
    public String getName() {
      return name
    }

    /**
     * Set the output directory for this plugin, relative to {@link GenerateProtoTask#outputBaseDir}.
     */
    void setOutputSubDir(String outputSubDir) {
      this.outputSubDir = outputSubDir
    }

    /**
     * Returns the relative outputDir for this plugin.  If outputDir is not specified, name is used.
     */
    @Input
    public String getOutputSubDir() {
      if (outputSubDir != null) {
        return outputSubDir
      }
      return name
    }
  }

  //===========================================================================
  //    protoc invocation logic
  //===========================================================================

  String getOutputDir(PluginOptions plugin) {
    return "${outputBaseDir.get()}/${plugin.outputSubDir}"
  }

  /**
   * Returns a {@code SourceDirectorySet} representing the generated source
   * directories.
   */
  @Internal
  @Deprecated
  SourceDirectorySet getOutputSourceDirectorySet() {
    String srcSetName = "generate-proto-" + name
    SourceDirectorySet srcSet
    srcSet = objectFactory.sourceDirectorySet(srcSetName, srcSetName)
    srcSet.srcDirs objectFactory.fileCollection().builtBy(this).from(providerFactory.provider {
      getOutputSourceDirectories()
    })
    return srcSet
  }

  @Internal
  @PackageScope
  Collection<File> getOutputSourceDirectories() {
    // insertion point requires the same output source directories as the java plugin. Using a set to avoid duplication.
    Collection<File> srcDirs = [] as Set
    builtins.each { builtin ->
      File dir = new File(getOutputDir(builtin))
      if (!dir.name.endsWith(".zip") && !dir.name.endsWith(".jar")) {
        srcDirs.add(dir)
      }
    }
    plugins.each { plugin ->
      File dir = new File(getOutputDir(plugin))
      if (!dir.name.endsWith(".zip") && !dir.name.endsWith(".jar")) {
        srcDirs.add(dir)
      }
    }
    return srcDirs
  }

  @TaskAction
  void compile() {
    Preconditions.checkState(state == State.FINALIZED, 'doneConfig() has not been called')

    copyActionFacade.delete { spec ->
      spec.delete(outputBaseDir)
    }
    // Sort to ensure generated descriptors have a canonical representation
    // to avoid triggering unnecessary rebuilds downstream
    List<File> protoFiles = sourceDirs.asFileTree.files.sort()

    [builtins, plugins]*.forEach { PluginOptions plugin ->
      String outputPath = getOutputDir(plugin)
      File outputDir = new File(outputPath)
      // protoc is capable of output generated files directly to a JAR file
      // or ZIP archive if the output location ends with .jar/.zip
      if (outputPath.endsWith(".jar") || outputPath.endsWith(".zip")) {
        outputDir = outputDir.getParentFile()
      }
      outputDir.mkdirs()
    }

    // The source directory designated from sourceSet may not actually exist on disk.
    // "include" it only when it exists, so that Gradle and protoc won't complain.
    List<String> dirs = includeDirs.filter { File it -> it.exists() }*.path
        .collect { "-I${it}".toString() }
    logger.debug "ProtobufCompile using directories ${dirs}"
    logger.debug "ProtobufCompile using files ${protoFiles}"

    String protocPath = computeExecutablePath(toolsLocator.protoc)
    List<String> baseCmd = [ protocPath ]
    baseCmd.addAll(dirs)

    // Handle code generation built-ins
    builtins.each { builtin ->
      String outPrefix = makeOptionsPrefix(builtin.options)
      baseCmd += "--${builtin.name}_out=${outPrefix}${getOutputDir(builtin)}".toString()
    }

    Map<String, ExecutableLocator> executableLocations = toolsLocator.plugins.asMap
    // Handle code generation plugins
    plugins.each { plugin ->
      String name = plugin.name
      ExecutableLocator locator = executableLocations.get(name)
      if (locator != null) {
        baseCmd += "--plugin=protoc-gen-${name}=${computeExecutablePath(locator)}".toString()
      } else {
        logger.warn "protoc plugin '${name}' not defined. Trying to use 'protoc-gen-${name}' from system path"
      }
      String pluginOutPrefix = makeOptionsPrefix(plugin.options)
      baseCmd += "--${name}_out=${pluginOutPrefix}${getOutputDir(plugin)}".toString()
    }

    if (generateDescriptorSet) {
      String path = getDescriptorPath()
      // Ensure that the folder for the descriptor exists;
      // the user may have set it to point outside an existing tree
      File folder = new File(path).parentFile
      if (!folder.exists()) {
        folder.mkdirs()
      }
      baseCmd += "--descriptor_set_out=${path}".toString()
      if (descriptorSetOptions.includeImports) {
        baseCmd += "--include_imports"
      }
      if (descriptorSetOptions.includeSourceInfo) {
        baseCmd += "--include_source_info"
      }
    }

    List<List<String>> cmds = generateCmds(baseCmd, protoFiles, getCmdLengthLimit())
    for (List<String> cmd : cmds) {
      compileFiles(cmd)
    }
  }

  /**
   * Used to expose inputs to Gradle, not to be called directly.
   */
  @Optional
  @Nested
  protected DescriptorSetOptions getDescriptorSetOptionsForCaching() {
    return generateDescriptorSet ? descriptorSetOptions : null
  }

  /**
   * Used to expose inputs to Gradle, not to be called directly.
   */
  @Nested
  protected Collection<PluginOptions> getBuiltinsForCaching() {
    return builtins
  }

  /**
   * Used to expose inputs to Gradle, not to be called directly.
   */
  @Nested
  protected Collection<PluginOptions> getPluginsForCaching() {
    return plugins
  }

  private static enum State {
    INIT, CONFIG, FINALIZED
  }

  private State state = State.INIT

  private void checkInitializing() {
    Preconditions.checkState(state == State.INIT, 'Should not be called after initilization has finished')
  }

  private void checkCanConfig() {
    Preconditions.checkState(state == State.CONFIG || state == State.INIT,
        'Should not be called after configuration has finished')
  }

  private void compileFiles(List<String> cmd) {
    logger.log(LogLevel.INFO, cmd.toString())

    StringBuffer stdout = new StringBuffer()
    StringBuffer stderr = new StringBuffer()
    Process result = cmd.execute()
    result.waitForProcessOutput(stdout, stderr)
    String output = "protoc: stdout: ${stdout}. stderr: ${stderr}"
    if (result.exitValue() == 0) {
      logger.log(LogLevel.INFO, output)
    } else {
      throw new GradleException(output)
    }
  }

  protected String computeExecutablePath(ExecutableLocator locator) {
    if (locator.path != null) {
      return locator.path.endsWith(JAR_SUFFIX) ?
              createJarTrampolineScript(locator.path, locator.mainClass) :
              locator.path
    }
    File file = locator.artifactFiles.singleFile
    if (file.name.endsWith(JAR_SUFFIX)) {
      return createJarTrampolineScript(file.getAbsolutePath(), locator.mainClass)
    }

    if (!file.canExecute() && !file.setExecutable(true)) {
      throw new GradleException("Cannot set ${file} as executable")
    }
    logger.info("Resolved artifact: ${file}")
    return file.path
  }

  /**
   * protoc only supports plugins that are a single self contained executable file. For .jar files create a trampoline
   * script to execute the jar file. Assume the jar is a "fat jar" or "uber jar" and don't attempt any artifact
   * resolution.
   * @param jarAbsolutePath Absolute path to the .jar file.
   * @return The absolute path to the trampoline executable script.
   */
  private String createJarTrampolineScript(String jarAbsolutePath, String mainClass) {
    assert jarAbsolutePath.endsWith(JAR_SUFFIX)
    boolean isWindows = Utils.isWindows()
    String jarFileName = new File(jarAbsolutePath).getName()
    if (jarFileName.length() <= JAR_SUFFIX.length()) {
      throw new GradleException(".jar protoc plugin path '${jarAbsolutePath}' has no file name")
    }
    File scriptExecutableFile = new File("${projectLayout.buildDirectory.get()}/scripts/" +
            jarFileName[0..(jarFileName.length() - JAR_SUFFIX.length() - 1)] + "-${getName()}-trampoline." +
            (isWindows ? "bat" : "sh"))
    try {
      mkdirsForFile(scriptExecutableFile)
      String javaExe = javaExecutablePath.get()
      // Rewrite the trampoline file unconditionally (even if it already exists) in case the dependency or versioning
      // changes we don't need to detect the delta (and the file content is cheap to re-generate).
      String trampoline = isWindows ?
              "@ECHO OFF\r\n\"${escapePathWindows(javaExe)}\" ${mainClass ? "-cp" : "-jar"} " +
                      "\"${escapePathWindows(jarAbsolutePath)}\" \"${mainClass}\" %*\r\n" :
              "#!/bin/sh\nexec '${escapePathUnix(javaExe)}' ${mainClass ? "-cp" : "-jar"} " +
                      "'${escapePathUnix(jarAbsolutePath)}' '${mainClass}' \"\$@\"\n"
      scriptExecutableFile.write(trampoline, US_ASCII.name())
      setExecutableOrFail(scriptExecutableFile)
      logger.info("Resolved artifact jar: ${jarAbsolutePath}. " +
              "Created trampoline file: ${scriptExecutableFile} with java executable ${javaExe}")
      return scriptExecutableFile.path
    } catch (IOException e) {
      throw new GradleException("Unable to generate trampoline for .jar protoc plugin", e)
    }
  }
}
