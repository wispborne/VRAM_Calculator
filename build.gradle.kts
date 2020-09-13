import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.10"
    application
}
group = "com.wisp"
val kotlinVersion = "1.4.10"


val vramCounterVersion = "1.2.0"
val jarFileName = "VRAM-Counter.jar"

repositories {
    mavenCentral()
}

tasks {
// Compile to Java 6 bytecode so that Starsector can use it
    withType<KotlinCompile>() {
        kotlinOptions.jvmTarget = "1.6"
    }

    named<Jar>("jar")
    {
        destinationDirectory.set(file("$rootDir/VRAM-Counter-$vramCounterVersion"))
        archiveFileName.set(jarFileName)

        // Set the class to run
        manifest.attributes["Main-Class"] = "MainKt"

        // Add JAR dependencies to the JAR
        configurations.runtimeClasspath.get()
            .filter { it.name in "kotlin-stdlib-$kotlinVersion.jar" }
            .map { zipTree(it) }
            .also { from(it) }

        doLast {
            File(destinationDirectory.get().asFile, "VRAM-Counter.bat")
                .writeText("start \"\" \"../../jre/bin/java.exe\" -jar ./$jarFileName")

            File(destinationDirectory.get().asFile, "VRAM-Counter.sh")
                .writeText(
                    StringBuilder()
                        .appendln("#!/bin/sh")
                        .appendln("../../jre/bin/java.exe -jar ./$jarFileName")
                        .toString()
                )

            File(destinationDirectory.get().asFile, "readme.txt")
                .writeText(
                    StringBuilder()
                        .appendln("VRAM-Counter $vramCounterVersion")
                        .appendln("------------")
                        .appendln("Place in Starsector's /mods folder, then launch.")
                        .appendln("Windows: Open .bat file")
                        .appendln("Not-Windows: Use .sh file")
                        .appendln()
                        .appendln("Changelog")
                        .appendln("1.2.0")
                        .appendln("Image channels are now accurately detected for all known cases, improving accuracy (now on par with original Python script).")
                        .appendln("Files with '_CURRENTLY_UNUSED' in the name are ignored.")
                        .appendln("Converted to Kotlin, added .bat and .sh launchers.")
                        .appendln()
                        .appendln("1.1.0")
                        .appendln("Backgrounds are now only counted if larger than vanilla size and only by their increase over vanilla.")
                        .appendln()
                        .appendln("1.0.0")
                        .appendln("Original release of Wisp's version.")
                        .appendln()
                        .appendln("Original script by Dark Revenant. Transcoded to Kotlin and edited to show more info by Wisp")
                        .toString()
                )

            File(destinationDirectory.get().asFile, "LICENSE.txt")
                .writeText(project.file("LICENSE.txt").readText())
        }
    }
}

application {
    mainClassName = "MainKt"
}

dependencies {
    implementation(kotlin("stdlib-jdk7"))
}