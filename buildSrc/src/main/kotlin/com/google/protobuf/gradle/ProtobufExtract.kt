package com.google.protobuf.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import java.io.File
import javax.inject.Inject

/**
 * Extracts proto files from a dependency configuration.
 */
abstract class ProtobufExtract : DefaultTask() {
    /**
     * The directory for the extracted files.
     */
    private var destDir: File? = null
    private var isTest: Boolean? = null
    private val inputFiles: ConfigurableFileCollection = getObjectFactory().fileCollection()
    private val copyActionFacade: CopyActionFacade = instantiateCopyActionFacade()
    private val archiveActionFacade: ArchiveActionFacade = instantiateArchiveActionFacade()

    fun setIsTest(isTest: Boolean) {
        this.isTest = isTest
    }

    @Input
    fun getIsTest(): Boolean {
        return isTest!!
    }

    @InputFiles
    // TODO Review if NAME_ONLY is the best path sensitivity to use here
    @PathSensitive(PathSensitivity.NAME_ONLY)
    fun getInputFiles(): ConfigurableFileCollection {
        return inputFiles
    }

    @Internal
    fun getCopyActionFacade(): CopyActionFacade {
        return copyActionFacade
    }

    @Internal
    fun getArchiveActionFacade(): ArchiveActionFacade {
        return archiveActionFacade
    }

    @Inject
    abstract fun getObjectFactory(): ObjectFactory

    @TaskAction
    fun extract() {
        val destDir = this.destDir!!
        destDir.mkdir()
        var warningLogged = false
        inputFiles.forEach { file ->
            logger.debug("Extracting protos from $file to $destDir")
            if (file.isDirectory) {
                copyActionFacade.copy {
                    includeEmptyDirs = false
                    from(file.path) {
                        include ("**/*.proto")
                    }
                    into(destDir)
                }
            } else if (file.path.endsWith(".proto")) {
                if (!warningLogged) {
                    warningLogged = true
                    logger.warn(
                        "proto file '${file.path}' directly specified in configuration. " +
                                "It's likely you specified files('path/to/foo.proto') or " +
                                "fileTree('path/to/directory') in protobuf or compile configuration. " +
                                "This makes you vulnerable to " +
                                "https://github.com/google/protobuf-gradle-plugin/issues/248. " +
                                "Please use files('path/to/directory') instead."
                    )
                }
                copyActionFacade.copy {
                    includeEmptyDirs = false
                    from(file.path)
                    into(destDir)
                }
            } else if (file.path.endsWith(".jar") || file.path.endsWith(".zip")) {
                val zipTree: FileTree = archiveActionFacade.zipTree(file.path)
                copyActionFacade.copy {
                    includeEmptyDirs = false
                    from(zipTree) {
                        include ("**/*.proto")
                    }
                    into(destDir)
                }
            } else if (file.path.endsWith(".tar")
                || file.path.endsWith(".tar.gz")
                || file.path.endsWith(".tar.bz2")
                || file.path.endsWith(".tgz")
            ) {
                val tarTree: FileTree = archiveActionFacade.tarTree(file.path)
                copyActionFacade.copy {
                    includeEmptyDirs = false
                    from(tarTree) {
                        include("**/*.proto")
                    }
                    into(destDir)
                }
            } else {
                logger.debug("Skipping unsupported file type (${file.path}); handles only jar, tar, tar.gz, tar.bz2 & tgz")
            }
        }
    }

    protected fun setDestDir(destDir: File) {
        check(this.destDir == null) { "destDir already set" }
        this.destDir = destDir
        outputs.dir(destDir)
    }

    @OutputDirectory
    protected fun getDestDir(): File? {
        return destDir
    }

    private fun instantiateCopyActionFacade(): CopyActionFacade {
        if (Utils.compareGradleVersion(project, "6.0") > 0) {
            // Use object factory to instantiate as that will inject the necessary service.
            return getObjectFactory().newInstance(CopyActionFacade.FileSystemOperationsBased::class.java)
        }
        return CopyActionFacade.ProjectBased(project)
    }

    private fun instantiateArchiveActionFacade(): ArchiveActionFacade {
        if (Utils.compareGradleVersion(project, "6.0") > 0) {
            // Use object factory to instantiate as that will inject the necessary service.
            return getObjectFactory().newInstance(ArchiveActionFacade.ServiceBased::class.java)
        }
        return ArchiveActionFacade.ProjectBased(project)
    }
}