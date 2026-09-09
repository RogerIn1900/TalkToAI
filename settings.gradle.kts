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
// Public SDK is pinned by the Git submodule commit; never resolve a floating branch.
check(file("market-ui/settings.gradle.kts").exists()) {
    "Missing Kuikly SDK. Run: git submodule update --init --recursive"
}
includeBuild("market-ui") {
    dependencySubstitution {
        substitute(module("io.github.rogerin1900:market-ui")).using(project(":"))
    }
}
