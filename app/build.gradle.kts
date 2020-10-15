import com.bybutter.protobuf.gradle.id

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-android-extensions")
    id("com.bybutter.protobuf")
}

android {
    compileSdkVersion(29)
    defaultConfig {
        applicationId = "com.bybutter.grpc"
        minSdkVersion(23)
        targetSdkVersion(29)
        versionCode = 1
        versionName = "1.0"
    }
    sourceSets.getByName("main")
        .java.srcDirs("${protobuf.protobuf.generatedFilesBaseDir}/main/javalite")
}

//apply(plugin = "com.google.protobuf")

val grpcVersion = "1.24.0"
protobuf.protobuf.run {
    protoc {
        setArtifact("com.google.protobuf:protoc:3.10.0")
    }

    plugins {
        id("javalite") {
            setArtifact("com.google.protobuf:protoc-gen-javalite:3.0.0")
        }
        id("grpc") { setArtifact("io.grpc:protoc-gen-grpc-java:$grpcVersion") }
    }

    generateProtoTasks {
        all().forEach {
            it.builtins {
                //  remove("java")
            }

            it.plugins {
                create("javalite") {
                    outputSubDir = "java"
                }
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.4.10")
    implementation("androidx.core:core-ktx:1.3.1")
    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("androidx.constraintlayout:constraintlayout:2.0.1")

    protobuf("com.bybutter.camera.api:camera-account-protos:1.0.1")
    protobuf("com.bybutter.camera.api:camera-zakka-protos:1.0.1")

    implementation("io.grpc:grpc-okhttp:$grpcVersion")
    implementation("io.grpc:grpc-protobuf-lite:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
}

