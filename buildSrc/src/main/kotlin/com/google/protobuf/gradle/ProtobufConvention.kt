package com.google.protobuf.gradle

import org.gradle.api.Project
import org.gradle.api.internal.file.FileResolver

class ProtobufConvention(project: Project) {
    val protobuf: ProtobufConfigurator = ProtobufConfigurator(project)
}