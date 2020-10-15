package com.google.protobuf.gradle

import org.gradle.api.Project

fun Project.protobuf(action: ProtobufConfigurator.()->Unit) {
    project.convention.getPlugin(ProtobufConvention::class.java).protobuf.apply(action)
}