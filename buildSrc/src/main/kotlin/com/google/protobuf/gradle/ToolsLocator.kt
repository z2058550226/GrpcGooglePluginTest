package com.google.protobuf.gradle

import com.google.gradle.osdetector.OsDetector
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection

class ToolsLocator(private val project: Project) {
    companion object{
        fun artifactParts(artifactCoordinate: String): List<String?> {
            var (artifact, extension) = artifactCoordinate.tokenize("@")
            if (extension == null && artifactCoordinate.endsWith("@")) {
                extension = ""
            }
            val (group, name, version, classifier) = artifact!!.tokenize(":")

            return listOf(group, name, version, classifier, extension)
        }
    }

    val protoc: ExecutableLocator = ExecutableLocator("protoc")
    val plugins: NamedDomainObjectContainer<ExecutableLocator> =
        project.container(ExecutableLocator::class.java)

    /**
     * For every ExecutableLocator that points to an artifact spec: creates a
     * project configuration dependency for that artifact, registers the
     * configuration dependency as an input dependency with the specified tasks,
     * and adds a doFirst {} block to the specified tasks which resolves the
     * spec, downloads the artifact, and point to the local path.
     */
    fun registerTaskDependencies(protoTasks: Collection<GenerateProtoTask>) {
        if (protoc.getArtifact() != null) {
            registerDependencyWithTasks(protoc, protoTasks)
        } else if (protoc.getPath() == null) {
            protoc.setPath("protoc")
        }
        for (pluginLocator in plugins) {
            if (pluginLocator.getArtifact() != null) {
                registerDependencyWithTasks(pluginLocator, protoTasks)
            } else if (pluginLocator.getPath() == null) {
                pluginLocator.setPath("protoc-gen-${pluginLocator.name}")
            }
        }
    }

    fun registerDependencyWithTasks(
        locator: ExecutableLocator,
        protoTasks: Collection<GenerateProtoTask>
    ) {
        // create a project configuration dependency for the artifact
        val config: Configuration =
            project.configurations.create("protobufToolsLocator_${locator.name}") {
                isVisible = false
                isTransitive = false
                setExtendsFrom(ArrayList())
            }
        val (groupId, artifact, version, classifier, extension) = artifactParts(locator.getArtifact()!!)
        val notation: Map<String, String> = mapOf(
            "group" to groupId!!,
            "name" to artifact!!,
            "version" to version!!,
            "classifier" to (classifier ?: (project.extensions.getByName("osdetector") as OsDetector).classifier!!),
            "ext" to (extension ?: "exe")
        )
        val dep: Dependency = project.dependencies.add(config.name, notation)!!
        val artifactFiles: FileCollection = config.fileCollection(dep)

        for (protoTask in protoTasks) {
            if (protoc === locator || protoTask.hasPlugin(locator.name)) {
                protoTask.getLocatorToAlternativePathsMapping().put(locator.name, artifactFiles)
            }
        }
    }
}