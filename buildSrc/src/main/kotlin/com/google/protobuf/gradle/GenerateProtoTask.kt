package com.google.protobuf.gradle

import com.android.build.gradle.api.BaseVariant
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.logging.LogLevel
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.*
import javax.inject.Inject
import kotlin.collections.ArrayList

abstract class GenerateProtoTask : DefaultTask() {
    companion object {
        // Windows CreateProcess has command line limit of 32768:
        // https://msdn.microsoft.com/en-us/library/windows/desktop/ms682425(v=vs.85).aspx
        const val WINDOWS_CMD_LENGTH_LIMIT = 32760

        // Extra command line length when added an additional argument on Windows.
        // Two quotes and a space.
        const val CMD_ARGUMENT_EXTRA_LENGTH = 3
        private const val JAR_SUFFIX = ".jar"

        // protoc allows you to prefix comma-delimited options to the path in
        // the --*_out flags, e.g.,
        // - Without options: --java_out=/path/to/output
        // - With options: --java_out=option1,option2:/path/to/output
        // This method generates the prefix out of the given options.
        fun makeOptionsPrefix(options: List<String>): String {
            val prefix = StringBuilder()
            if (options.isNotEmpty()) {
                options.forEach { option ->
                    if (prefix.isNotEmpty()) {
                        prefix.append(',')
                    }
                    prefix.append(option)
                }
                prefix.append(':')
            }
            return prefix.toString()
        }

        fun generateCmds(
            baseCmd: List<String>,
            protoFiles: List<File>,
            cmdLengthLimit: Int
        ): List<List<String>> {
            val cmds: ArrayList<List<String>> = ArrayList()
            if (protoFiles.isNotEmpty()) {
                val baseCmdLength: Int = baseCmd.sumBy { it.length + CMD_ARGUMENT_EXTRA_LENGTH }
                val currentArgs: ArrayList<String> = ArrayList()
                var currentArgsLength = 0
                for (proto: File in protoFiles) {
                    val protoFileName: String = proto.name
                    val currentFileLength = protoFileName.length + CMD_ARGUMENT_EXTRA_LENGTH
                    // Check if appending the next proto string will overflow the cmd length limit
                    if (baseCmdLength + currentArgsLength + currentFileLength > cmdLengthLimit) {
                        // Add the current cmd before overflow
                        cmds.add(baseCmd + currentArgs)
                        currentArgs.clear()
                        currentArgsLength = 0
                    }
                    // Append the proto file to the args
                    currentArgs.add(protoFileName)
                    currentArgsLength += currentFileLength
                }
                // Add the last cmd for execution
                cmds.add(baseCmd + currentArgs)
            }
            return cmds
        }

        fun getCmdLengthLimit(): Int {
            return getCmdLengthLimit(System.getProperty("os.name"))
        }

        fun getCmdLengthLimit(os: String): Int =
            if (isWindows(os)) WINDOWS_CMD_LENGTH_LIMIT else Integer.MAX_VALUE

        fun isWindows(os: String?): Boolean =
            os != null && os.toLowerCase(Locale.ROOT).indexOf("win") > -1

        fun isWindows(): Boolean = isWindows(System.getProperty("os.name"))

        fun escapePathUnix(path: String): String = path.replace("'", "'\\''")

        fun escapePathWindows(path: String): String {
            val escapedPath: String = path.replace("%", "%%")
            return if (escapedPath.endsWith("\\")) escapedPath + "\\" else escapedPath
        }

        @Throws(IOException::class)
        fun mkdirsForFile(outputFile: File) {
            if (!outputFile.parentFile.isDirectory && !outputFile.parentFile.mkdirs()) {
                throw  IOException("unable to make directories for file: " + outputFile.canonicalPath)
            }
        }

        @Throws(IOException::class)
        fun setExecutableOrFail(outputFile: File) {
            if (!outputFile.setExecutable(true)) {
                outputFile.delete()
                throw  IOException("unable to set file as executable: " + outputFile.canonicalPath)
            }
        }

        fun computeJavaExePath(isWindows: Boolean): String {
            val java =
                File(System.getProperty("java.home"), if (isWindows) "bin/java.exe" else "bin/java")
            if (!java.exists()) {
                throw  IOException("Could not find java executable at " + java.path)
            }
            return java.path
        }
    }


