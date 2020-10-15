package com.bybutter.protobuf.gradle

import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.internal.file.FileOperations
import javax.inject.Inject

interface ArchiveActionFacade {
    fun zipTree(path: Any): FileTree

    fun tarTree(path: Any): FileTree

    class ProjectBased(private val project: Project) : ArchiveActionFacade {
        override fun zipTree(path: Any): FileTree {
            return project.zipTree(path)
        }

        override fun tarTree(path: Any): FileTree {
            return project.tarTree(path)
        }
    }

    abstract class ServiceBased : ArchiveActionFacade {
        // TODO Use public ArchiveOperations from Gradle 6.6 instead
        @get:Inject
        abstract val fileOperations: FileOperations

        override fun zipTree(path: Any): FileTree {
            return fileOperations.zipTree(path)
        }

        override fun tarTree(path: Any): FileTree {
            return fileOperations.tarTree(path)
        }
    }
}