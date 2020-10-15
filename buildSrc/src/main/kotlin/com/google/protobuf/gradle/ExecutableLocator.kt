package com.google.protobuf.gradle

import org.gradle.api.Named

class ExecutableLocator(private val name: String) : Named {
    override fun getName(): String {
        return name
    }

    var artifact: String? = null
        /**
         * Specifies an artifact spec for downloading the executable from
         * repositories. spec format: '<groupId>:<artifactId>:<version>'
         */
        set(value) {
            field = value
            path = null
        }
    var path: String? = null
        /**
         * Specifies a local path.
         */
        set(value) {
            field = value
            artifact = null
        }
}