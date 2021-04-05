import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.10"
    application
}
group = "com.wisp"
val kotlinVersion = "1.4.10"


val vramCounterVersion = "1.10.0"
val toolname = "VRAM-Counter"
val jarFileName = "$toolname.jar"
val destinationFolder = file("$rootDir/artifacts/$toolname-$vramCounterVersion")
val javaPath_Windows = "../../jre/bin/java.exe"
val javaPath_Linux = "../../jre_linux/bin/java"
val javaExe_MacOS = "java"
val starsectorCorePath_Windows = "../../starsector-core"
val starsectorCorePath_Linux = "../.."
val starsectorCorePath_MacOS = "../.."

repositories {
    mavenCentral()
}

application {
    mainClassName = "MainKt"
}

dependencies {
    implementation(kotlin("stdlib-jdk7"))
    implementation(fileTree("libs") { include("*.jar") })
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9")
    implementation("de.siegmar:fastcsv:1.0.3")
    implementation("com.fasterxml.jackson.core:jackson-core:2.11.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.11.2")
    // Comnpiled for Java 8, doesn't work
//    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:0.11.0")
}

tasks {
// Compile to Java 6 bytecode so that Starsector can use it
    withType<KotlinCompile>() {
        kotlinOptions.jvmTarget = "1.6"
    }

    named<Jar>("jar")
    {
        dependsOn(":create-artifact-dir")
        dependsOn(":copyNativeFiles")
        dependsOn(":writeReadme")
        dependsOn(":write-windows-startup")
        dependsOn(":write-linux-startup")
        dependsOn(":write-macos-startup")
        destinationDirectory.set(destinationFolder)
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

            File(destinationDirectory.get().asFile, "LICENSE.txt")
                .writeText(project.file("LICENSE.txt").readText())

            File(destinationDirectory.get().asFile, "config.properties")
                .writeText(
                    StringBuilder()
                        .appendln("showSkippedFiles=false")
                        .appendln("showCountedFiles=true")
                        .appendln("showPerformance=true")
                        .appendln("showGfxLibDebugOutput=false")
                        .appendln("# areGfxLibNormalMapsEnabled=true")
                        .appendln("# areGfxLibMaterialMapsEnabled=true")
                        .appendln("# areGfxLibSurfaceMapsEnabled=true")
                        .toString()
                )

            File(destinationDirectory.get().asFile, "screenshot.png")
                .writeBytes(project.file("screenshot.png").readBytes())
        }
    }

    register("create-artifact-dir") {
        destinationFolder.mkdirs()
    }

    register<Copy>("copyNativeFiles") {
        from("libs/native")
        into("${destinationFolder.path}/native")
    }

    register("write-windows-startup") {
        File(destinationFolder.path, "$toolname-windows.bat")
            .writeText(
                """
@echo off
IF NOT EXIST $javaPath_Windows (
    ECHO Place $toolname folder in Starsector's mods folder.
    pause
) ELSE (
 "$javaPath_Windows" -Djava.library.path=native/windows -jar ./$jarFileName
 pause
)
"""
            )
    }

    register("write-linux-startup") {
        File(destinationFolder.path, "$toolname-linux.sh")
            .writeText(
                """
#!/bin/sh

if [ ! -f "$javaPath_Linux" ]; then
    echo "Place VRAM-Counter folder in Starsector's mods folder."
    read -p "Press any key to continue ..."
else
    "$javaPath_Linux" -Djava.library.path=native/linux -jar ./VRAM-Counter.jar
    read -p "Press any key to continue ..."
fi
"""
            )
    }

    register("write-macos-startup") {
        File(destinationFolder.path, "$toolname-macos.sh")
            .writeText(
                """
#!/bin/sh

if [ ! -f "$javaExe_MacOS" ]; then
    echo "Place VRAM-Counter folder in Starsector's mods folder."
    read -p "Press any key to continue ..."
else
    "$javaExe_MacOS" -Djava.library.path=native/linux -jar ./VRAM-Counter.jar
    read -p "Press any key to continue ..."
fi
"""
            )
    }

    register("writeReadme") {

        // Write readme.md
        File(destinationFolder, "readme.md")
            .writeText(
                """
# $toolname $vramCounterVersion

## Use

Place folder in Starsector's /mods folder, then launch.
Windows: Open .bat file
Not-Windows: Use .sh file

![screenshot](screenshot.png)

## How Much VRAM Do I Have?

Win10: Please read the relevant section in <https://fractalsoftworks.com/forum/index.php?topic=19122.0>.

MacOS: The information is under Apple menu (top-left) -> About This Mac -> More Info -> Hardware -> Graphics/Displays.

Linux: The console command you need changes based on your distro, GPU type, GPU driver, GPU driver version, the year, moon phase, barometric pressure, and the current hormonal makeup of your kernel. Try StackOverflow, and good luck.

## Changelog

1.10.0
- Support for Starsecor 0.9.5a mod_info.json format.
- Backwards compatible with 0.91a format.

1.9.0
- GPU information, including dedicated VRAM if Nvidia, is now displayed. Credit to LazyWizard for most of the code (taken from Console Commands).

1.8.0

- Sort mods by their VRAM impact in descending order.
- Show total at bottom of each detailed breakdown.
- Slight text cleanup.
- Fixed the detailed breakdown not getting added to the file output.
- Added section to Readme about finding your GPU's VRAM.

1.7.0

- Mod totals are now included in the output text file.

1.6.0

- Prompt for GraphicsLib settings on each run.

1.5.0

- Make it clearer what it and isn't counted when user only copy/pastes a single line from the output.
- Add all enabled mods to the summary view.
- Copy the summary to the clipboard so it may be easily pasted into chat.

1.4.0

- Total estimated use no longer counts images with the same relative path and name multiple times.
  - So if Mods A and B both have /graphics/image.png, both will have the size counted in the per-mod display, but it will be only counted once in the total.

1.3.0

- Now prints out estimated usage of *enabled mods*, in addition to all found mods.
- For readability, only the currently chosen GraphicsLib settings (in 'config.properties') are shown.
- Fixed the # images count incorrectly counting all files.
- Now shows mod name, version, and id instead of mod folder name.

1.2.0

- Image channels are now accurately detected for all known cases, improving accuracy (now on par with original Python script).
- Files with '_CURRENTLY_UNUSED' in the name are ignored.
- Added configuration file for display printouts and script performance data.
- Converted to Kotlin, added .bat and .sh launchers.
- Greatly increased performance by multithreading file opening.
- Now shows with GraphicsLib (default) and with specific GraphicsLib maps disabled (configurable)

1.1.0

- Backgrounds are now only counted if larger than vanilla size and only by their increase over vanilla.

1.0.0

- Original release of Wisp's version.

Original script by Dark Revenant. Transcoded to Kotlin and edited to show more info by Wisp.
Source: [https://github.com/davidwhitman/VRAM_Calculator]
"""
            )
    }

}