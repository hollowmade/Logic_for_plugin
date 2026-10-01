plugins {
    java
    id("io.github.goooler.shadow") version "8.1.8"
}

group = "ru.logic"
version = "1.0.0-SNAPSHOT"
description = "LogicTierPlugin"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    // paper-api is compileOnly, so it is absent here: tests fail if core touches Bukkit
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    test {
        useJUnitPlatform()
    }
    shadowJar {
        archiveClassifier.set("")
        relocate("com.zaxxer.hikari", "ru.logic.libs.hikari")
        relocate("org.sqlite", "ru.logic.libs.sqlite")
    }
    build {
        dependsOn(shadowJar)
    }
    processResources {
        filesMatching("plugin.yml") {
            expand(project.properties)
        }
    }
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
