import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.10"
    application
}
group = "com.wisp"
val kotlinVersion = "1.4.10"


val vramCounterVersion = "1.2.0"
val toolname = "VRAM-Counter"
val jarFileName = "$toolname.jar"
val relativeJavaExePath = "../../jre/bin/java.exe"

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
        destinationDirectory.set(file("$rootDir/$toolname-$vramCounterVersion"))
        archiveFileName.set(jarFileName)

        // Set the class to run
        manifest.attributes["Main-Class"] = "MainKt"

        // Add JAR dependencies to the JAR
        configurations.runtimeClasspath.get()
            .filter { !it.isDirectory }
//            .filter { it.name in "kotlin-stdlib-$kotlinVersion.jar" }
            .map { zipTree(it) }
            .also { from(it) }

        doLast {
            File(destinationDirectory.get().asFile, "$toolname.bat")
                .writeText(
                    StringBuilder()
                        .appendln(
                            """
@echo off
IF NOT EXIST $relativeJavaExePath (
    ECHO Place $toolname folder in Starsector's mods folder.
    pause
) ELSE (
 "$relativeJavaExePath" -jar ./$jarFileName
)
"""
                        )
                        .toString()
                )

//            File(destinationDirectory.get().asFile, "$toolname.sh")
//                .writeText(
//                    StringBuilder()
//                        .appendln("#!/bin/sh")
//                        .appendln("$relativeJavaExePath -jar ./$jarFileName")
//                        .toString()
//                )

            File(destinationDirectory.get().asFile, "readme.txt")
                .writeText(
                    """
$toolname $vramCounterVersion
------------
Place folder in Starsector's /mods folder, then launch.
Windows: Open .bat file
Not-Windows: Use .sh file

Changelog
1.2.0
Image channels are now accurately detected for all known cases, improving accuracy (now on par with original Python script).
Files with '_CURRENTLY_UNUSED' in the name are ignored.
Added configuration file for display printouts and script performance data.
Converted to Kotlin, added .bat and .sh launchers.
Greatly increased performance by by multithreading file opening.

1.1.0
Backgrounds are now only counted if larger than vanilla size and only by their increase over vanilla.

1.0.0
Original release of Wisp's version.

Original script by Dark Revenant. Transcoded to Kotlin and edited to show more info by Wisp
"""
                )

            File(destinationDirectory.get().asFile, "LICENSE.txt")
                .writeText(project.file("LICENSE.txt").readText())

            File(destinationDirectory.get().asFile, "config.properties")
                .writeText(
                    StringBuilder()
                        .appendln("showSkippedFiles=false")
                        .appendln("showCountedFiles=true")
                        .appendln("showPerformance=false")
                        .toString()
                )
        }
    }
}

application {
    mainClassName = "MainKt"
}

dependencies {
    implementation(kotlin("stdlib-jdk7"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9")
}