package org.gradle.profiler.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope
import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import java.nio.charset.StandardCharsets
import java.util.Locale

class ProfilerApplicationReaction : Reaction<ProfilerApplication, Project> {
    override fun on(data: ProfilerApplication, context: Project, scope: ReactionScope) {
        ProfilerComponents.configure(context, data)
        JavaConventions.apply(context, "java-library")
        context.pluginManager.apply("groovy")
        context.pluginManager.apply("application")
        DependencyLinking.link(context, data.dependencies().orNull)

        val gradleRuntime = context.configurations.create("gradleRuntime")
        val profilerPlugins = context.configurations.create("profilerPlugins")
        context.dependencies.add(gradleRuntime.name, context.dependencies.gradleApi())
        context.dependencies.add(gradleRuntime.name, Catalog.library(context, "toolingApi"))
        data.embeddedPlugins().get().forEach { plugin ->
            if (plugin is ProjectDependency && context.findProject(plugin.project().get()) == null) {
                context.logger.info("Embedded plugin ${plugin.project().get()} is not part of this build, skipping it.")
            } else {
                context.dependencies.add(profilerPlugins.name, DependencyLinking.notation(context, plugin))
            }
        }

        context.extensions.getByType(JavaApplication::class.java).mainClass.set(data.mainClass())

        context.tasks.withType(Jar::class.java).configureEach { jar ->
            jar.manifest.attributes(
                mapOf(
                    "Implementation-Title" to "Gradle Profiler",
                    "Implementation-Version" to context.version
                )
            )
        }

        context.tasks.named("processResources", ProcessResources::class.java) { task ->
            task.into("META-INF/jars") { jars ->
                jars.from(profilerPlugins.minus(gradleRuntime)) { spec ->
                    // Removing the version from the JARs here, since they are referenced by name in production code.
                    spec.rename("""(.*)-\d+\.\d+.*\.jar""", "\$1.jar")
                }
            }
        }

        configureTests(context)

        context.tasks.named("updateDaemonJvm", UpdateDaemonJvm::class.java) { it.toolchainDownloadUrls.empty() }

        val installDist = context.tasks.named("installDist", Sync::class.java)
        context.tasks.register("install", Sync::class.java) { install ->
            val installDir = context.providers.gradleProperty("gradle-profiler.install.dir").orElse("distribution")
                .map { context.layout.settingsDirectory.file(it) }
            install.from(installDist.map { it.destinationDir })
            install.into(installDir)
            install.doLast { println("Installed gradle-profiler to '${installDir.get()}'") }
        }

        registerBuildReceipt(context)
    }

    private fun configureTests(project: Project) {
        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
        val providers = project.providers
        project.tasks.named("test", Test::class.java) { test ->
            // If testJavaVersion is not set use the current JVM. Some tests require JFR, which is only available
            // in some JVM implementations. For now assume that the current JVM has JFR support.
            // CI will inject the correct implementation.
            val launcher = toolchains.launcherFor { spec ->
                val javaVersion = providers.gradleProperty("testJavaVersion")
                    .getOrElse(JavaVersion.current().majorVersion)
                spec.languageVersion.set(JavaLanguageVersion.of(javaVersion))
                providers.gradleProperty("testJavaVendor").map { vendor ->
                    when (vendor.lowercase(Locale.US)) {
                        "oracle" -> spec.vendor.set(JvmVendorSpec.ORACLE)
                        "openjdk" -> spec.vendor.set(JvmVendorSpec.ADOPTIUM)
                    }
                }.orNull
            }
            test.javaLauncher.set(launcher)
            // So processes started from test also use same Java version
            test.environment("JAVA_HOME", test.javaLauncher.get().metadata.installationPath)

            // We had some build failures on macOS, where it seems to be a Socket was already closed when trying to download the Gradle distribution.
            // The tests failing were consistently in ProfilerIntegrationTest.
            // Running only ProfilerIntegrationTest did not expose the failures.
            // The problem went away when running every test class in its on JVM.
            // So I suppose the problem is that the JVM shares the TAPI client, and one of the tests leave the client in a bad state.
            // We now use forkEvery = 1 to run each test class in its own JVM, so we don't run into this problem anymore.
            test.setForkEvery(1)
            test.maxHeapSize = "2g"

            // Required for mocks when running on Java 17+
            test.jvmArgumentProviders.add(AddOpensArgProvider(test))

            // Used by tests to select a different Daemon JVM when Test JVM is unsupported by older Gradle versions
            test.systemProperty("javaHomes.java11", launcherJavaHomeFor(toolchains, 11))

            // Run cross-version tests for all tested versions
            test.systemProperty("org.gradle.integtest.versions", providers.gradleProperty("testVersions").orElse("all").get())
            test.systemProperty("org.gradle.integtest.keepTestDirs", providers.gradleProperty("keepTestDirs").orElse("false").get())

            // Spock snapshot testing (golden files). Regenerate snapshots with: ./gradlew test -PupdateSnapshots=true
            test.systemProperty("spock.snapshots.rootPath", project.layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
            test.systemProperty("spock.snapshots.updateSnapshots", providers.gradleProperty("updateSnapshots").orElse("false").get())
        }
    }

    private fun launcherJavaHomeFor(toolchains: JavaToolchainService, javaVersion: Int): String =
        toolchains.launcherFor { it.languageVersion.set(JavaLanguageVersion.of(javaVersion)) }
            .map { it.metadata.installationPath.asFile.absolutePath }
            .get()

    private fun registerBuildReceipt(project: Project) {
        val profilerVersion = project.profilerVersion()
        val createdBuildReceipt = project.layout.buildDirectory.file(buildReceiptName)
        project.tasks.register("createBuildReceipt") { task ->
            task.outputs.file(createdBuildReceipt).withPropertyName("buildReceipt")
            task.inputs.property("version", profilerVersion)
            task.doLast {
                createdBuildReceipt.get().asFile.writeText("version=${profilerVersion.get()}", StandardCharsets.UTF_8)
            }
        }
        val logger = project.logger
        project.gradle.taskGraph.whenReady { graph ->
            if (graph.hasTask(":publishToMavenCentral") || graph.hasTask(":releaseToSdkMan")) {
                logger.lifecycle(
                    "##teamcity[buildStatus text='{build.status.text}, Published version {}']",
                    profilerVersion.get()
                )
            }
        }
    }
}

class AddOpensArgProvider(private val test: Test) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        return if (test.javaVersion.isCompatibleWith(JavaVersion.VERSION_1_9)) {
            listOf("--add-opens=java.base/java.lang=ALL-UNNAMED")
        } else {
            emptyList()
        }
    }
}
