import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "2.1.10"
    kotlin("jvm") version kotlinVersion
    kotlin("kapt") version kotlinVersion
    kotlin("plugin.serialization") version "1.9.22"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "org.openredstone.trialore"
version = "1.1"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.aikar.co/content/groups/aikar/")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(group = "org.danilopianini", name = "khttp", version = "1.6.3")
    implementation(group = "net.luckperms", name = "api", version = "5.1")
    implementation(group = "org.jetbrains.exposed", name = "exposed-core", version = "0.51.1")
    implementation(group = "org.jetbrains.exposed", name = "exposed-jdbc", version = "0.51.1")
    implementation(group = "org.jetbrains.exposed", name = "exposed-java-time", version = "0.51.1")
    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.46.0.0")
    implementation(group = "co.aikar", name = "acf-paper", version = "0.5.1-SNAPSHOT")
    implementation(group = "com.fasterxml.jackson.dataformat", name = "jackson-dataformat-yaml", version = "2.15.0")
    compileOnly(group = "io.papermc.paper", name = "paper-api", version = "1.20.4-R0.1-SNAPSHOT")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.shadowJar {
    relocate("co.aikar.commands", "trialore.acf")
    relocate("co.aikar.locales", "trialore.locales")

    relocate("com.fasterxml.jackson", "org.openredstone.lib.jackson")
    dependencies {
        exclude(dependency("net.luckperms:api:.*"))
        exclude(dependency("io.papermc.paper:paper-api:.*"))
    }
    archiveClassifier.set("")
}


tasks.build {
    dependsOn(tasks.shadowJar)
}
