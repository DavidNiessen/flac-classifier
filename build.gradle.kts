import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.gradleup.shadow") version "8.3.6"
    `maven-publish`
}

group = "dev.niessen"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jflac:jflac-codec:1.5.2")
    implementation("com.github.wendykierp:JTransforms:3.1")
    implementation("info.picocli:picocli:4.7.6")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.niessen.flacclassifier.MainKt")
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("flac-classifier")
    archiveClassifier.set("all")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "OWNER/flac-classifier"}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("flac-classifier")
                description.set("Analyzes FLAC files to detect fake lossless audio (MP3/AAC/OGG transcodes, upsampling)")
            }
        }
    }
}
