plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.embedded.terminal"
version = "1.36.0"


repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        pycharm("2026.2")
    }
    implementation("com.ibm.icu:icu4j:74.2")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = "com.embedded.terminal"
        name = "Embedded Terminal Emulator"

        ideaVersion {
            sinceBuild = "241"
            untilBuild = "262.*"
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}