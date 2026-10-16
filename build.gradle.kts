plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.tinyvault"
version = "1.0.4"

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
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.yaml:snakeyaml:2.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
        mergeServiceFiles()
        relocate("com.google.gson", "dev.tinyvault.shaded.gson")
    }
    test {
        useJUnitPlatform()
    }
    build {
        dependsOn(shadowJar)
    }
}
