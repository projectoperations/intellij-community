// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import com.intellij.ide.plugins.PluginMainDescriptor.Companion.productModeAliasesForCorePlugin
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.BuildNumber
import com.intellij.platform.plugins.testFramework.PluginSetTestBuilder
import com.intellij.platform.testFramework.PluginBuilder
import com.intellij.testFramework.rules.InMemoryFsRule
import com.intellij.util.io.write
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.intellij.lang.annotations.Language
import org.junit.Rule
import org.junit.Test
import java.util.function.Function

class PluginSetLoadingTest {
  init {
    Logger.setUnitTestMode() // due to warnInProduction use in IdeaPluginDescriptorImpl
  }

  @Rule
  @JvmField
  val inMemoryFs = InMemoryFsRule()

  private val rootPath get() = inMemoryFs.fs.getPath("/")
  private val pluginsDirPath get() = rootPath.resolve("wd/plugins")

  @Test
  fun `use newer plugin`() {
    writeDescriptor("foo_1-0", """
      <idea-plugin>
        <id>foo</id>
        <vendor>JetBrains</vendor>
        <version>1.0</version>
      </idea-plugin>""")
    writeDescriptor("foo_2-0", """
      <idea-plugin>
        <id>foo</id>
        <vendor>JetBrains</vendor>
        <version>2.0</version>
      </idea-plugin>""")

    val pluginSet = PluginSetTestBuilder.fromPath(pluginsDirPath).build()
    val plugins = pluginSet.enabledPlugins
    assertThat(plugins).hasSize(1)
    val foo = plugins[0]
    assertThat(foo.version).isEqualTo("2.0")
    assertThat(foo.pluginId.idString).isEqualTo("foo")

    assertThat(pluginSet.allPlugins.toList()).map(Function { it.pluginId }).containsOnly(foo.pluginId)
    assertThat(pluginSet.findEnabledPlugin(foo.pluginId)).isSameAs(foo)
  }

  @Test
  fun `use newer plugin if disabled`() {
    writeDescriptor("foo_3-0", """
      <idea-plugin>
        <id>foo</id>
        <vendor>JetBrains</vendor>
        <version>1.0</version>
      </idea-plugin>""")
    writeDescriptor("foo_2-0", """
      <idea-plugin>
        <id>foo</id>
        <vendor>JetBrains</vendor>
        <version>2.0</version>
      </idea-plugin>""")

    val (_, result) = PluginSetTestBuilder.fromPath(pluginsDirPath)
      .withDisabledPlugins("foo")
      .buildLoadingResult()

    val incompletePlugins = result.getIncompleteIdMap().values
    assertThat(incompletePlugins).hasSize(1)
    val foo = incompletePlugins.single()
    assertThat(foo.version).isEqualTo("2.0")
    assertThat(foo.pluginId.idString).isEqualTo("foo")
  }

  @Test
  fun `prefer bundled if custom is incompatible`() {
    // names are important - will be loaded in alphabetical order
    writeDescriptor("foo_1-0", """
      <idea-plugin>
        <id>foo</id>
        <vendor>JetBrains</vendor>
        <version>2.0</version>
        <idea-version until-build="2"/>
      </idea-plugin>""")
    writeDescriptor("foo_2-0", """
      <idea-plugin>
        <id>foo</id>
        <vendor>JetBrains</vendor>
        <version>2.0</version>
        <idea-version until-build="4"/>
      </idea-plugin>""")

    val (_, result) = PluginSetTestBuilder.fromPath(pluginsDirPath)
      .withProductBuildNumber(BuildNumber.fromString("4.0")!!)
      .buildLoadingResult()

    assertThat(result.hasPluginErrors).isFalse()
    val plugins = result.enabledPlugins.toList()
    assertThat(plugins).hasSize(1)
    assertThat(result.duplicateModuleMap).isNull()
    assertThat(result.getIncompleteIdMap()).isEmpty()
    val foo = plugins[0]
    assertThat(foo.version).isEqualTo("2.0")
    assertThat(foo.pluginId.idString).isEqualTo("foo")

    assertThat(result.getIdMap()).containsOnlyKeys(foo.pluginId)
    assertThat(result.getIdMap().get(foo.pluginId)).isSameAs(foo)
  }

