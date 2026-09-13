pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.PhilJay") }
        }
    }
}

rootProject.name = "TalkToAI"
include(":androidApp")
include(":shared")
// Consume the committed SDK submodule by default; local development is opt-in.
val marketUiDir = providers.gradleProperty("marketUiDir").orNull?.let { file(it) }
    ?: file("market-ui")
check(marketUiDir.resolve("settings.gradle.kts").exists()) {
    "Missing Kuikly SDK. Run: git submodule update --init --recursive"
}
includeBuild(marketUiDir) {
    dependencySubstitution {
        substitute(module("io.github.rogerin1900:market-ui")).using(project(":"))
    }
}
