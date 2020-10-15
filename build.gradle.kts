buildscript {
    repositories {
        maven("https://nexus.bybutter.com/repository/maven-public/") {
            name = "maven-releases"
            credentials {
                username = System.getenv("NEXUS_USERNAME")
                password = System.getenv("NEXUS_PASSWORD")
            }
        }
        google()
        jcenter()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.10")
        classpath("com.android.tools.build:gradle:4.0.2")
    }
}

allprojects {
    repositories {
        maven("https://nexus.bybutter.com/repository/maven-public/") {
            name = "maven-releases"
            credentials {
                username = System.getenv("NEXUS_USERNAME")
                password = System.getenv("NEXUS_PASSWORD")
            }
        }
        google()
        jcenter()
    }
}

// Used for debug plugin
task("config") {
    doLast {
        println("config")
    }
}