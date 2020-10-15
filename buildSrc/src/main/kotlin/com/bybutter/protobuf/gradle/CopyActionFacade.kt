package com.bybutter.protobuf.gradle

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.CopySpec

import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.WorkResult
import javax.inject.Inject

/**
 * Interface exposing the file copying feature. Actual implementations may use the
 * {@link org.gradle.api.file.FileSystemOperations} if available (Gradle 6.0+) or {@link org.gradle.api.Project#copy} if
 * the version of Gradle is below 6.0.
 */
interface CopyActionFacade {
    fun copy(action: Action<in CopySpec>): WorkResult

    class ProjectBased(private val project: Project) : CopyActionFacade {
        override fun copy(action: Action<in CopySpec>): WorkResult {
            return project.copy(action)
        }

    }

    abstract class FileSystemOperationsBased : CopyActionFacade {
        @get:Inject
        abstract val fileSystemOperations: FileSystemOperations

        override fun copy(action: Action<in CopySpec>): WorkResult {
            return fileSystemOperations.copy(action)
        }
    }
}