  @Test
  fun `select compatible plugin if both versions provided`() {
    writeDescriptor("foo_1-0", """
      <idea-plugin>
        <id>foo</id>
        <vendor>JetBrains</vendor>
        <version>1.0</version>
        <idea-version since-build="1.*" until-build="2.*"/>
      </idea-plugin>""")
    writeDescriptor("foo_2-0", """
      <idea-plugin>
        <id>foo</id>
        <vendor>JetBrains</vendor>
        <version>2.0</version>
        <idea-version since-build="2.0" until-build="4.*"/>
      </idea-plugin>""")

    val pluginSet = PluginSetTestBuilder.fromPath(pluginsDirPath)
      .withProductBuildNumber(BuildNumber.fromString("3.12")!!)
      .build()
    val plugins = pluginSet.enabledPlugins
    assertThat(plugins).hasSize(1)
    val foo = plugins[0]
    assertThat(foo.version).isEqualTo("2.0")
    assertThat(foo.pluginId.idString).isEqualTo("foo")

    assertThat(pluginSet.allPlugins.toList()).map(Function { it.pluginId }).containsOnly(foo.pluginId)
    assertThat(pluginSet.findEnabledPlugin(foo.pluginId)).isSameAs(foo)
  }

  @Test
  fun `use first plugin if both versions the same`() {
    PluginBuilder().id("foo").version("1.0").build(pluginsDirPath.resolve("foo_1-0"))
    PluginBuilder().id("foo").version("1.0").build(pluginsDirPath.resolve("foo_another"))

    val pluginSet = PluginSetTestBuilder.fromPath(pluginsDirPath).build()
    val plugins = pluginSet.enabledPlugins
    assertThat(plugins).hasSize(1)
    val foo = plugins[0]
    assertThat(foo.version).isEqualTo("1.0")
    assertThat(foo.pluginId.idString).isEqualTo("foo")

    assertThat(pluginSet.allPlugins.toList()).map(Function { it.pluginId }).containsOnly(foo.pluginId)
    assertThat(pluginSet.findEnabledPlugin(foo.pluginId)).isSameAs(foo)
  }

  @Test
  fun `until build is honored only if it targets 243 and earlier`() {
    fun addDescriptor(build: String) = writeDescriptor("p$build", """
    <idea-plugin>
      <id>p$build</id>
      <version>1.0</version>
      <idea-version since-build="$build" until-build="$build.100"/>
    </idea-plugin>
    """.trimIndent())

    addDescriptor("243")
    addDescriptor("251")
    addDescriptor("252")
    addDescriptor("261")

    assertEnabledPluginsSetEquals(listOf("p243")) { buildNumber = "243.10" }
    assertEnabledPluginsSetEquals(listOf("p251")) { buildNumber = "251.10" }
    assertEnabledPluginsSetEquals(listOf("p251", "p252")) { buildNumber = "252.200" }
    assertEnabledPluginsSetEquals(listOf("p251", "p252", "p261")) { buildNumber = "261.200" }
  }

  @Test
  fun `broken plugins is honored while until build is not`() {
    writeDescriptor("p251", """
      <idea-plugin>
      <id>p251</id>
      <version>1.0</version>
      <idea-version since-build="251" until-build="251.100"/>
      </idea-plugin>
    """.trimIndent())
    writeDescriptor("p252", """
      <idea-plugin>
      <id>p252</id>
      <version>1.0</version>
      <idea-version since-build="252" until-build="252.100"/>
      </idea-plugin>
    """.trimIndent())

    assertEnabledPluginsSetEquals(listOf("p251", "p252")) { buildNumber = "252.200" }
    assertEnabledPluginsSetEquals(listOf("p252")) {
      buildNumber = "252.200"
      withBrokenPlugin("p251", "1.0")
    }
    assertEnabledPluginsSetEquals(listOf("p251")) {
      buildNumber = "252.200"
      withBrokenPlugin("p252", "1.0")
    }
  }

  @Test
  fun `package prefix collision prevents plugin from loading`() {
    PluginManagerCore.getAndClearPluginLoadingErrors()
    // FIXME these plugins are not related, but one of them loads => depends on implicit order
    PluginBuilder().id("foo")
      .module("foo.module", PluginBuilder().packagePrefix("common.module"), loadingRule = ModuleLoadingRule.REQUIRED)
      .build(pluginsDirPath.resolve("foo"))
    PluginBuilder().id("bar")
      .module("bar.module", PluginBuilder().packagePrefix("common.module"), loadingRule = ModuleLoadingRule.REQUIRED)
      .build(pluginsDirPath.resolve("bar"))
    val pluginSet = buildPluginSet()
    assertThat(pluginSet).hasExactlyEnabledPlugins("foo")
    val errors = PluginManagerCore.getAndClearPluginLoadingErrors()
    assertThat(errors).hasSizeGreaterThan(0)
    assertThat(errors[0].get().toString()).contains("conflicts with", "bar.module", "foo.module", "package prefix")
  }
  
