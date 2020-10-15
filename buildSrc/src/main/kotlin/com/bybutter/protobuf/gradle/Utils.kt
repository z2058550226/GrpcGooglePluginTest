package com.bybutter.protobuf.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.util.GUtil
import java.util.regex.Matcher

object Utils {
    fun getConfigName(sourceSetName: String, type: String): String {
        // same as DefaultSourceSet.configurationNameOf
        val baseName = if (sourceSetName == SourceSet.MAIN_SOURCE_SET_NAME) ""
        else GUtil.toCamelCase(sourceSetName)
        return uncapitalize(baseName + capitalize(type))!!
    }

    fun getSourceSetSubstringForTaskNames(sourceSetName: String): String {
        return if (sourceSetName == SourceSet.MAIN_SOURCE_SET_NAME) ""
        else GUtil.toCamelCase(sourceSetName)
    }

    /**
     * Returns true if the source set is a test related source set.
     */
    fun isTest(sourceSetOrVariantName: String): Boolean {
        return sourceSetOrVariantName == "test" ||
                sourceSetOrVariantName.toLowerCase().contains("androidtest") ||
                sourceSetOrVariantName.toLowerCase().contains("unittest")
    }


    /**
     * Returns the compile task name for Kotlin.
     */
    fun getKotlinAndroidCompileTaskName(variantName: String): String {
        // The kotlin plugin does not provide a utility for this.
        // Fortunately, the naming scheme is well defined:
        // https://kotlinlang.org/docs/reference/using-gradle.html#compiler-options
        return "compile" + GUtil.toCamelCase(variantName) + "Kotlin"
    }

    fun compareGradleVersion(project: Project, target: String): Int {
        val gv: Matcher = parseVersionString(project.gradle.gradleVersion)
        val tv: Matcher = parseVersionString(target)
        val majorVersionDiff: Int = gv.group(1).toInt() - tv.group(1).toInt()
        if (majorVersionDiff != 0) {
            return majorVersionDiff
        }
        return gv.group(2).toInt() - tv.group(2).toInt()
    }

    private fun parseVersionString(version: String): Matcher {
        val regex = """(\d*)\.(\d*).*""".toRegex()

        val matcher: Matcher = regex.toPattern().matcher(version)
        if (matcher.find(0).not() || matcher.matches().not()) {
            throw  GradleException("Failed to parse version \"${version}\"")
        }
        return matcher
    }

    private fun capitalize(str: String?): String? {
        var strLen = 0
        return if (str == null || str.length.also { strLen = it } == 0) {
            str
        } else StringBuilder(strLen)
            .append(Character.toTitleCase(str[0]))
            .append(str.substring(1))
            .toString()
    }

    private fun uncapitalize(str: String?): String? {
        var strLen = 0
        return if (str == null || str.length.also { strLen = it } == 0) {
            str
        } else StringBuilder(strLen)
            .append(Character.toLowerCase(str[0]))
            .append(str.substring(1))
            .toString()
    }
}