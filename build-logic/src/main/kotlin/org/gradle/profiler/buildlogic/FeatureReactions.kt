package org.gradle.profiler.buildlogic

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.npm.task.NpxTask
import com.google.protobuf.gradle.ProtobufExtension
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import tasks.FetchPerfettoProtoTask
import java.io.File

class PublishingReaction : Reaction<Publishing, Project> {
    override fun on(data: Publishing, context: Project, scope: ReactionScope) {
        context.pluginManager.apply("maven-publish")
        context.pluginManager.apply("signing")

        context.extensions.getByType(JavaPluginExtension::class.java).apply {
            withSourcesJar()
            withJavadocJar()
        }

        context.tasks.register("checkDescription") { task ->
            val description = context.provider { context.description }
            val projectPath = context.path
            task.doFirst {
                check(description.orNull != null) { "You must set the description of published project '$projectPath'" }
            }
        }
        context.tasks.named("sanityCheck") { it.dependsOn("checkDescription") }

        val publishing = context.extensions.getByType(PublishingExtension::class.java)
        publishing.publications.register("mavenJava", MavenPublication::class.java) { publication ->
            publication.from(context.components.getByName("java"))
            publication.pom { pom ->
                pom.name.set(context.provider { "${publication.groupId}:${publication.artifactId}" })
                pom.description.set(context.provider {
                    requireNotNull(context.description) { "You must set the description of published project ${context.name}" }
                })
                pom.url.set("https://github.com/gradle/gradle-profiler")
                pom.licenses { licenses ->
                    licenses.license { license ->
                        license.name.set("The Apache License, Version 2.0")
                        license.url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                pom.developers { developers ->
                    developers.developer { developer ->
                        developer.name.set("The Gradle team")
                        developer.organization.set("Gradle Technologies")
                        developer.organizationUrl.set("https://gradle.org")
                    }
                }
                pom.scm { scm ->
                    scm.connection.set("scm:git:git://github.com/gradle/gradle-profiler.git")
                    scm.developerConnection.set("scm:git:ssh://github.com:gradle/gradle-profiler.git")
                    scm.url.set("https://github.com/gradle/gradle-profiler/tree/master")
                }
            }
        }

        val environment = context.providers
        val signArtifacts = !environment.environmentVariable("PGP_SIGNING_KEY").orNull.isNullOrEmpty()
        context.tasks.withType(Sign::class.java).configureEach { it.isEnabled = signArtifacts }

        val signing = context.extensions.getByType(SigningExtension::class.java)
        signing.useInMemoryPgpKeys(
            // Key ID required when signing with a subkey
            environment.environmentVariable("PGP_SIGNING_KEY_ID").orNull,
            environment.environmentVariable("PGP_SIGNING_KEY").orNull,
            environment.environmentVariable("PGP_SIGNING_KEY_PASSPHRASE").orNull
        )
        if (signArtifacts) {
            publishing.publications.configureEach { signing.sign(it) }
        }
    }
}

class JavaAgentReaction : Reaction<JavaAgent, Project> {
    override fun on(data: JavaAgent, context: Project, scope: ReactionScope) {
        val addExports = data.addExports().getOrElse(emptyList())
        context.tasks.named("compileJava", JavaCompile::class.java) { compile ->
            addExports.forEach { compile.options.compilerArgs.addAll(listOf("--add-exports", it)) }
        }
        context.tasks.named("javadoc", Javadoc::class.java) { javadoc ->
            addExports.forEach { (javadoc.options as StandardJavadocDocletOptions).addStringOption("-add-exports", it) }
        }
        context.tasks.named("jar", Jar::class.java) { jar ->
            jar.manifest.attributes(mapOf("Premain-Class" to data.premainClass().get()))
        }
    }
}

class PerfettoProtoReaction : Reaction<PerfettoProto, Project> {
    override fun on(data: PerfettoProto, context: Project, scope: ReactionScope) {
        context.pluginManager.apply("com.google.protobuf")

        val fetchPerfettoProto = context.tasks.register("fetchPerfettoProto", FetchPerfettoProtoTask::class.java) { task ->
            task.version.set(data.version())
            task.sha256.set(data.sha256())
            task.outputDir.convention(context.layout.buildDirectory.dir("perfetto-proto"))
        }

        context.extensions.getByType(ProtobufExtension::class.java).protoc { protoc ->
            protoc.artifact = Catalog.libs(context).findLibrary("protobuf-protoc").orElseThrow().get().toString()
        }

        // Pass the task (not just the dir) so every consumer of the proto source — generateProto,
        // processResources, sourcesJar — inherits the dependency on the download.
        val mainSourceSet = context.extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName("main")
        val protoSources = (mainSourceSet as ExtensionAware).extensions.getByName("proto") as SourceDirectorySet
        protoSources.srcDir(fetchPerfettoProto)
    }
}

class HtmlReportReaction : Reaction<HtmlReport, Project> {
    override fun on(data: HtmlReport, context: Project, scope: ReactionScope) {
        context.pluginManager.apply("com.github.node-gradle.node")
        context.extensions.getByType(NodeExtension::class.java).apply {
            download.set(true)
            version.set(data.nodeVersion())
        }
        // The node plugin registers a project-level repository for its downloads, which hides the
        // settings repositories from this project.
        ProfilerRepositories.libraries(context.repositories)

        val generateHtmlReportJavaScript = context.tasks.register("generateHtmlReportJavaScript", NpxTask::class.java) { task ->
            task.dependsOn("npmInstall")
            val source = context.file("src/main/js/org/gradle/profiler/report/report.js")
            val outputDir = context.layout.buildDirectory.dir("html-report")
            val output = outputDir.map { it.file("org/gradle/profiler/report/report.js") }
            task.inputs.file(source)
            task.outputs.dir(outputDir)
            task.command.set("browserify")
            task.args.addAll(source.absolutePath, "--outfile", output.get().asFile.absolutePath)
        }

        val processResources = context.tasks.named("processResources", ProcessResources::class.java)
        processResources.configure { it.from(generateHtmlReportJavaScript) }

        val testReports = mapOf(
            "testHtmlReport" to "example",
            "testHtmlReportSingle" to "example-single",
            "testHtmlReportCacheSize" to "example-cache-size",
            "testHtmlReportWithOps" to "example-with-build-operations",
            "testHtmlReportRegression" to "example-regression"
        )
        testReports.forEach { (taskName, fileName) ->
            context.tasks.register(taskName, ProcessResources::class.java) { task ->
                val dataFile = context.file("src/test/resources/org/gradle/profiler/report/$fileName.json")
                task.inputs.file(dataFile)
                task.inputs.files(processResources)

                task.from("src/main/resources/org/gradle/profiler/report")
                task.into(context.layout.buildDirectory.dir("test-html-report"))
                task.rename("report-template.html", "test-report-$fileName.html")
                val scriptFile = File(processResources.get().destinationDir, "org/gradle/profiler/report/report.js")
                task.filter { line ->
                    when (line) {
                        "@@BENCHMARK_RESULT_JSON@@" -> dataFile.readText()
                        "@@SCRIPT@@" -> scriptFile.readText(Charsets.UTF_8)
                        else -> line
                    }
                }
            }
        }
        context.tasks.register("testHtmlReports") { it.dependsOn(testReports.keys) }
    }
}
