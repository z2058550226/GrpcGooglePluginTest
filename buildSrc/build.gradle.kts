import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins{
    `kotlin-dsl`
    kotlin("jvm") version "1.4.10"
}

repositories {
    jcenter()
    google()
}

dependencies{
    implementation(gradleApi())
    implementation("com.android.tools.build:gradle:4.0.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.10")
    implementation(kotlin("stdlib-jdk8"))

    implementation("com.google.gradle:osdetector-gradle-plugin:1.6.2")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}