  @Test
  fun `package prefix collision in plugin explicitly marked as incompatible`() {
    PluginBuilder().id("foo")
      .module("foo.module", PluginBuilder().packagePrefix("common.module"), loadingRule = ModuleLoadingRule.REQUIRED)
      .incompatibleWith("bar")
      .build(pluginsDirPath.resolve("foo"))
    PluginBuilder().id("bar")
      .module("bar.module", PluginBuilder().packagePrefix("common.module"), loadingRule = ModuleLoadingRule.REQUIRED)
      .build(pluginsDirPath.resolve("bar"))
    val pluginSet = buildPluginSet()
    assertThat(pluginSet).hasExactlyEnabledPlugins("bar")
  }

  @Test
  fun `package prefix collision prevents plugin from loading - same plugin`() {
    PluginManagerCore.getAndClearPluginLoadingErrors()
    PluginBuilder().id("foo").packagePrefix("common.module")
      .module("foo.module", PluginBuilder().packagePrefix("common.module"), loadingRule = ModuleLoadingRule.REQUIRED)
      .build(pluginsDirPath.resolve("foo"))
    val pluginSet = buildPluginSet()
    assertThat(pluginSet).doesNotHaveEnabledPlugins()
    val errors = PluginManagerCore.getAndClearPluginLoadingErrors()
    assertThat(errors).hasSizeGreaterThan(0)
    assertThat(errors[0].get().toString()).contains("conflicts with", "foo.module", "package prefix")
  }

  @Test
  fun `package prefix collision does not prevent plugin from loading if module is optional`() {
    PluginManagerCore.getAndClearPluginLoadingErrors()
    PluginBuilder().id("foo")
      .module("foo.module", PluginBuilder().packagePrefix("common.module"), loadingRule = ModuleLoadingRule.OPTIONAL)
      .build(pluginsDirPath.resolve("foo"))
    PluginBuilder().id("bar")
      .module("bar.module", PluginBuilder().packagePrefix("common.module"), loadingRule = ModuleLoadingRule.OPTIONAL)
      .build(pluginsDirPath.resolve("bar"))
    val pluginSet = buildPluginSet()
    assertThat(pluginSet).hasExactlyEnabledPlugins("foo", "bar")
    // FIXME these plugins are not related, but one of them loads => depends on implicit order
    assertThat(pluginSet).hasExactlyEnabledModulesWithoutMainDescriptors("foo.module")
    val errors = PluginManagerCore.getAndClearPluginLoadingErrors()
    assertThat(errors).isNotEmpty()
    assertThat(errors[0].get().toString()).contains("conflicts with", "bar", "foo.module", "package prefix")
  }

  @Test
  fun `content module without a package prefix nor isSeparateJar fails to load`() {
    PluginBuilder().id("foo")
      .module("foo.module", PluginBuilder())
      .build(pluginsDirPath.resolve("foo"))
    assertThatThrownBy {
      buildPluginSet()
    }.hasMessageContaining("Package is not specified")
  }

  @Test
  fun `content module with a package prefix or separate jar loads`() {
    PluginBuilder().id("foo")
      .module("foo.module", PluginBuilder().packagePrefix("foo.module"))
      .build(pluginsDirPath.resolve("foo"))
    PluginBuilder().id("bar")
      .module("bar.module", PluginBuilder().separateJar(true))
      .build(pluginsDirPath.resolve("bar"))
    assertThat(buildPluginSet()).hasExactlyEnabledPlugins("foo", "bar")
  }

  @Test
  fun `id, version, name are inherited in depends sub-descriptors`() {
    PluginBuilder().id("foo").build(pluginsDirPath.resolve("foo"))
    PluginBuilder()
      .id("bar")
      .name("Bar")
      .version("1.0.0")
      .depends("foo", PluginBuilder())
      .build(pluginsDirPath.resolve("bar"))

    val pluginSet = buildPluginSet()
    assertThat(pluginSet).hasExactlyEnabledPlugins("bar", "foo")
    val descriptor = pluginSet.getEnabledPlugin("bar")
    assertThat(descriptor.pluginId.idString).isEqualTo("bar")
    assertThat(descriptor.name).isEqualTo("Bar")
    assertThat(descriptor.version).isEqualTo("1.0.0")
    assertThat(descriptor.dependencies).hasSize(1)
    val subDesc = descriptor.dependencies[0].subDescriptor!!
    assertThat(subDesc.pluginId.idString).isEqualTo("bar")
    assertThat(subDesc.name).isEqualTo("Bar")
    assertThat(subDesc.version).isEqualTo("1.0.0")
  }

