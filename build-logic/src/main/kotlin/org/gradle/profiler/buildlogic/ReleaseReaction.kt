package org.gradle.profiler.buildlogic

import io.sdkman.vendors.model.SdkmanExtension
import io.sdkman.vendors.tasks.SdkAnnounceVersion
import io.sdkman.vendors.tasks.SdkDefaultVersion
import io.sdkman.vendors.tasks.SdkReleaseVersion
import io.sdkman.vendors.tasks.SdkmanVendorBaseTask
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope
import services.GithubReleaseService
import tasks.PublishToGithubReleasesTask
import java.util.Locale

class ReleaseReaction : Reaction<Release, Project> {
    override fun on(data: Release, context: Project, scope: ReactionScope) {
        context.pluginManager.apply("io.sdkman.vendors")

        val repository = data.githubRepository().get()
        val version = context.version.toString()
        val isSnapshot = version.endsWith("-SNAPSHOT")
        // Alphas and snapshots have no release-drafter draft and SDKMAN skips them, so they get no GitHub release ZIP.
        val isSnapshotOrAlphaRelease = version.lowercase(Locale.US).run { contains("snapshot") || contains("alpha") }

        val publishToMavenCentral = context.tasks.register("publishToMavenCentral") { task ->
            task.dependsOn(if (isSnapshot) "publishAggregationToCentralSnapshots" else "publishAggregationToCentralPortal")
        }

        val releaseTagName = "v$version"
        context.tasks.register("gitTag", Exec::class.java) { task ->
            task.onlyIf { !isSnapshot }
            task.commandLine("git", "tag", releaseTagName)
        }
        val gitPushTag = context.tasks.register("gitPushTag", Exec::class.java) { task ->
            task.mustRunAfter(publishToMavenCentral)
            task.dependsOn("gitTag")
            task.onlyIf { !isSnapshot }
            task.commandLine(
                "git",
                "push",
                "https://bot-teamcity:${context.findProperty("githubToken")}@github.com/$repository.git",
                releaseTagName
            )
        }

        context.gradle.sharedServices.registerIfAbsent("githubRelease", GithubReleaseService::class.java) { spec ->
            spec.parameters.githubToken.set(context.providers.gradleProperty("githubToken"))
        }
        context.tasks.register("publishToGithubReleases", PublishToGithubReleasesTask::class.java) { task ->
            task.mustRunAfter(gitPushTag)
            task.repository.set(repository)
            task.releaseVersion.set(version)
            // Wiring the ZIP file provider also carries the task dependency on distZip.
            // The GithubReleaseService is auto-wired via @ServiceReference on the task.
            task.distributionZip.set(context.tasks.named("distZip", Zip::class.java).flatMap { it.archiveFile })
            task.onlyIf { !isSnapshotOrAlphaRelease }
        }

        val candidate = data.sdkmanCandidate().get()
        context.extensions.getByType(SdkmanExtension::class.java).apply {
            api.set("https://vendors.sdkman.io")
            this.candidate.set(candidate)
            hashtag.set("#$candidate")
            this.version.set(version)
            url.set("https://github.com/$repository/releases/download/v$version/gradle-profiler-$version.zip")
            consumerKey.set(context.findProperty("sdkmanKey") as String?)
            consumerToken.set(context.findProperty("sdkmanToken") as String?)
        }

        context.tasks.withType(SdkmanVendorBaseTask::class.java).configureEach { it.mustRunAfter(gitPushTag) }
        context.tasks.withType(SdkDefaultVersion::class.java).configureEach {
            it.mustRunAfter(context.tasks.withType(SdkReleaseVersion::class.java))
        }
        context.tasks.withType(SdkAnnounceVersion::class.java).configureEach {
            it.mustRunAfter(context.tasks.withType(SdkReleaseVersion::class.java))
        }

        context.tasks.register("releaseToSdkMan") { task ->
            // We don't publish snapshots and alphas at all to SDKman.
            if (!isSnapshotOrAlphaRelease) {
                task.dependsOn(context.tasks.withType(SdkReleaseVersion::class.java))

                // We only announce and set the default version for final releases
                // A release is not final if it contains things different to numbers and dots.
                // For example:
                //   - 1.3: final
                //   - 1.3.25: final
                //   - 1.3-rc-4: not final
                //   - 1.3.RC5: not final
                //   - 1.3-milestone5: not final
                val isFinalRelease = Regex("""[0-9\.]*""").matchEntire(version) != null
                if (isFinalRelease) {
                    task.dependsOn(context.tasks.withType(SdkDefaultVersion::class.java))
                    task.dependsOn(context.tasks.withType(SdkAnnounceVersion::class.java))
                }
            }
        }
    }
}
