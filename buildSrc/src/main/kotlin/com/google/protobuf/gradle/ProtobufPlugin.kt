package com.google.protobuf.gradle

import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.api.DefaultAndroidSourceSet
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.builder.model.SourceProvider
import org.gradle.api.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.file.FileResolver
import javax.inject.Inject

class ProtobufPlugin constructor() : Plugin<Project> {
    private var fileResolver: FileResolver? = null

    // Dummy groovy meta-programming, for sourceSet.proto extension
    private val sourceSetsProtoMeta: MutableMap<AndroidSourceSet, SourceDirectorySet> =
        mutableMapOf()

    @Suppress("unused")
    @Inject
    constructor(fileResolver: FileResolver?) : this() {
        println("second constructor")
        this.fileResolver = fileResolver
    }

    lateinit var project: Project

    override fun apply(project: Project) {
        println("***** google proto buff *****")

        this.project = project

        project.apply(mapOf("plugin" to com.google.gradle.osdetector.OsDetectorPlugin::class.java))

        // name to convention
        project.convention.plugins["protobuf"] = ProtobufConvention(project)

        addSourceSetExtensions()
        sourceSets.forEach { createConfigurations(it.name) }

        project.afterEvaluate {
            addProtoTasks()
        }
    }

    private fun addSourceSetExtensions() {
        sourceSets.forEach { sourceSet ->
            val name = sourceSet.name
            println("sourceSet.name: ${sourceSet.name}")
            val protoSrcDirSet: SourceDirectorySet =
                project.objects.sourceDirectorySet(name, "$name Proto source")
            sourceSetsProtoMeta[sourceSet] = protoSrcDirSet
            protoSrcDirSet.srcDir("src/$name/proto")
            protoSrcDirSet.include("**/*.proto")
        }
    }

    private val sourceSets: NamedDomainObjectContainer<AndroidSourceSet>
        get() {
            val appModuleExtension =
                project.extensions.getByName("android") as BaseAppModuleExtension
//            val javaSourceSet = project.extensions.getByType(SourceSetContainer::class.java)
            return appModuleExtension.sourceSets
        }

    private fun createConfigurations(sourceSetName: String) {
        val protobufConfigName = Utils.getConfigName(sourceSetName, "protobuf")
        if (project.configurations.findByName(protobufConfigName) == null) {
            project.configurations.create(protobufConfigName) {
                isVisible = false
                isTransitive = true
                setExtendsFrom(emptyList())
            }
        }

        val compileProtoConfigName = Utils.getConfigName(sourceSetName, "compileProtoPath")
        val compileConfig: Configuration? =
            project.configurations.findByName(Utils.getConfigName(sourceSetName, "compileOnly"))
        val implementationConfig: Configuration? =
            project.configurations.findByName(Utils.getConfigName(sourceSetName, "implementation"))

        if (compileConfig != null && implementationConfig != null &&
            project.configurations.findByName(compileProtoConfigName) == null
        ) {
            project.configurations.create(compileProtoConfigName) {
                isVisible = false
                isTransitive = true
                setExtendsFrom(listOf(compileConfig, implementationConfig))
                isCanBeConsumed = false
            }.attributes.attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(
                    LibraryElements::class.java, LibraryElements.RESOURCES
                )
            )
        }
    }

    private fun addProtoTasks() {
        getNonTestVariants().forEach { variant ->
            addTasksForVariant(variant, false)
        }
    }

    private fun getNonTestVariants(): DomainObjectSet<out BaseVariant> {
        val extension = project.extensions.getByName("android")
        if (extension is AbstractAppExtension) {
            return extension.applicationVariants
        } else if (extension is LibraryExtension) {
            return extension.libraryVariants
        }
        throw IllegalStateException()
    }

    private fun addTasksForVariant(variant: BaseVariant, isTestVariant: Boolean) {
        val generateProtoTask: GenerateProtoTask =
            addGenerateProtoTask(variant.name, variant.sourceSets)
        generateProtoTask.setVariant(variant, isTestVariant)
        generateProtoTask.setFlavors(variant.productFlavors.map { it.name })

        if (variant.hasMetaProperty("buildType")) {
            generateProtoTask.setBuildType(variant.buildType.name)
        }
//        generateProtoTask.doneInitializing()
//
//        variant.sourceSets.each {
//            setupExtractProtosTask(generateProtoTask, it.name)
//        }
//
//        if (variant.hasProperty("compileConfiguration")) {
//            // For Android Gradle plugin >= 2.5
//            Attribute artifactType = Attribute . of ("artifactType", String)
//            String name = variant . name
//                    FileCollection classPathConfig = variant . compileConfiguration . incoming . artifactView {
//                attributes {
//                    it.attribute(artifactType, "jar")
//                }
//            }.files
//            FileCollection testClassPathConfig =
//            variant.hasProperty("testedVariant") ?
//            variant.testedVariant.compileConfiguration.incoming.artifactView {
//                attributes {
//                    it.attribute(artifactType, "jar")
//                }
//            }.files : null
//            setupExtractIncludeProtosTask(
//                generateProtoTask,
//                name,
//                classPathConfig,
//                testClassPathConfig
//            )
//        } else {
//            // For Android Gradle plugin < 2.5
//            variant.sourceSets.each {
//                setupExtractIncludeProtosTask(generateProtoTask, it.name)
//            }
//        }
    }

    private fun addGenerateProtoTask(
        sourceSetOrVariantName: String,
        sourceSets: List<SourceProvider>
    ): GenerateProtoTask {
        val generateProtoTaskName =
            "generate${Utils.getSourceSetSubstringForTaskNames(sourceSetOrVariantName)}Proto"
        return project.tasks.create(generateProtoTaskName, GenerateProtoTask::class.java) {
            description = "Compiles Proto source for '${sourceSetOrVariantName}'"

            val protobufConvention =
                project.convention.plugins["protobuf"] as ProtobufConvention
            setOutputBaseDir("${protobufConvention.protobuf.generatedFilesBaseDir}/${sourceSetOrVariantName}")
            setFileResolver(fileResolver)
            sourceSets.forEach { sourceSet ->
                val protoSrcDirSet: SourceDirectorySet =
                    sourceSetsProtoMeta[sourceSet as DefaultAndroidSourceSet] ?: return@forEach
                addSourceFiles(protoSrcDirSet)
                addIncludeDir(protoSrcDirSet.sourceDirectories)
            }
        }
    }

    /* private Task addGenerateProtoTask(String sourceSetOrVariantName, Collection<Object> sourceSets) {
         String generateProtoTaskName = 'generate' +
         Utils.getSourceSetSubstringForTaskNames(sourceSetOrVariantName) + 'Proto'
         return project.tasks.create(generateProtoTaskName, GenerateProtoTask) {
             description = "Compiles Proto source for '${sourceSetOrVariantName}'"
             outputBaseDir = "${project.protobuf.generatedFilesBaseDir}/${sourceSetOrVariantName}"
             it.fileResolver = this.fileResolver
             sourceSets.each { sourceSet ->
                 addSourceFiles(sourceSet.proto)
                 SourceDirectorySet protoSrcDirSet = sourceSet.proto
                         addIncludeDir(protoSrcDirSet.sourceDirectories)
             }
             ProtobufConfigurator extension = project.protobuf
                     protocLocator.set(project.providers.provider { extension.tools.protoc })
             pluginsExecutableLocators.set(project.providers.provider {
                 ((NamedDomainObjectContainer<ExecutableLocator>) extension.tools.plugins).asMap
             })
         }
     }*/

}