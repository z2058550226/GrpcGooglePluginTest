package com.bybutter.protobuf.gradle

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.BaseVariant
import org.codehaus.groovy.runtime.InvokerHelper
import org.gradle.api.Project
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.*

// Project

val Project.android: BaseExtension get() = extensions.getByName("android") as BaseExtension

// groovy

fun BaseVariant.hasMetaProperty(name: String): Boolean {
    return InvokerHelper.getMetaClass(this).hasProperty(this, name) != null
}

// String

fun CharSequence.tokenize(delimiters: CharSequence): List<String?> {
    return StringTokenizer(this.toString(), delimiters.toString()).toList().map { it?.toString() }
}

// Process

@Throws(IOException::class)
fun List<String>.execute(): Process {
    val cmdArr = this.toTypedArray()
    return Runtime.getRuntime().exec(cmdArr)
}

fun Process.waitForProcessOutput(output: Appendable, error: Appendable) {
    val tout: Thread = consumeProcessOutputStream(output)
    val terr: Thread = consumeProcessErrorStream(error)

    try {
        tout.join()
    } catch (e: InterruptedException) {
    }

    try {
        terr.join()
    } catch (var7: InterruptedException) {
    }

    try {
        this.waitFor()
    } catch (var6: InterruptedException) {
    }

    closeStreams()
}

fun Process.closeStreams() {
    try {
        errorStream.close()
    } catch (e: IOException) {
    }

    try {
        inputStream.close()
    } catch (e: IOException) {
    }

    try {
        outputStream.close()
    } catch (e: IOException) {
    }
}

fun Process.consumeProcessOutputStream(output: Appendable): Thread {
    val thread = Thread(TextDumper(inputStream, output))
    thread.start()
    return thread
}

fun Process.consumeProcessErrorStream(error: Appendable): Thread {
    val thread = Thread(TextDumper(errorStream, error))
    thread.start()
    return thread
}

private class TextDumper(
    private val inputStream: InputStream,
    private val appendable: Appendable
) : Runnable {
    override fun run() {
        val br = BufferedReader(InputStreamReader(inputStream))
        try {
            var next: String? = br.readLine()
            while (next != null) {
                appendable.append(next)
                appendable.append("\n")
                next = br.readLine()
            }
        } catch (e: IOException) {
            throw RuntimeException("exception while reading process stream", e)
        }
    }
}

