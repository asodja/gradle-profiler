package org.gradle.profiler.buildlogic

import nmcp.NmcpSettings
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.initialization.Settings
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope

class ProfilerBuildReaction : Reaction<ProfilerBuild, Settings> {
    override fun on(data: ProfilerBuild, context: Settings, scope: ReactionScope) {
        val providers = context.providers

        if (!providers.gradleProperty("profiler.skipIdePlugin").isPresent) {
            context.include("ide-plugin")
        }

        val subprojectsDirectory = data.subprojectsDirectory().get()
        context.rootProject.children.forEach { project ->
            project.projectDir = context.rootDir.resolve("$subprojectsDirectory/${project.name}")
        }

        ProfilerRepositories.libraries(context.dependencyResolutionManagement.repositories)
        ProfilerRepositories.ides(context.dependencyResolutionManagement.repositories)

        context.extensions.getByType(NmcpSettings::class.java).centralPortal { portal ->
            portal.username.set(providers.gradleProperty("mavenCentralUsername"))
            portal.password.set(providers.gradleProperty("mavenCentralPassword"))
        }
    }
}

/**
 * Repositories with content filters or ivy layouts, which the settings schema cannot express.
 * Declared build-wide in the settings; a project that gets its own repositories from a plugin
 * (node, IntelliJ Platform) no longer sees the settings ones and must repeat [libraries].
 */
internal object ProfilerRepositories {
    fun libraries(repositories: RepositoryHandler) {
        repositories.mavenCentral()
        repositories.maven { repository ->
            repository.name = "Gradle public repository"
            repository.setUrl("https://repo.gradle.org/gradle/libs-releases")
            repository.content { it.includeModule("org.gradle", "gradle-tooling-api") }
        }
    }

    fun ides(repositories: RepositoryHandler) {
        listOf(
            "https://redirector.gvt1.com/edgedl/android/studio/ide-zips",
            "https://redirector.gvt1.com/edgedl/android/studio/install"
        ).forEach { url ->
            repositories.ivy { repository ->
                repository.setUrl(url)
                repository.patternLayout {
                    it.artifact("[revision]/[artifact]-[ext]")
                    it.artifact("[revision]/[artifact]-[revision]-[ext]")
                }
                repository.metadataSources { it.artifact() }
                repository.content { it.includeGroup("android-studio") }
            }
        }
        repositories.ivy { repository ->
            repository.setUrl("https://download.jetbrains.com/idea/")
            repository.patternLayout {
                it.artifact("[artifact]-[revision].[ext]")
                // ARM Mac uses dash separator: ideaIC-2025.2-aarch64.dmg
                it.artifact("[artifact]-[revision]-[ext]")
            }
            repository.metadataSources { it.artifact() }
            repository.content { it.includeGroup("intellij-idea") }
        }
    }
}
