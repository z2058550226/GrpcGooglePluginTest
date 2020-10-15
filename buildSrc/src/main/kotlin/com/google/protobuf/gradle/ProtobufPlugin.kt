package com.google.protobuf.gradle

import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.TestedAndroidConfig
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.builder.model.SourceProvider
import org.gradle.api.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.tasks.SourceTask
import org.gradle.kotlin.dsl.get
import java.io.File
import javax.inject.Inject

class ProtobufPlugin constructor() : Plugin<Project> {
    companion object {
        private fun linkGenerateProtoTasksToTask(
            task: SourceTask,
            genProtoTask: GenerateProtoTask
        ) {
            task.dependsOn(genProtoTask)
            task.source(
                genProtoTask.getOutputSourceDirectorySet().include(
                    "**/*.java",
                    "**/*.kt"
                )
            )
        }
    }

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
        val extension = project.android
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
        generateProtoTask.doneInitializing()

        variant.sourceSets.forEach {
            setupExtractProtosTask(generateProtoTask, it.name)
        }

        if (variant.hasMetaProperty("compileConfiguration")) {
            // For Android Gradle plugin >= 2.5
            val artifactType: Attribute<String> = Attribute.of("artifactType", String::class.java)
            val name: String = variant.name
            val classPathConfig: FileCollection =
                variant.compileConfiguration.incoming.artifactView {
                    attributes {
                        attribute(artifactType, "jar")
                    }
                }.files
            val testClassPathConfig: FileCollection? =
                if (variant is TestVariant)
                    variant.testedVariant.compileConfiguration.incoming.artifactView {
                        attributes {
                            attribute(artifactType, "jar")
                        }
                    }.files else null
            setupExtractIncludeProtosTask(
                generateProtoTask,
                name,
                classPathConfig,
                testClassPathConfig
            )
        }
    }

    private fun addGenerateProtoTask(
        sourceSetOrVariantName: String,
        sourceSets: List<SourceProvider>
    ): GenerateProtoTask {
        val generateProtoTaskName =
            "generate${Utils.getSourceSetSubstringForTaskNames(sourceSetOrVariantName)}Proto"
        return project.tasks.create(generateProtoTaskName, GenerateProtoTask::class.java) {
            description = "Compiles Proto source for '${sourceSetOrVariantName}'"

            val protobufConvention = project.protobuf
            setOutputBaseDir("${protobufConvention.protobuf.generatedFilesBaseDir}/${sourceSetOrVariantName}")
            setFileResolver(fileResolver)
            sourceSets.forEach { sourceSet ->
                val protoSrcDirSet: SourceDirectorySet = (sourceSet as AndroidSourceSet).proto
                addSourceFiles(protoSrcDirSet)
                addIncludeDir(protoSrcDirSet.sourceDirectories)
            }
        }
    }

    /**
     * Sets up a task to extract protos from protobuf dependencies. They are
     * treated as sources and will be compiled.
     *
     * <p>This task is per-sourceSet, for both Java and Android. In Android a
     * variant may have multiple sourceSets, each of these sourceSets will have
     * its own extraction task.
     */
    private fun setupExtractProtosTask(
        generateProtoTask: GenerateProtoTask, sourceSetName: String
    ): Task {
        val extractProtosTaskName: String = "extract" +
                Utils.getSourceSetSubstringForTaskNames(sourceSetName) + "Proto"
        var task: ProtobufExtract? =
            project.tasks.findByName(extractProtosTaskName) as? ProtobufExtract
        if (task == null) {
            task = project.tasks.create(extractProtosTaskName, ProtobufExtract::class.java) {
                description =
                    "Extracts proto files/dependencies specified by 'protobuf' configuration"
                setDestDir(File(getExtractedProtosDir(sourceSetName)))

                getInputFiles().from(
                    project.configurations[Utils.getConfigName(sourceSetName, "protobuf")]
                )
                setIsTest(Utils.isTest(sourceSetName))
            }
        }

        linkExtractTaskToGenerateTask(task, generateProtoTask)
        generateProtoTask.addSourceFiles(project.fileTree(task.getDestDir()!!) { include("**/*.proto") })
        return task
    }


    /**
     * Sets up a task to extract protos from compile dependencies of a sourceSet, Those are needed
     * for imports in proto files, but they won't be compiled since they have already been compiled
     * in their own projects or artifacts.
     *
     * <p>This task is per-sourceSet for both Java and per variant for Android.
     */
    private fun setupExtractIncludeProtosTask(
        generateProtoTask: GenerateProtoTask,
        sourceSetOrVariantName: String,
        compileClasspathConfiguration: FileCollection? = null,
        testedCompileClasspathConfiguration: FileCollection? = null
    ) {
        val extractIncludeProtosTaskName: String = "extractInclude" +
                Utils.getSourceSetSubstringForTaskNames(sourceSetOrVariantName) + "Proto"
        var task: ProtobufExtract? =
            project.tasks.findByName(extractIncludeProtosTaskName) as? ProtobufExtract
        if (task == null) {
            task = project.tasks.create(extractIncludeProtosTaskName, ProtobufExtract::class.java) {
                description = "Extracts proto files from compile dependencies for includes"

                setDestDir(File(getExtractedIncludeProtosDir(sourceSetOrVariantName)))
                val path: FileCollection = compileClasspathConfiguration
                    ?: project.configurations.getByName(
                        Utils.getConfigName(
                            sourceSetOrVariantName,
                            "compileProtoPath"
                        )
                    )
                getInputFiles().from(path)

                if (Utils.isTest(sourceSetOrVariantName)) {
                    getInputFiles().setFrom(project.android.sourceSets["main"].proto)
                    getInputFiles().setFrom(
                        testedCompileClasspathConfiguration ?: project.configurations["compile"]
                    )
                }

                setIsTest(Utils.isTest(sourceSetOrVariantName))
            }
        }

        linkExtractTaskToGenerateTask(task, generateProtoTask)
    }

    private fun linkExtractTaskToGenerateTask(
        extractTask: ProtobufExtract,
        generateTask: GenerateProtoTask
    ) {
        generateTask.dependsOn(extractTask)
        generateTask.addIncludeDir(project.files(extractTask.getDestDir()))
    }

    private fun linkGenerateProtoTasksToTaskName(
        compileTaskName: String,
        genProtoTask: GenerateProtoTask
    ) {
        val compileTask: Task? = project.tasks.findByName(compileTaskName)
        if (compileTask != null) {
            linkGenerateProtoTasksToTask(compileTask as SourceTask, genProtoTask)
        } else {
            project.tasks.whenTaskAdded {
                if (name == compileTaskName) {
                    linkGenerateProtoTasksToTask(this as SourceTask, genProtoTask)
                }
            }
        }
    }

    private fun linkGenerateProtoTasksToSourceCompile() {
        val android = project.android
        if (android is TestedAndroidConfig) {
            getNonTestVariants() + android.testVariants
        } else {
            getNonTestVariants()
        }.forEach { variant ->
            (project.protobuf.protobuf.getGenerateProtoTasks() as ProtobufConfigurator.AndroidGenerateProtoTaskCollection)
                .ofVariant(variant.name).forEach { genProtoTask: GenerateProtoTask ->
                    val generatedSources: SourceDirectorySet =
                        genProtoTask.getOutputSourceDirectorySet()
                    variant.registerJavaGeneratingTask(genProtoTask, generatedSources.srcDirs)
                    linkGenerateProtoTasksToTaskName(
                        Utils.getKotlinAndroidCompileTaskName(variant.name), genProtoTask
                    )
                }
        }

        if (android is TestedAndroidConfig) {
            android.unitTestVariants.forEach { variant ->
                (project.protobuf.protobuf.getGenerateProtoTasks() as ProtobufConfigurator.AndroidGenerateProtoTaskCollection)
                    .ofVariant(variant.name)
                    .forEach { genProtoTask: GenerateProtoTask ->
                        // unit test variants do not implement registerJavaGeneratingTask
                        val javaCompileTask: Task? = variant.javaCompileProvider.get()
                        if (javaCompileTask != null) {
                            linkGenerateProtoTasksToTask(
                                javaCompileTask as SourceTask,
                                genProtoTask
                            )
                        }

                        linkGenerateProtoTasksToTaskName(
                            Utils.getKotlinAndroidCompileTaskName(variant.name),
                            genProtoTask
                        )
                    }
            }
        }

    }

    private fun getExtractedIncludeProtosDir(sourceSetName: String): String {
        return "${project.buildDir}/extracted-include-protos/${sourceSetName}"
    }

    private fun getExtractedProtosDir(sourceSetName: String): String {
        return "${project.buildDir}/extracted-protos/${sourceSetName}"
    }

    /**
     * Adds proto sources and generated sources to supported IDE plugins.
     */
    private fun addSourcesToIde() {
        project.tasks.withType(GenerateProtoTask::class.java)
            .forEach { generateProtoTask: GenerateProtoTask ->
                generateProtoTask.getOutputSourceDirectorySet().srcDirs.forEach { outputDir: File ->
                    generateProtoTask.getVariant().addJavaSourceFoldersToModel(outputDir)
                }
            }
    }

    private val Project.protobuf: ProtobufConvention get() = convention.plugins["protobuf"] as ProtobufConvention

    private val AndroidSourceSet.proto: SourceDirectorySet get() = sourceSetsProtoMeta[this]!!
}