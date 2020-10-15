package com.bybutter.protobuf.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskCollection
import groovy.lang.Closure
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.kotlin.dsl.NamedDomainObjectContainerScope
import org.gradle.kotlin.dsl.closureOf
import org.gradle.kotlin.dsl.invoke
import org.gradle.util.ConfigureUtil

class ProtobufConfigurator(private val project: Project) {
    private val tasks: GenerateProtoTaskCollection = AndroidGenerateProtoTaskCollection()
    private val tools = ToolsLocator(project)
    private val taskConfigClosures: ArrayList<Closure<*>> = ArrayList()

    /**
     * The base directory of generated files. The default is
     * "${project.buildDir}/generated/source/proto".
     */
    val generatedFilesBaseDir = "${project.buildDir}/generated/source/proto"

    fun runTaskConfigClosures() {
        taskConfigClosures.forEach { closure ->
            ConfigureUtil.configure(closure, tasks)
        }
    }

    //===========================================================================
    //         Configuration methods
    //===========================================================================

    /**
     * Locates the protoc executable. The closure will be manipulating an
     * ExecutableLocator.
     */
    fun protoc(action: ExecutableLocator.() -> Unit) {
        ConfigureUtil.configure(closureOf(action), tools.protoc)
    }

    /**
     * Locate the codegen plugin executables. The closure will be manipulating a
     * NamedDomainObjectContainer<ExecutableLocator>.
     */
    fun plugins(action: NamedDomainObjectContainerScope<ExecutableLocator>.() -> Unit) {
        ConfigureUtil.configure(closureOf<NamedDomainObjectContainer<ExecutableLocator>> {
            this(action)
        }, tools.plugins)
    }

    /**
     * Configures the generateProto tasks in the given closure.
     *
     * <p>The closure will be manipulating a JavaGenerateProtoTaskCollection or
     * an AndroidGenerateProtoTaskCollection depending on whether the project is
     * Java or Android.
     *
     * <p>You should only change the generateProto tasks in this closure. Do not
     * change the task in your own afterEvaluate closure, as the change may not
     * be picked up correctly by the wired javaCompile task.
     */
    fun generateProtoTasks(action: GenerateProtoTaskCollection.()->Unit) {
        taskConfigClosures.add(closureOf(action))
    }

    /**
     * Returns the collection of generateProto tasks. Note the tasks are
     * available only after project evaluation.
     *
     * <p>Do not try to change the tasks other than in the closure provided
     * to {@link #generateProtoTasks(Closure)}. The reason is explained
     * in the comments for the linked method.
     */
    fun getGenerateProtoTasks(): GenerateProtoTaskCollection {
        return tasks
    }


    open inner class GenerateProtoTaskCollection {
        fun all(): TaskCollection<GenerateProtoTask> {
            return project.tasks.withType(GenerateProtoTask::class.java)
        }
    }

    inner class AndroidGenerateProtoTaskCollection : GenerateProtoTaskCollection() {
        fun ofFlavor(flavor: String): TaskCollection<GenerateProtoTask> {
            return all().matching { task: GenerateProtoTask ->
                task.getFlavors().contains(flavor)
            }
        }

        fun ofBuildType(buildType: String): TaskCollection<GenerateProtoTask> {
            return all().matching { task: GenerateProtoTask ->
                task.getBuildType() == buildType
            }
        }

        fun ofVariant(variant: String): TaskCollection<GenerateProtoTask> {
            return all().matching { task: GenerateProtoTask ->
                task.variantName == variant
            }
        }

        fun ofNonTest(): TaskCollection<GenerateProtoTask> {
            return all().matching { task: GenerateProtoTask ->
                !task.isTestVariant
            }
        }

        fun ofTest(): TaskCollection<GenerateProtoTask> {
            return all().matching { task: GenerateProtoTask ->
                task.isTestVariant
            }
        }
    }

    inner class JavaGenerateProtoTaskCollection : GenerateProtoTaskCollection() {
        fun ofSourceSet(sourceSet: String): TaskCollection<GenerateProtoTask> {
            return all().matching { task: GenerateProtoTask ->
                task.getSourceSet().name == sourceSet
            }
        }
    }
}