    // include dirs are passed to the '-I' option of protoc.  They contain protos
    // that may be "imported" from the source protos, but will not be compiled.
    private val includeDirs: ConfigurableFileCollection = getObjectFactory().fileCollection()

    // source files are proto files that will be compiled by protoc
    private val sourceFiles: ConfigurableFileCollection = getObjectFactory().fileCollection()

    private val builtins: NamedDomainObjectContainer<PluginOptions> =
        getObjectFactory().domainObjectContainer(PluginOptions::class.java)
    private val plugins: NamedDomainObjectContainer<PluginOptions> =
        getObjectFactory().domainObjectContainer(PluginOptions::class.java)

    // These fields are set by the Protobuf plugin only when initializing the
    // task.  Ideally they should be final fields, but Gradle task cannot have
    // constructor arguments. We use the initializing flag to prevent users from
    // accidentally modifying them.
    private var outputBaseDir: String? = null

    // Tags for selectors inside protobuf.generateProtoTasks; do not serialize with Gradle configuration caching
    @SuppressWarnings("UnnecessaryTransientModifier") // It is not necessary for task to implement Serializable
    @Transient
    private var sourceSet: SourceSet? = null

    @SuppressWarnings("UnnecessaryTransientModifier") // It is not necessary for task to implement Serializable
    @Transient
    private var variant: BaseVariant? = null
    private var flavors: List<String>? = null
    private var buildType: String? = null


    @get:Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
    var isTestVariant = false
        get() {
            check(variant != null) { "variant is not set" }
            return field
        }
    private var fileResolver: FileResolver? = null

    @get:Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
    val variantName: String by lazy { variant?.name.orEmpty() }

    private val isAndroidProject = true

    private val isTestProvider: Boolean by lazy { getIsTestVariant() }

    /**
     * If true, will set the protoc flag
     * --descriptor_set_out="${outputBaseDir}/descriptor_set.desc"
     *
     * Default: false
     */
    @Internal("Handled as input via getDescriptorSetOptionsForCaching()")
    val generateDescriptorSet = false

    /**
     * Configuration object for descriptor generation details.
     */
    class DescriptorSetOptions {
        /**
         * If set, specifies an alternative location than the default for storing the descriptor
         * set.
         */
        @OutputFile
        var path: String? = null

        /**
         * If true, source information (comments, locations) will be included in the descriptor set.
         */
        @Input
        var includeSourceInfo = false

        /**
         * If true, imports are included in the descriptor set, such that it is self-containing.
         */
        @Input
        var includeImports = false
    }

    @Internal("Handled as input via getDescriptorSetOptionsForCaching()")
    val descriptorSetOptions: DescriptorSetOptions = DescriptorSetOptions()


    fun setOutputBaseDir(outputBaseDir: String) {
        checkInitializing()
        check(this.outputBaseDir == null) { "outputBaseDir is already set" }
        this.outputBaseDir = outputBaseDir
    }

    @OutputDirectory
    fun getOutputBaseDir(): String? {
        return outputBaseDir
    }

    fun setSourceSet(sourceSet: SourceSet) {
        checkInitializing()
        this.sourceSet = sourceSet
    }

    fun setVariant(variant: BaseVariant, isTestVariant: Boolean) {
        checkInitializing()
        this.variant = variant
        this.isTestVariant = isTestVariant
    }

    fun setFlavors(flavors: List<String>) {
        checkInitializing()
        this.flavors = flavors
    }

    fun setBuildType(buildType: String) {
        checkInitializing()
        this.buildType = buildType
    }

    fun setFileResolver(fileResolver: FileResolver?) {
        checkInitializing()
        this.fileResolver = fileResolver
    }

    @Internal("Inputs tracked in getSourceFiles()")
    fun getSourceSet(): SourceSet {
        return sourceSet ?: throw NullPointerException("sourceSet is not set")
    }

