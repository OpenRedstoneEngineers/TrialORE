import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kotlinVersion = "2.4.0"
    kotlin("jvm") version kotlinVersion
    kotlin("kapt") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    id("com.gradleup.shadow") version "9.4.3"
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
    implementation("org.danilopianini:khttp:1.6.3")
    implementation("net.luckperms:api:5.1")
    implementation("org.jetbrains.exposed:exposed-core:0.51.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.51.1")
    implementation("org.jetbrains.exposed:exposed-java-time:0.51.1")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
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
    val lib = "$group.lib"
    relocate("co.aikar.commands", "$lib.acf")
    relocate("co.aikar.locales", "$lib.acflocales")
    relocate("com.fasterxml.jackson", "$lib.jackson")
    dependencies {
        exclude(dependency("net.luckperms:api:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<Test> {
    failOnNoDiscoveredTests = false
}
