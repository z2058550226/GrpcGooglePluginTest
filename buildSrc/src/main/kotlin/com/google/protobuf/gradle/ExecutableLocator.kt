package com.google.protobuf.gradle

import org.gradle.api.Named

class ExecutableLocator(private val name: String) : Named {
    override fun getName(): String {
        return name
    }

    private var artifact: String? = null

    private var path: String? = null

    /**
     * Specifies an artifact spec for downloading the executable from
     * repositories. spec format: '<groupId>:<artifactId>:<version>'
     */
    fun setArtifact(artifact: String?) {
        this.artifact = artifact
        path = null
    }

    fun getArtifact(): String? {
        return artifact
    }

    /**
     * Specifies a local path.
     */
    fun setPath(path: String?) {
        this.path = path
        artifact = null
    }

    fun getPath(): String? {
        return path
    }
}