plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.tinyvault"
version = "1.1.0"

java {
    toolchain {
        // Paper 26.2 requires Java 25; Gradle provisions it automatically.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    implementation("software.amazon.awssdk:s3:2.55.16")
}

tasks {
    jar {
        archiveClassifier = "plain"
    }
    processResources {
        inputs.property("version", project.version.toString())
        filesMatching("plugin.yml") {
            expand(mapOf("version" to project.version.toString()))
        }
    }
    shadowJar {
        archiveClassifier = ""
        relocate("software.amazon.awssdk", "dev.tinyvault.shaded.awssdk")
    }
    build {
        dependsOn(shadowJar)
    }
}
