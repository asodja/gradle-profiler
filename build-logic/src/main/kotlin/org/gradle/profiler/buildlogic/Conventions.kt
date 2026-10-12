package org.gradle.profiler.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** What every project of this build gets, whatever its project type. */
internal object ProfilerComponents {
    fun configure(project: Project, component: ProfilerComponent) {
        project.pluginManager.apply("base")
        project.group = "org.gradle.profiler"
        project.version = project.profilerVersion().get()
        component.description().orNull?.let { project.description = it }
        project.tasks.register("sanityCheck")
        project.tasks.named("check") { it.dependsOn("sanityCheck") }
    }
}

internal object JavaConventions {
    fun apply(project: Project, plugin: String = "java") {
        project.pluginManager.apply(plugin)
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        // Generated protobuf sources contain non-ASCII characters, which fail javadoc under a POSIX locale.
        project.tasks.withType(Javadoc::class.java).configureEach { it.options.encoding = "UTF-8" }
        project.tasks.withType(Test::class.java).configureEach { test ->
            // Add OS as inputs since tests on different OS may behave differently https://github.com/gradle/gradle-private/issues/2831
            // the version currently differs between our dev infrastructure, so we only track the name and the architecture
            test.inputs.property("operatingSystem", "${OperatingSystem.current().name} ${System.getProperty("os.arch")}")
            test.useJUnitPlatform()
        }
    }
}

internal object KotlinConventions {
    fun apply(project: Project, jvmToolchain: Int = 17) {
        JavaConventions.apply(project)
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.extensions.getByType(KotlinJvmProjectExtension::class.java).jvmToolchain(jvmToolchain)
    }
}

/** Resolves the `:libs.…` symbols of the schema against `gradle/libs.versions.toml`. */
internal object Catalog {
    fun libs(project: Project): VersionCatalog =
        project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

    fun library(project: Project, alias: String): Any =
        libs(project).findLibrary(alias).orElseThrow { IllegalArgumentException("No library '$alias' in the version catalog") }

    fun bundle(project: Project, alias: String): Any =
        libs(project).findBundle(alias).orElseThrow { IllegalArgumentException("No bundle '$alias' in the version catalog") }

    fun version(project: Project, version: Version): Provider<String> = when (version) {
        is Version.StringValue -> project.provider { version.value() }
        is Version.SymbolValue -> {
            val name = version.value()
            require(name.startsWith("libs.versions.")) { "Unknown version symbol ':$name', expected ':libs.versions.<name>'" }
            val alias = name.removePrefix("libs.versions.")
            val constraint = libs(project).findVersion(alias)
                .orElseThrow { IllegalArgumentException("No version '$alias' in the version catalog") }
            project.provider { constraint.requiredVersion }
        }
    }
}

internal object DependencyLinking {
    fun link(project: Project, dependencies: Dependencies?) {
        if (dependencies == null) {
            return
        }
        add(project, "api", dependencies.api())
        add(project, "implementation", dependencies.implementation())
        add(project, "runtimeOnly", dependencies.runtimeOnly())
        add(project, "testImplementation", dependencies.testImplementation())
        add(project, "testRuntimeOnly", dependencies.testRuntimeOnly())
    }

    private fun add(project: Project, configuration: String, declarations: Provider<List<DependencyDeclaration>>) {
        declarations.getOrElse(emptyList()).forEach { declaration ->
            project.dependencies.add(configuration, notation(project, declaration))
        }
    }

    fun notation(project: Project, declaration: DependencyDeclaration): Any = when (declaration) {
        is DependencyDeclaration.StringValue -> declaration.value()
        is DependencyDeclaration.SymbolValue -> symbol(project, declaration.value())
        is ProjectDependency -> project.dependencies.project(mapOf("path" to declaration.project().get()))
    }

    private fun symbol(project: Project, name: String): Any = when {
        name == "gradleApi" -> project.dependencies.gradleApi()
        name.startsWith("libs.bundles.") -> Catalog.bundle(project, name.removePrefix("libs.bundles."))
        name.startsWith("libs.") -> Catalog.library(project, name.removePrefix("libs."))
        else -> throw IllegalArgumentException("Unknown dependency symbol ':$name'")
    }
}
