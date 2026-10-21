plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform")
}

group = "com.eblan.plugin"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        local(file(".intellijPlatform/sandbox/idea-IU-262.10968.63").absolutePath)
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            recommended()
        }
    }

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            Added AI parameter grouping through configurable OpenAI-compatible chat APIs.
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
