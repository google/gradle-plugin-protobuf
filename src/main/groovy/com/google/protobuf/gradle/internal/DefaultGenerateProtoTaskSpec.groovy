package com.google.protobuf.gradle.internal

import com.google.protobuf.gradle.tasks.DescriptorSetSpec
import com.google.protobuf.gradle.tasks.GenerateProtoTaskSpec
import com.google.protobuf.gradle.tasks.PluginSpec
import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.util.ConfigureUtil

@CompileStatic
@SuppressWarnings("JUnitPublicNonTestMethod") // it is not a test class
class DefaultGenerateProtoTaskSpec implements GenerateProtoTaskSpec {
  private final NamedDomainObjectContainer<PluginSpec> plugins
  private final NamedDomainObjectContainer<PluginSpec> builtins
  private final DescriptorSetSpec descriptorSetSpec
  private final Property<String> outputDir
  private final String variantName
  private boolean generateDescriptorSet = false

  DefaultGenerateProtoTaskSpec(String variantName, ObjectFactory objects) {
    NamedDomainObjectFactory<PluginSpec> pluginSpecObjectFactory = new PluginSpecObjectFactory(objects)
    this.plugins = objects.domainObjectContainer(PluginSpec, pluginSpecObjectFactory)
    this.builtins = objects.domainObjectContainer(PluginSpec, pluginSpecObjectFactory)
    this.outputDir = objects.property(String)
    this.descriptorSetSpec = new DefaultDescriptorSetSpec(objects)
    this.variantName = variantName
  }

  @Override
  String getVariantName() {
    return this.variantName
  }

  @Override
  Property<String> getOutputDir() {
    return this.outputDir
  }

  @Override
  @Deprecated
  void setOutputBaseDir(Provider<String> outputBaseDir) {
    this.outputDir.set(outputBaseDir)
  }

  @Override
  boolean getGenerateDescriptorSet() {
    return generateDescriptorSet
  }

  @Override
  void setGenerateDescriptorSet(boolean enabled) {
    this.generateDescriptorSet = enabled
  }

  @Override
  DescriptorSetSpec getDescriptorSetOptions() {
    return this.descriptorSetSpec
  }

  @Override
  NamedDomainObjectContainer<PluginSpec> getPlugins() {
    return this.plugins
  }

  @Override
  NamedDomainObjectContainer<PluginSpec> getBuiltins() {
    return this.builtins
  }

  @Override
  boolean hasPlugin(String name) {
    return plugins.findByName(name) != null
  }

  @Override
  void builtins(Action<NamedDomainObjectContainer<PluginSpec>> configureAction) {
    configureAction.execute(builtins)
  }

  @Override
  void builtins(Closure<NamedDomainObjectContainer<PluginSpec>> closure) {
    ConfigureUtil.configure(closure, builtins)
  }

  @Override
  void plugins(Action<NamedDomainObjectContainer<PluginSpec>> configureAction) {
    configureAction.execute(plugins)
  }

  @Override
  void plugins(Closure<NamedDomainObjectContainer<PluginSpec>> closure) {
    ConfigureUtil.configure(closure, plugins)
  }

  @Override
  void generateDescriptorSet(@DelegatesTo(DescriptorSetSpec) Action<DescriptorSetSpec> configureAction) {
    configureAction.execute(descriptorSetSpec)
  }

  @Override
  void generateDescriptorSet(Closure<DescriptorSetSpec> closure) {
    ConfigureUtil.configure(closure, descriptorSetSpec)
  }
}
