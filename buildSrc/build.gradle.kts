import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins{
    `kotlin-dsl`
}

repositories {
    jcenter()
    google()
}

dependencies{
    implementation(gradleApi())
    implementation("com.android.tools.build:gradle:4.0.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.10")
    compileOnly(kotlin("stdlib-jdk8"))
    compileOnly(gradleKotlinDsl())
    compileOnly(kotlin("reflect"))
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