package org.gradle.profiler.buildlogic

import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformRepositoriesExtension
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import providers.IdeInstallation
import providers.IdeSystemProperties
import tasks.ExtractIdeTask
import tasks.InstallAndroidSdkTask

class IdePluginReaction : Reaction<IdePlugin, Project> {
    override fun on(data: IdePlugin, context: Project, scope: ReactionScope) {
        ProfilerComponents.configure(context, data)
        KotlinConventions.apply(context, jvmToolchain = 21)
        context.pluginManager.apply("org.jetbrains.intellij.platform")

        // The IntelliJ Platform repositories are project-level and hide the settings ones.
        ProfilerRepositories.libraries(context.repositories)
        (context.repositories as ExtensionAware).extensions
            .getByType(IntelliJPlatformRepositoriesExtension::class.java).defaultRepositories()

        DependencyLinking.link(context, data.dependencies().orNull)
        val platformDependencies = (context.dependencies as ExtensionAware).extensions
            .getByType(IntelliJPlatformDependenciesExtension::class.java)
        platformDependencies.intellijIdea(Catalog.version(context, data.platformVersion().get()))
        data.bundledPlugins().get().forEach { platformDependencies.bundledPlugin(it) }
        platformDependencies.plugin("org.jetbrains.android", Catalog.version(context, data.androidPluginVersion().get()).get())

        // Exclude Spock from test sandbox to avoid duplicate on classpath (Gradle provides it at runtime)
        context.tasks.withType(PrepareSandboxTask::class.java).configureEach { it.exclude("**/spock-*.jar") }

        context.extensions.getByType(IntelliJPlatformExtension::class.java).apply {
            pluginConfiguration.name.set(data.pluginName())
            pluginConfiguration.ideaVersion.sinceBuild.set(Catalog.version(context, data.sinceBuild().get()))
            pluginConfiguration.ideaVersion.untilBuild.set(context.provider { null })
            // Disable searchable options indexing since this plugin is not published to the marketplace
            buildSearchableOptions.set(false)
        }
    }
}

class IdeTestsReaction : Reaction<IdeTests, Project> {
    override fun on(data: IdeTests, context: Project, scope: ReactionScope) {
        val autoDownloadAndRunInHeadless = context.providers.gradleProperty("autoDownloadAndRunInHeadless")
            .map { it.toBoolean() }
            .orElse(false)
        val intellijVersion = Catalog.version(context, data.intellijVersion().get())
        val androidStudioVersion = Catalog.version(context, data.androidStudioVersion().get())
        val androidStudioCodename: Provider<String> = data.androidStudioCodename().orNull
            ?.let { Catalog.version(context, it) }
            ?: context.provider { "" }
        val androidSdkVersion = Catalog.version(context, data.androidSdkVersion().get())

        val androidStudioRuntime = context.configurations.create("androidStudioRuntime")
        val intellijRuntime = context.configurations.create("intellijRuntime")
        context.dependencies.add(
            androidStudioRuntime.name,
            androidStudioCodename.zip(androidStudioVersion) { codename, version ->
                val artifact = if (codename.isEmpty()) "android-studio" else "android-studio-$codename"
                "android-studio:$artifact:$version@${Ide.androidStudioFileExtension()}"
            }
        )
        context.dependencies.add(intellijRuntime.name, intellijVersion.map { "intellij-idea:idea:$it@${Ide.intellijFileExtension()}" })

        val testIdesDir = context.layout.buildDirectory.dir("test-ides")
        val unpackAndroidStudio = context.tasks.register("unpackAndroidStudio", ExtractIdeTask::class.java) { task ->
            task.dmgAppName.set("Android Studio.app")
            task.stripTopLevelDirectory.set(true)
            task.ideDistribution.from(androidStudioRuntime)
            task.outputDir.set(testIdesDir.map { it.dir("android-studio") })
        }
        val unpackIntellij = context.tasks.register("unpackIntellij", ExtractIdeTask::class.java) { task ->
            task.dmgAppName.set("IntelliJ IDEA.app")
            // Windows ZIP is flat, Linux tar.gz has a wrapping directory
            task.stripTopLevelDirectory.set(!Ide.isWindows())
            task.ideDistribution.from(intellijRuntime)
            task.outputDir.set(testIdesDir.map { it.dir("intellij") })
        }
        val installAndroidSdk = context.tasks.register("installAndroidSdk", InstallAndroidSdkTask::class.java) { task ->
            task.androidSdkVersion.set(androidSdkVersion)
            task.androidProjectDir.set(context.layout.buildDirectory.dir("installAndroidSdk/android-sdk-project"))
            // For local development it's easier to setup Android SDK with Android Studio, since auto download needs ANDROID_HOME or ANDROID_SDK_ROOT
            // to be set with an accepted license in it. See https://developer.android.com/studio/intro/update.html#download-with-gradle.
            task.onlyIf { autoDownloadAndRunInHeadless.get() }
        }

        val androidStudioInstallation = context.objects.newInstance(IdeInstallation::class.java).apply {
            installLocation.set(unpackAndroidStudio.flatMap { it.outputDir })
        }
        val intellijInstallation = context.objects.newInstance(IdeInstallation::class.java).apply {
            installLocation.set(unpackIntellij.flatMap { it.outputDir })
        }

        context.tasks.withType(Test::class.java).configureEach { test ->
            test.dependsOn(installAndroidSdk)
            test.jvmArgumentProviders.add(
                IdeSystemProperties(androidStudioInstallation, autoDownloadAndRunInHeadless, autoDownloadAndRunInHeadless, "studio.home")
            )
            test.jvmArgumentProviders.add(
                IdeSystemProperties(intellijInstallation, autoDownloadAndRunInHeadless, autoDownloadAndRunInHeadless, "idea.home")
            )
        }
    }
}

internal object Ide {
    private val os = System.getProperty("os.name").lowercase()
    private val architecture = System.getProperty("os.arch").lowercase()

    fun isWindows(): Boolean = os.startsWith("windows")
    private fun isMacOS(): Boolean = os.startsWith("mac")
    private fun isLinux(): Boolean = os.startsWith("linux")
    private fun isIntel(): Boolean = architecture == "x86_64" || architecture == "x86"

    fun androidStudioFileExtension(): String = when {
        isWindows() -> "windows.zip"
        isMacOS() && isIntel() -> "mac.dmg"
        isMacOS() && !isIntel() -> "mac_arm.dmg"
        isLinux() -> "linux.tar.gz"
        else -> throw IllegalStateException("Unsupported OS: $os")
    }

    fun intellijFileExtension(): String = when {
        isWindows() -> "win.zip"
        isMacOS() && isIntel() -> "dmg"
        isMacOS() && !isIntel() -> "aarch64.dmg"
        isLinux() -> "tar.gz"
        else -> throw IllegalStateException("Unsupported OS: $os")
    }
}