    @SkipWhenEmpty
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getSourceFiles(): FileCollection? {
        return sourceFiles
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getIncludeDirs(): FileCollection? {
        return includeDirs
    }

    @Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
    fun getVariant(): Any {
        return variant ?: throw NullPointerException("variant is not set")
    }

    @Internal("Input captured by getAlternativePaths()")
    abstract fun getProtocLocator(): Property<ExecutableLocator>

    @Internal("Input captured by getAlternativePaths(), this is used to query alternative path by locator name.")
    abstract fun getLocatorToAlternativePathsMapping(): MapProperty<String, FileCollection>

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    fun getAlternativePaths(): ConfigurableFileCollection {
        return getObjectFactory().fileCollection()
            .from(getLocatorToAlternativePathsMapping().get().values)
    }

    @Internal("Input captured by getAlternativePaths()")
    abstract fun getPluginsExecutableLocators(): MapProperty<String, ExecutableLocator>

    @Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
    fun getIsTestVariant(): Boolean {
        variant ?: throw  NullPointerException("variant is not set")
        return isTestVariant
    }

    @Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
    fun getFlavors(): List<String> {
        return flavors ?: throw NullPointerException("flavors is not set")
    }

    @Internal("Not an actual input to the task, only used to find tasks belonging to a variant")
    fun getBuildType(): String? {
        check(variantName == "test" || buildType != null) { "buildType is not set and task is not for local unit test variant" }
        return buildType
    }

    fun doneInitializing() {
        check(state == State.INIT) { "Invalid state: $state" }
        state = State.CONFIG
    }

    fun doneConfig() {
        check(state == State.CONFIG) { "Invalid state: $state" }
        state = State.FINALIZED
    }

    @Internal("Tracked as an input via getDescriptorSetOptionsForCaching()")
    fun getDescriptorPath(): String {
        check(generateDescriptorSet) { "requested descriptor path but descriptor generation is off" }
        return if (descriptorSetOptions.path != null) descriptorSetOptions.path!! else "${outputBaseDir}/descriptor_set.desc"
    }

    @Inject
    abstract fun getObjectFactory(): ObjectFactory

    //===========================================================================
    //        Configuration methods
    //===========================================================================

    /**
     * Configures the protoc builtins in a closure, which will be manipulating a
     * NamedDomainObjectContainer<PluginOptions>.
     */
//    fun builtins(configureClosure: Closure) {
//        checkCanConfig()
//        ConfigureUtil.configure(configureClosure, builtins)
//    }

    /**
     * Returns the container of protoc builtins.
     */
    @Internal("Tracked as an input via getBuiltinsForCaching()")
    fun getBuiltins(): NamedDomainObjectContainer<PluginOptions> {
        checkCanConfig()
        return builtins
    }

    /**
     * Configures the protoc plugins in a closure, which will be maniuplating a
     * NamedDomainObjectContainer<PluginOptions>.
     */
//    fun plugins(Closure configureClosure)
//    {
//        checkCanConfig()
//        ConfigureUtil.configure(configureClosure, plugins)
//    }

    /**
     * Returns the container of protoc plugins.
     */
    @Internal("Tracked as an input via getPluginsForCaching()")
    fun getPlugins(): NamedDomainObjectContainer<PluginOptions> {
        checkCanConfig()
        return plugins
    }

    /**
     * Returns true if the task has a plugin with the given name, false otherwise.
     */
    fun hasPlugin(name: String): Boolean {
        return plugins.findByName(name) != null
    }

    /**
     * Add a directory to protoc's include path.
     */
    fun addIncludeDir(dir: FileCollection) {
        checkCanConfig()
        includeDirs.from(dir)
    }

    /**
     * Add a collection of proto source files to be compiled.
     */
    fun addSourceFiles(files: FileCollection) {
        checkCanConfig()
        sourceFiles.from(files)
    }

    /**
     * Returns true if the Java source set or Android variant is test related.
     */
    @Input
    fun getIsTest(): Boolean {
        return isTestProvider
    }

    @Internal("Already captured with getIsTest()")
    fun getIsTestProvider(): Boolean {
        return isTestProvider
    }

    /**
     * The container of command-line options for a protoc plugin or a built-in output.
     */
    class PluginOptions(private val name: String) : Named {
        @get:Input
        val options: MutableList<String> = mutableListOf()

        @get:Input
        var outputSubDir: String = name

        fun option(option: String): PluginOptions {
            options.add(option)
            return this
        }

        @Input
        override fun getName(): String = name
    }

    //===========================================================================
    //    protoc invocation logic
    //===========================================================================

    fun getOutputDir(plugin: PluginOptions): String {
        return "${outputBaseDir}/${plugin.outputSubDir}"
    }

    /**
     * Returns a {@code SourceDirectorySet} representing the generated source
     * directories.
     */
    @Internal
    fun getOutputSourceDirectorySet(): SourceDirectorySet {
        val srcSetName: String = "generate-proto-$name"
        val srcSet: SourceDirectorySet =
            getObjectFactory().sourceDirectorySet(srcSetName, srcSetName)
        builtins.forEach { builtin ->
            srcSet.srcDir(File(getOutputDir(builtin)))
        }
        plugins.forEach { plugin ->
            srcSet.srcDir(File(getOutputDir(plugin)))
        }
        return srcSet
    }

    @TaskAction
    fun compile() {
        check(state == State.FINALIZED) { "doneConfig() has not been called" }

        // Sort to ensure generated descriptors have a canonical representation
        // to avoid triggering unnecessary rebuilds downstream
        val protoFiles: List<File> = sourceFiles.files.sorted()

        (builtins + plugins).forEach { plugin ->
            val outputDir = File(getOutputDir(plugin))
            outputDir.mkdirs()
        }

        // The source directory designated from sourceSet may not actually exist on disk.
        // "include" it only when it exists, so that Gradle and protoc won't complain.
        val dirs: List<String> = includeDirs.filter { it.exists() }.map { "-I${it.path}" }
//        val dirs: List<String> = includeDirs.filter { it.exists() }*.path.collect { "-I${it}" }
        logger.debug("ProtobufCompile using directories $dirs")
        logger.debug("ProtobufCompile using files $protoFiles")

        val protocPath: String = computeExecutablePath(getProtocLocator().get())
        val baseCmd: MutableList<String> = mutableListOf(protocPath)
        baseCmd.addAll(dirs)

        // Handle code generation built-ins
        builtins.forEach { builtin ->
            val outPrefix: String = makeOptionsPrefix(builtin.options)
            baseCmd += "--${builtin.name}_out=${outPrefix}${getOutputDir(builtin)}"
        }

        val executableLocations: Map<String, ExecutableLocator> =
            getPluginsExecutableLocators().get()
        // Handle code generation plugins
        plugins.forEach { plugin ->
            val name: String = plugin.name
            val locator: ExecutableLocator? = executableLocations[name]
            if (locator != null) {
                baseCmd += "--plugin=protoc-gen-${name}=${computeExecutablePath(locator)}"
            } else {
                logger.warn("protoc plugin '${name}' not defined. Trying to use 'protoc-gen-${name}' from system path")
            }
            val pluginOutPrefix: String = makeOptionsPrefix(plugin.options)
            baseCmd += "--${name}_out=${pluginOutPrefix}${getOutputDir(plugin)}"
        }

        if (generateDescriptorSet) {
            val path: String = getDescriptorPath()
            // Ensure that the folder for the descriptor exists;
            // the user may have set it to point outside an existing tree
            val folder: File = File(path).parentFile
            if (!folder.exists()) {
                folder.mkdirs()
            }
            baseCmd += "--descriptor_set_out=${path}"
            if (descriptorSetOptions.includeImports) {
                baseCmd += "--include_imports"
            }
            if (descriptorSetOptions.includeSourceInfo) {
                baseCmd += "--include_source_info"
            }
        }

        val cmds: List<List<String>> = generateCmds(baseCmd, protoFiles, getCmdLengthLimit())
        for (cmd in cmds) {
            compileFiles(cmd)
        }
    }

    /**
     * Used to expose inputs to Gradle, not to be called directly.
     */
    @Nested
    protected fun getDescriptorSetOptionsForCaching(): DescriptorSetOptions? {
        return if (generateDescriptorSet) descriptorSetOptions else null
    }

    /**
     * Used to expose inputs to Gradle, not to be called directly.
     */
    @Nested
    protected fun getBuiltinsForCaching(): Collection<PluginOptions> {
        return builtins
    }

    /**
     * Used to expose inputs to Gradle, not to be called directly.
     */
    @Nested
    protected fun getPluginsForCaching(): Collection<PluginOptions> {
        return plugins
    }

    private enum class State {
        INIT, CONFIG, FINALIZED
    }

    private var state = State.INIT

    private fun checkInitializing() {
        check(state == State.INIT) { "Should not be called after initilization has finished" }
    }

    private fun checkCanConfig() {
        check(state == State.CONFIG || state == State.INIT) { "Should not be called after configuration has finished" }
    }

    private fun compileFiles(cmd: List<String>) {
        logger.log(LogLevel.INFO, cmd.toString())

        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val result: Process = cmd.execute()
        result.waitForProcessOutput(stdout, stderr)
        val output = "protoc: stdout: ${stdout}. stderr: $stderr"
        if (result.exitValue() == 0) {
            logger.log(LogLevel.INFO, output)
        } else {
            throw  GradleException(output)
        }
    }

    protected fun computeExecutablePath(locator: ExecutableLocator): String {
        locator.path?.let { path ->
            return if (path.endsWith(JAR_SUFFIX)) createJarTrampolineScript(path) else path
        }
        val file: File =
            getLocatorToAlternativePathsMapping().getting(locator.name).get().singleFile
        if (file.name.endsWith(JAR_SUFFIX)) {
            return createJarTrampolineScript(file.absolutePath)
        }

        if (!file.canExecute() && !file.setExecutable(true)) {
            throw GradleException("Cannot set $file as executable")
        }
        logger.info("Resolved artifact: $file")
        return file.path
    }

    /**
     * protoc only supports plugins that are a single self contained executable file. For .jar files create a trampoline
     * script to execute the jar file. Assume the jar is a "fat jar" or "uber jar" and don't attempt any artifact
     * resolution.
     * @param jarAbsolutePath Absolute path to the .jar file.
     * @return The absolute path to the trampoline executable script.
     */
    private fun createJarTrampolineScript(jarAbsolutePath: String): String {
        check(jarAbsolutePath.endsWith(JAR_SUFFIX))
        val isWindows: Boolean = isWindows()
        val jarFileName: String = File(jarAbsolutePath).name
        if (jarFileName.length <= JAR_SUFFIX.length) {
            throw GradleException(".jar protoc plugin path '${jarAbsolutePath}' has no file name")
        }
        val scriptExecutableFile: File = File(
            "${project.buildDir}/scripts/" + jarFileName.substring(0 until jarFileName.length - JAR_SUFFIX.length) + "-${name}-trampoline." +
                    (if (isWindows) "bat" else "sh")
        )
        try {
            mkdirsForFile(scriptExecutableFile)
            val javaExe: String = computeJavaExePath(isWindows)
            // Rewrite the trampoline file unconditionally (even if it already exists) in case the dependency or versioning
            // changes we don't need to detect the delta (and the file content is cheap to re-generate).
            val trampoline: String = if (isWindows)
                "@ECHO OFF\r\n\"${escapePathWindows(javaExe)}\" -jar \"${
                    escapePathWindows(jarAbsolutePath)
                }\" %*\r\n"
            else "#!/bin/sh\nexec '${escapePathUnix(javaExe)}' -jar '${
                escapePathUnix(jarAbsolutePath)
            }' \"\$@\"\n"
            scriptExecutableFile.writeText(trampoline, US_ASCII)
            setExecutableOrFail(scriptExecutableFile)
            logger.info("Resolved artifact jar: ${jarAbsolutePath}. Created trampoline file: $scriptExecutableFile")
            return scriptExecutableFile.path
        } catch (e: IOException) {
            throw GradleException("Unable to generate trampoline for .jar protoc plugin", e)
        }
    }
}