  @Test
  fun `id, version, name can't be overridden in depends sub-descriptors`() {
    PluginBuilder().id("foo").build(pluginsDirPath.resolve("foo"))
    PluginBuilder()
      .id("bar")
      .name("Bar")
      .version("1.0.0")
      .depends("foo", PluginBuilder()
        // .id("bar 2") TODO ids are disregarded for content modules
        .additionalXmlContent("<id>bar 2</id>")
        .name("Bar Sub")
        .version("2.0.0"))
      .build(pluginsDirPath.resolve("bar"))

    val (pluginSet, errs) = runAndReturnWithLoggedErrors { buildPluginSet() }
    assertThat(errs.joinToString { it.message ?: "" }).isNotNull
      .contains("element 'version'", "element 'name'", "element 'id'")
    assertThat(pluginSet).hasExactlyEnabledPlugins("bar", "foo")
    val descriptor = pluginSet.getEnabledPlugin("bar")
    assertThat(descriptor.pluginId.idString).isEqualTo("bar")
    assertThat(descriptor.name).isEqualTo("Bar")
    assertThat(descriptor.version).isEqualTo("1.0.0")
    assertThat(descriptor.dependencies).hasSize(1)
    val subDesc = descriptor.dependencies[0].subDescriptor!!
    assertThat(subDesc.pluginId.idString).isEqualTo("bar")
    assertThat(subDesc.name).isEqualTo("Bar")
    assertThat(subDesc.version).isEqualTo("1.0.0")
  }

  @Test
  fun `resource bundle is inherited in depends sub-descriptors`() {
    PluginBuilder().id("foo").build(pluginsDirPath.resolve("foo"))
    PluginBuilder()
      .id("bar")
      .resourceBundle("resourceBundle")
      .depends("foo", PluginBuilder())
      .build(pluginsDirPath.resolve("bar"))

    val pluginSet = buildPluginSet()
    assertThat(pluginSet).hasExactlyEnabledPlugins("bar", "foo")
    val descriptor = pluginSet.getEnabledPlugin("bar")
    assertThat(descriptor.resourceBundleBaseName).isEqualTo("resourceBundle")
    assertThat(descriptor.dependencies).hasSize(1)
    val subDesc = descriptor.dependencies[0].subDescriptor!!
    assertThat(subDesc.resourceBundleBaseName).isEqualTo("resourceBundle")
  }

  @Test
  fun `resource bundle can be overridden in depends sub-descriptors`() {
    PluginBuilder().id("foo").build(pluginsDirPath.resolve("foo"))
    PluginBuilder()
      .id("bar")
      .resourceBundle("resourceBundle")
      .depends("foo", PluginBuilder().resourceBundle("sub"))
      .build(pluginsDirPath.resolve("bar"))

    val pluginSet = buildPluginSet()
    assertThat(pluginSet).hasExactlyEnabledPlugins("bar", "foo")
    val descriptor = pluginSet.getEnabledPlugin("bar")
    assertThat(descriptor.resourceBundleBaseName).isEqualTo("resourceBundle")
    assertThat(descriptor.dependencies).hasSize(1)
    val subDesc = descriptor.dependencies[0].subDescriptor!!
    assertThat(subDesc.resourceBundleBaseName).isEqualTo("sub")
  }

  @Test
  fun `additional core plugin aliases`() {
    PluginBuilder()
      .id("com.intellij")
      .module("embedded.module", PluginBuilder().packagePrefix("embedded"), loadingRule = ModuleLoadingRule.EMBEDDED)
      .module("required.module", PluginBuilder().packagePrefix("required"), loadingRule = ModuleLoadingRule.REQUIRED)
      .module("optional.module", PluginBuilder().packagePrefix("optional"), loadingRule = ModuleLoadingRule.OPTIONAL)
      .build(pluginsDirPath.resolve("core"))
    val pluginSet = buildPluginSet()
    val core = pluginSet.getEnabledPlugin("com.intellij")
    for (alias in IdeaPluginOsRequirement.getHostOsModuleIds() + productModeAliasesForCorePlugin()) {
      assertThat(pluginSet.findEnabledPlugin(alias)).isSameAs(core)
    }
  }

  private fun writeDescriptor(id: String, @Language("xml") data: String) {
    pluginsDirPath.resolve(id)
      .resolve(PluginManagerCore.PLUGIN_XML_PATH)
      .write(data.trimIndent())
  }

  private fun assertEnabledPluginsSetEquals(enabledIds: List<String>, builder: PluginSetTestBuilder.() -> Unit) {
    val pluginSet = buildPluginSet(builder)
    assertThat(pluginSet).hasExactlyEnabledPlugins(*enabledIds.toTypedArray())
  }

  private fun buildPluginSet(builder: PluginSetTestBuilder.() -> Unit = {}): PluginSet = PluginSetTestBuilder.fromPath(pluginsDirPath).apply(builder).build()
}