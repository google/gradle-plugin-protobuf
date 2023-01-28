package com.google.protobuf.gradle.tasks

import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory

@CompileStatic
@SuppressWarnings("JUnitPublicNonTestMethod") // it is not a test class
interface GenerateProtoTaskSpec {

  @OutputDirectory
  Property<String> getOutputDir()

  @Deprecated
  void setOutputBaseDir(Provider<String> outputBaseDir)

  @Input
  String getVariantName()

  /**
   * If true, will set the protoc flag
   * --descriptor_set_out="${outputBaseDir}/descriptor_set.desc"
   *
   * Default: false
   */
  @Input
  boolean getGenerateDescriptorSet()

  void setGenerateDescriptorSet(boolean enabled)

  @Nested
  DescriptorSetSpec getDescriptorSetOptions()

  /**
   * Returns the container of protoc plugins.
   */
  @Nested
  NamedDomainObjectContainer<PluginSpec> getPlugins()

  /**
   * Returns the container of protoc builtins.
   */
  @Nested
  NamedDomainObjectContainer<PluginSpec> getBuiltins()

  /**
   * Returns true if the task has a plugin with the given name, false otherwise.
   */
  boolean hasPlugin(String name)

  /**
   * Configures the protoc builtins in a closure, which will be manipulating a
   * NamedDomainObjectContainer<PluginOptions>.
   */
  void builtins(Action<NamedDomainObjectContainer<PluginSpec>> configureAction)

  /**
   * Configures the protoc builtins in a closure, which will be manipulating a
   * NamedDomainObjectContainer<PluginOptions>.
   */
  void builtins(Closure<NamedDomainObjectContainer<PluginSpec>> closure)

  /**
   * Configures the protoc plugins in a closure, which will be manipulating a
   * NamedDomainObjectContainer<PluginOptions>.
   */
  void plugins(Action<NamedDomainObjectContainer<PluginSpec>> configureAction)

  /**
   * Configures the protoc plugins in a closure, which will be manipulating a
   * NamedDomainObjectContainer<PluginOptions>.
   */
  void plugins(Closure<NamedDomainObjectContainer<PluginSpec>> closure)

  /**
   * Configures the protoc descriptor set in a closure, which will be manipulating a
   * DescriptorSetSpec.
   */
  void generateDescriptorSet(@DelegatesTo(DescriptorSetSpec) Action<DescriptorSetSpec> configureAction)

  /**
   * Configures the protoc descriptor set in a closure, which will be manipulating a
   * DescriptorSetSpec.
   */
  void generateDescriptorSet(Closure<DescriptorSetSpec> closure)
}
