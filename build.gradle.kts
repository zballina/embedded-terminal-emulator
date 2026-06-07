plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.0.0"
}

group = "com.embedded.terminal"
version = "1.24.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        pycharmCommunity("2024.1")
        instrumentationTools()
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