package com.replaymod.gradle.preprocess

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.compile.JavaCompile

import org.gradle.kotlin.dsl.*
import java.io.File
import java.nio.file.Path

class PreprocessPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val parent = project.parent
        if (parent == null) {
            project.apply<RootPreprocessPlugin>()
            return
        }

        project.evaluationDependsOn(parent.path)
        val rootExtension = parent.extensions.getByType<RootPreprocessExtension>()
        val graph = rootExtension.rootNode ?: throw IllegalStateException("Preprocess graph was not configured.")
        val projectNode = graph.findNode(project.name) ?: throw IllegalStateException("Prepocess graph does not contain ${project.name}.")

        val coreProjectFile = project.file("../mainProject")
        val coreProject = coreProjectFile.readText().trim()
        val mcVersion = projectNode.mcVersion
        project.extra["mcVersion"] = mcVersion
        val ext = project.extensions.create("preprocess", PreprocessExtension::class, project.objects, mcVersion)

        val kotlin = project.plugins.hasPlugin("kotlin")

        if (coreProject == project.name) {
            project.the<SourceSetContainer>().configureEach {
                java.setSrcDirs(listOf(parent.file("src/$name/java")))
                resources.setSrcDirs(listOf(parent.file("src/$name/resources")))
                if (kotlin) {
                    withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.setSrcDirs(listOf(
                            parent.file("src/$name/kotlin"),
                            parent.file("src/$name/java")
                    ))
                }
            }
        } else {
            val inheritedLink = projectNode.links.find { it.first.findNode(coreProject) != null }
            val (inheritedNode, extraMappings) = inheritedLink ?: graph.findParent(projectNode)!!
            val (mappingFile, mappingFileInverted) = extraMappings
            val reverseMappings = (inheritedLink != null) != mappingFileInverted
            val inherited = parent.evaluationDependsOn(inheritedNode.project)

            project.the<SourceSetContainer>().configureEach {
                val inheritedSourceSet = inherited.the<SourceSetContainer>()[name]
                val cName = if (name == "main") "" else name.capitalize()
                val overwritesKotlin = project.file("src/$name/kotlin").also { it.mkdirs() }
                val overwritesJava = project.file("src/$name/java").also { it.mkdirs() }
                val overwriteResources = project.file("src/$name/resources").also { it.mkdirs() }
                val preprocessedRoot = project.buildDir.resolve("preprocessed/$name")
                val generatedKotlin = preprocessedRoot.resolve("kotlin")
                val generatedJava = preprocessedRoot.resolve("java")
                val generatedResources = preprocessedRoot.resolve("resources")

                val preprocessCode = project.tasks.register<PreprocessTask>("preprocess${cName}Code") {
                    inherited.tasks.findByPath("preprocess${cName}Code")?.let { dependsOn(it) }
                    entry(
                        source = inherited.files(inheritedSourceSet.java.srcDirs),
                        overwrites = overwritesJava,
                        generated = generatedJava,
                    )
                    if (kotlin) {
                        entry(
                            source = inherited.files(inheritedSourceSet.withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.srcDirs.filter {
                                it.endsWith("kotlin")
                            }),
                            overwrites = overwritesKotlin,
                            generated = generatedKotlin,
                        )
                    }
                    jdkHome.set((inherited.tasks["compileJava"] as JavaCompile).javaCompiler.map { it.metadata.installationPath })
                    remappedjdkHome.set((project.tasks["compileJava"] as JavaCompile).javaCompiler.map { it.metadata.installationPath })
                    classpath = inherited.tasks["compile${cName}${if (kotlin) "Kotlin" else "Java"}"].classpath
                    remappedClasspath = project.tasks["compile${cName}${if (kotlin) "Kotlin" else "Java"}"].classpath
                    mapping = mappingFile
                    reverseMapping = reverseMappings
                    vars.convention(ext.vars)
                    keywords.convention(ext.keywords)
                    patternAnnotation.convention(ext.patternAnnotation)
                    manageImports.convention(ext.manageImports)
                }
                val sourceJavaTask = project.tasks.findByName("source${name.capitalize()}Java")
                (sourceJavaTask ?: project.tasks["compile${cName}Java"]).dependsOn(preprocessCode)
                java.setSrcDirs(listOf(overwritesJava, preprocessCode.map { generatedJava }))

                if (kotlin) {
                    val kotlinConsumerTask = project.tasks.findByName("source${name.capitalize()}Kotlin")
                            ?: project.tasks["compile${cName}Kotlin"]
                    kotlinConsumerTask.dependsOn(preprocessCode)
                    withGroovyBuilder { getProperty("kotlin") as SourceDirectorySet }.setSrcDirs(
                            listOf(
                                overwritesKotlin,
                                preprocessCode.map { generatedKotlin },
                                overwritesJava,
                                preprocessCode.map { generatedJava },
                            ))
                }

                val preprocessResources = project.tasks.register<PreprocessTask>("preprocess${cName}Resources") {
                    inherited.tasks.findByPath("preprocess${cName}Resources")?.let { dependsOn(it) }
                    entry(
                        source = inherited.files(inheritedSourceSet.resources.srcDirs),
                        overwrites = overwriteResources,
                        generated = generatedResources,
                    )
                    vars.convention(ext.vars)
                    keywords.convention(ext.keywords)
                    patternAnnotation.convention(ext.patternAnnotation)
                    manageImports.convention(ext.manageImports)
                }
                project.tasks["process${cName}Resources"].dependsOn(preprocessResources)
                resources.setSrcDirs(listOf(overwriteResources, preprocessResources.map { generatedResources }))
            }

            project.tasks.register<Copy>("setCoreVersion") {
                outputs.upToDateWhen { false }

                from(project.file("src"))
                from(File(project.buildDir, "preprocessed"))
                into(File(parent.projectDir, "src"))

                project.the<SourceSetContainer>().all {
                    val cName = if (name == "main") "" else name.capitalize()

                    dependsOn(project.tasks.named("preprocess${cName}Code"))
                    dependsOn(project.tasks.named("preprocess${cName}Resources"))
                }

                doFirst {
                    // "Overwrites" for the core project are just stored in the main sources
                    // which will get overwritten soon, so we need to preserve those.
                    // Specifically, assume we've got projects A, B and C with C being the current
                    // core project and A being the soon to be one:
                    // If there is an overwrite in B, we need to preserve A's source version in A's overwrites.
                    // If there is an overwrite in C, we need to preserve B's version in B's overwrites and get
                    // rid of C's overwrite since it will now be stored in the main sources.
                    fun preserveOverwrites(project: Project, toBePreserved: List<Path>?) {
                        val overwrites = project.file("src").toPath()
                        val overwritten = overwrites.toFile()
                                .walk()
                                .filter { it.isFile }
                                .map { overwrites.relativize(it.toPath()) }
                                .toList()

                        // For the soon-to-be-core project, we must not yet delete the overwrites
                        // as they have yet to be copied into the main sources.
                        if (toBePreserved != null) {
                            val source = if (project.name == coreProject) {
                                project.parent!!.file( "src").toPath()
                            } else {
                                project.buildDir.toPath().resolve("preprocessed")
                            }
                            project.delete(overwrites)
                            toBePreserved.forEach { name ->
                                project.copy {
                                    from(source.resolve(name))
                                    into(overwrites.resolve(name).parent)
                                }
                            }
                        }

                        if (project.name != coreProject) {
                            val node = graph.findNode(project.name)!!
                            val nextLink = node.links.find { it.first.findNode(coreProject) != null }
                            val (nextNode, _) = nextLink ?: graph.findParent(node)!!
                            val nextProject = parent.project(nextNode.project)
                            preserveOverwrites(nextProject, overwritten)
                        }
                    }
                    preserveOverwrites(project, null)
                }

                doLast {
                    // Once our own overwrites have been copied into the main sources, we should remove them.
                    val overwrites = project.file("src")
                    project.delete(overwrites)
                    project.mkdir(overwrites)
                }

                doLast {
                    coreProjectFile.writeText(project.name)
                }
            }
        }
    }
}

private val Task.classpath: FileCollection?
    get() = if (this is AbstractCompile) {
        this.classpath
    } else {
        // assume kotlin 1.7+
        try {
            val classpathMethod = this.javaClass.getMethod("getLibraries")
            classpathMethod.invoke(this) as FileCollection?
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }
    }
