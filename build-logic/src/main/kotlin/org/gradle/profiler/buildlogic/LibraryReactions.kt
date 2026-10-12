package org.gradle.profiler.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Sync
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope

class ProfilerLibraryReaction : Reaction<ProfilerLibrary, Project> {
    override fun on(data: ProfilerLibrary, context: Project, scope: ReactionScope) {
        ProfilerComponents.configure(context, data)
        JavaConventions.apply(context, "java-library")
        DependencyLinking.link(context, data.dependencies().orNull)
    }
}

class EmbeddedLibraryReaction : Reaction<EmbeddedLibrary, Project> {
    override fun on(data: EmbeddedLibrary, context: Project, scope: ReactionScope) {
        ProfilerComponents.configure(context, data)
        JavaConventions.apply(context, "java-library")
        context.pluginManager.apply("groovy")
        val javaVersion = JavaVersion.toVersion(data.javaVersion().get())
        context.extensions.getByType(JavaPluginExtension::class.java).apply {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        DependencyLinking.link(context, data.dependencies().orNull)
    }
}

class KotlinLibraryReaction : Reaction<KotlinLibrary, Project> {
    override fun on(data: KotlinLibrary, context: Project, scope: ReactionScope) {
        ProfilerComponents.configure(context, data)
        KotlinConventions.apply(context)
        context.pluginManager.apply("groovy")
        DependencyLinking.link(context, data.dependencies().orNull)
    }
}

class KotlinApplicationReaction : Reaction<KotlinApplication, Project> {
    override fun on(data: KotlinApplication, context: Project, scope: ReactionScope) {
        ProfilerComponents.configure(context, data)
        KotlinConventions.apply(context)
        context.pluginManager.apply("groovy")
        context.pluginManager.apply("application")
        DependencyLinking.link(context, data.dependencies().orNull)

        val applicationName = data.applicationName().get()
        context.extensions.getByType(JavaApplication::class.java).apply {
            mainClass.set(data.mainClass())
            this.applicationName = applicationName
        }

        val installDist = context.tasks.named("installDist", Sync::class.java)
        context.tasks.register("install", Sync::class.java) { install ->
            val installDirName = "$applicationName.install.dir"
            val installDir = context.providers.gradleProperty(installDirName)
                .orElse(context.providers.systemProperty(installDirName))
                .map { context.file(it) }
                .orElse(context.layout.projectDirectory.dir("distribution").asFile)
            install.from(installDist.map { it.destinationDir })
            install.into(installDir)
            install.doLast { println("Installed $applicationName to '${installDir.get()}'") }
        }
    }
}
