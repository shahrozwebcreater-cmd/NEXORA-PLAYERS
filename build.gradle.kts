plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.nexora"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    implementation("com.google.code.gson:gson:2.13.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    shadowJar {
        archiveClassifier.set("")
        relocate("com.google.gson", "com.nexora.players.lib.gson")
    }
    build {
        dependsOn(shadowJar)
    }
}
