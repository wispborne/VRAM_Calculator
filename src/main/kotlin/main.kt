import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import de.siegmar.fastcsv.reader.CsvReader
import kotlinx.coroutines.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.*
import javax.imageio.ImageIO


/**
 * v1.2.0
 *
 * Original Python script by Dark Revenant.
 * Transcoded to Kotlin and edited to show more info by Wisp.
 */

const val VANILLA_BACKGROUND_WIDTH = 2048
const val VANILLA_BACKGROUND_TEXTURE_SIZE_IN_BYTES = 12582912f
const val VANILLA_GAME_VRAM_USAGE_IN_BYTES =
    433586176 // 0.9.1a, per https://fractalsoftworks.com/forum/index.php?topic=8726.0
const val OUTPUT_LABEL_WIDTH = 38

/** If one of these strings is in the filename, the file is skipped **/
val UNUSED_INDICATOR = listOf("CURRENTLY_UNUSED", "DO_NOT_USE")
const val BACKGROUND_FOLDER_NAME = "backgrounds"
const val GRAPHICSLIB_ID = "shaderLib"

//val gameModsFolder = File("C:\\Program Files (x86)\\Fractal Softworks\\Starsector\\mods")
val currentFolder = File(System.getProperty("user.dir"))
val gameModsFolder: File = currentFolder.parentFile


suspend fun main(args: Array<String>) {
    val properties = runCatching {
        Properties().apply { load(File(currentFolder, "config.properties").bufferedReader()) }
    }.getOrNull()
    val showSkippedFiles = properties?.getProperty("showSkippedFiles")?.toBoolean() ?: false
    val showCountedFiles = properties?.getProperty("showCountedFiles")?.toBoolean() ?: true
    val showPerformance = properties?.getProperty("showPerformance")?.toBoolean() ?: true
    val showGfxLibDebugOutput = properties?.getProperty("showGfxLibDebugOutput")?.toBoolean() ?: false
    val areGfxLibNormalMapsEnabledProp = properties?.getProperty("areGfxLibNormalMapsEnabled")?.toBoolean()
    val areGfxLibMaterialMapsEnabledProp = properties?.getProperty("areGfxLibMaterialMapsEnabled")?.toBoolean()
    val areGfxLibSurfaceMapsEnabledProp = properties?.getProperty("areGfxLibSurfaceMapsEnabled")?.toBoolean()

    val progressText = StringBuilder()
    val modTotals = StringBuilder()
    val summaryText = StringBuilder()
    val startTime = Date().time

    val csvReader = CsvReader().apply {
        setSkipEmptyRows(true)
        setErrorOnDifferentFieldCount(false)
    }

    val jsonMapper = JsonMapper.builder()
        .defaultLeniency(true)
        .enable(
            JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_SINGLE_QUOTES,
            JsonReadFeature.ALLOW_YAML_COMMENTS, JsonReadFeature.ALLOW_MISSING_VALUES,
            JsonReadFeature.ALLOW_TRAILING_COMMA, JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES,
            JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS,
            JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER,
            JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS
        )
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

    if (!gameModsFolder.exists()) {
        println("This doesn't exist! ${gameModsFolder.absolutePath}")
        readLine()
        return
    }

    val enabledModIds = getUserEnabledModIds(jsonMapper, progressText)

    val graphicsLibConfig =
        if (enabledModIds?.contains(GRAPHICSLIB_ID) != true) {
            GraphicsLibConfig(
                areGfxLibNormalMapsEnabled = false,
                areGfxLibMaterialMapsEnabled = false,
                areGfxLibSurfaceMapsEnabled = false
            )
        } else {
            // GraphicsLib enabled
            if (listOf(
                    areGfxLibNormalMapsEnabledProp,
                    areGfxLibMaterialMapsEnabledProp,
                    areGfxLibSurfaceMapsEnabledProp
                ).any { it == null }
            ) {
                askUserForGfxLibConfig()
            } else {
                GraphicsLibConfig(
                    areGfxLibNormalMapsEnabled = areGfxLibNormalMapsEnabledProp!!,
                    areGfxLibMaterialMapsEnabled = areGfxLibMaterialMapsEnabledProp!!,
                    areGfxLibSurfaceMapsEnabled = areGfxLibSurfaceMapsEnabledProp!!
                )
            }
        }

    if (enabledModIds != null) progressText.appendAndPrint(
        "\nEnabled Mods:\n${enabledModIds.joinToString(separator = "\n")}"
    )

    progressText.appendAndPrint("Mods folder: ${gameModsFolder.absolutePath}")

    val mods = gameModsFolder
        .listFiles()!!
        .filter { it.isDirectory }
        .mapNotNull {
            getModInfo(jsonMapper = jsonMapper, modFolder = it, progressText = progressText)
        }
        .map { modInfo ->
            progressText.appendAndPrint("\nFolder: ${modInfo.name}")
            val startTimeForMod = Date().time

            val filesInMod =
                modInfo.folder.walkTopDown()
                    .filter { it.isFile }
                    .toList()

            val graphicsLibFilesToExcludeForMod =
                graphicsLibFilesToExcludeForMod(
                    filesInMod = filesInMod,
                    csvReader = csvReader,
                    progressText = progressText,
                    showGfxLibDebugOutput = showGfxLibDebugOutput,
                    graphicsLibConfig = graphicsLibConfig
                )

            val timeFinishedGettingGraphicsLibData = Date().time
            if (showPerformance) progressText.appendAndPrint(
                "Finished getting graphicslib data for ${modInfo.name} in ${(timeFinishedGettingGraphicsLibData - startTimeForMod)} ms"
            )

            val modImages = filesInMod
                .parallelMap { file ->
                    val image = try {
                        withContext(Dispatchers.IO) {
                            ImageIO.read(file)!!
                        }
                    } catch (e: Exception) {
                        if (showSkippedFiles)
                            progressText.appendAndPrint("Skipped non-image ${file.relativePath} (${e.message})")
                        return@parallelMap null
                    }

                    ModImage(
                        file = file,
                        textureHeight = if (image.width == 1) 1 else Integer.highestOneBit(image.width - 1) * 2,
                        textureWidth = if (image.height == 1) 1 else Integer.highestOneBit(image.height - 1) * 2,
                        bitsInAllChannels = image.colorModel.componentSize.toList(),
                        imageType = when {
                            file.relativePath.contains(BACKGROUND_FOLDER_NAME) -> ModImage.ImageType.Background
                            UNUSED_INDICATOR.any { suffix -> file.relativePath.contains(suffix) } -> ModImage.ImageType.Unused
                            else -> ModImage.ImageType.Texture
                        }
                    )
                }
                .filterNotNull()

            val timeFinishedGettingFileData = Date().time
            if (showPerformance) progressText.appendAndPrint(
                "Finished getting file data for ${modInfo.formattedName} in ${(timeFinishedGettingFileData - timeFinishedGettingGraphicsLibData)} ms"
            )

            val imagesToSumUp = modImages.toMutableList()

            imagesToSumUp.removeAll(modImages
                .filter { it.imageType == ModImage.ImageType.Unused }
                .also { if (it.any() && showSkippedFiles) progressText.appendAndPrint("Skipping unused files") }
                .onEach { progressText.appendAndPrint("  ${it.file.relativePath}") }
            )


            // The game only loads one background at a time and vanilla always has one loaded.
            // Therefore, a mod only increases the VRAM use by the size difference of the largest background over vanilla.
            val largestBackgroundBiggerThanVanilla = modImages
                .filter { it.imageType == ModImage.ImageType.Background && it.textureWidth > VANILLA_BACKGROUND_WIDTH }
                .maxByOrNull { it.bytesUsed }
            imagesToSumUp.removeAll(
                modImages.filter { it.imageType == ModImage.ImageType.Background && it != largestBackgroundBiggerThanVanilla }
                    .also {
                        if (it.any())
                            progressText.appendAndPrint(
                                "Skipping backgrounds that are not larger than vanilla and/or not the mod's largest background."
                            )
                    }
                    .onEach { progressText.appendAndPrint("   ${it.file.relativePath}") }
            )

            imagesToSumUp.forEach { image ->
                if (showCountedFiles) progressText.appendAndPrint(
                    "${image.file.relativePath} - TexHeight: ${image.textureHeight}, " +
                            "TexWidth: ${image.textureWidth}, " +
                            "Channels: ${image.bitsInAllChannels}, " +
                            "Mult: ${image.multiplier}\n" +
                            "   --> ${image.textureHeight} * ${image.textureWidth} * ${image.bitsInAllChannels.sum()} * ${image.multiplier} = ${image.bytesUsed} bytes added over vanilla"
                )
            }

            val imagesWithoutExcludedGfxLibMaps =
                if (graphicsLibFilesToExcludeForMod != null)
                    imagesToSumUp.filterNot { image ->
                        image.file.relativeTo(modInfo.folder) in graphicsLibFilesToExcludeForMod
                            .map { File(it.relativeFilePath) }
                    }
                else imagesToSumUp

            val mod = Mod(
                info = modInfo,
                isEnabled = modInfo.id in (enabledModIds ?: emptyList()),
                images = imagesWithoutExcludedGfxLibMaps
            )

            if (showPerformance) progressText.appendAndPrint(
                "Finished calculating file sizes for ${mod.info.formattedName} in ${(Date().time - timeFinishedGettingFileData)} ms"
            )
            progressText.appendAndPrint(mod.totalBytesForMod.bytesAsReadableMiB)
            mod
        }
        .sortedByDescending { it.totalBytesForMod }

    mods.forEach { mod ->
        modTotals.appendLine()
        modTotals.appendLine("${mod.info.formattedName} - ${mod.images.count()} images - ${if (mod.isEnabled) "Enabled" else "Disabled"}")
        modTotals.appendLine(mod.totalBytesForMod.bytesAsReadableMiB)
    }

    val enabledMods = mods.filter { mod -> mod.isEnabled }
    val totalBytes = mods.getBytesUsedByDedupedImages()

    val totalBytesOfEnabledMods = enabledMods
        .getBytesUsedByDedupedImages()

    if (showPerformance) progressText.appendAndPrint(
        "Finished run in ${(Date().time - startTime)} ms"
    )

    val enabledModsString =
        enabledMods.joinToString(separator = "\n    ") { it.info.formattedName }.ifBlank { "(none)" }

    progressText.appendAndPrint("\n")
    summaryText.appendLine()
    summaryText.appendLine("-------------")
    summaryText.appendLine("VRAM Use Estimates")
    summaryText.appendLine()
    summaryText.appendLine("Configuration")
    summaryText.appendLine("  Enabled Mods")
    summaryText.appendLine("    $enabledModsString")
    summaryText.appendLine("  GraphicsLib")
    summaryText.appendLine("    Normal Maps Enabled: ${graphicsLibConfig.areGfxLibNormalMapsEnabled}")
    summaryText.appendLine("    Material Maps Enabled: ${graphicsLibConfig.areGfxLibMaterialMapsEnabled}")
    summaryText.appendLine("    Surface Maps Enabled: ${graphicsLibConfig.areGfxLibSurfaceMapsEnabled}")
    summaryText.appendLine("    Edit 'config.properties' to choose your GraphicsLib settings.")
    getGPUInfo()?.also { info ->
        summaryText.appendLine("  System")
        summaryText.appendLine(info.getGPUString()?.joinToString(separator = "\n") { "    $it" })

        // If expected VRAM after loading game and mods is less than 300 MB, show warning
        if (info.getFreeVRAM() - (totalBytesOfEnabledMods + VANILLA_GAME_VRAM_USAGE_IN_BYTES) < 300000) {
            summaryText.appendLine()
            summaryText.appendLine("WARNING: You may not have enough free VRAM to run your current modlist.")
        }
    }
    summaryText.appendLine()

    summaryText.appendLine("Enabled + Disabled Mods w/o Vanilla".padEnd(OUTPUT_LABEL_WIDTH) + totalBytes.bytesAsReadableMiB)
    summaryText.appendLine("Enabled + Disabled Mods w/ Vanilla".padEnd(OUTPUT_LABEL_WIDTH) + (totalBytes + VANILLA_GAME_VRAM_USAGE_IN_BYTES).bytesAsReadableMiB)
    summaryText.appendLine()
    summaryText.appendLine("Enabled Mods w/o Vanilla".padEnd(OUTPUT_LABEL_WIDTH) + totalBytesOfEnabledMods.bytesAsReadableMiB)
    summaryText.appendLine("Enabled Mods w/ Vanilla".padEnd(OUTPUT_LABEL_WIDTH) + (totalBytesOfEnabledMods + VANILLA_GAME_VRAM_USAGE_IN_BYTES).bytesAsReadableMiB)

    summaryText.appendLine()
    summaryText.appendLine("** This is only an estimate of VRAM use and actual use may be higher or lower.")
    summaryText.appendLine("** Unused images in mods are counted unless they contain one of ${UNUSED_INDICATOR.joinToString { "\"$it\"" }} in the file name.")

    println(modTotals.toString())
    println(summaryText.toString())
    copyToClipboard(summaryText.toString())
    val outputFile = File("$currentFolder/VRAM_usage_of_mods.txt")
    outputFile.delete()
    outputFile.createNewFile()
    outputFile.writeText(progressText.toString())
    outputFile.appendText(modTotals.toString())
    outputFile.appendText(summaryText.toString())


    println("\nFile written to ${outputFile.absolutePath}.\nSummary copied to clipboard, ready to paste.")
}

fun askUserForGfxLibConfig(): GraphicsLibConfig {
    println("GraphicsLib increases VRAM usage, which VRAM_Counter accounts for.")
    println("Have you modified the default GraphicsLib config? (y/N)")
    val didUserChangeConfig = parseYesNoInput(readLine(), defaultResultIfBlank = false)
        ?: return askUserForGfxLibConfig()

    var result = GraphicsLibConfig(false, false, false)

    if (!didUserChangeConfig) {
        return GraphicsLibConfig(
            areGfxLibNormalMapsEnabled = true,
            areGfxLibMaterialMapsEnabled = true,
            areGfxLibSurfaceMapsEnabled = true
        )
    } else {
        println("Are normal maps enabled? (Y/n)")
        result = result.copy(
            areGfxLibNormalMapsEnabled = parseYesNoInput(readLine(), defaultResultIfBlank = true)
                ?: return askUserForGfxLibConfig()
        )
        println("Are material maps enabled? (Y/n)")
        result = result.copy(
            areGfxLibMaterialMapsEnabled = parseYesNoInput(readLine(), defaultResultIfBlank = true)
                ?: return askUserForGfxLibConfig()
        )
        println("Are surface maps enabled? (Y/n)")
        result = result.copy(
            areGfxLibSurfaceMapsEnabled = parseYesNoInput(readLine(), defaultResultIfBlank = true)
                ?: return askUserForGfxLibConfig()
        )

        return result
    }
}

fun parseYesNoInput(input: String?, defaultResultIfBlank: Boolean): Boolean? =
    when {
        input.isNullOrBlank() -> defaultResultIfBlank
        listOf("n", "no").any { it.equals(input, ignoreCase = true) } -> false
        listOf("y", "yes").any { it.equals(input, ignoreCase = true) } -> true
        else -> null
    }

fun List<Mod>.getBytesUsedByDedupedImages(): Long = this
    .flatMap { mod -> mod.images.map { img -> mod.info.folder to img } }
    .distinctBy { (modFolder: File, image: ModImage) -> image.file.relativeTo(modFolder).path + image.file.name }
    .sumOf { it.second.bytesUsed }

fun graphicsLibFilesToExcludeForMod(
    filesInMod: List<File>,
    csvReader: CsvReader,
    progressText: StringBuilder,
    showGfxLibDebugOutput: Boolean,
    graphicsLibConfig: GraphicsLibConfig
): List<GraphicsLibInfo>? {
    return filesInMod
        .filter { it.name.endsWith(".csv") }
        .mapNotNull { file ->
            runCatching { csvReader.read(file.reader()) }
                .recover {
                    progressText.appendAndPrint("Unable to read ${file.relativePath}: ${it.message}")
                    null
                }
                .getOrNull()
                ?.rows
                ?.map { it.fields }
        }
        // Look for a CSV with a header row containing certain column names
        .firstOrNull {
            it.first().containsAll(listOf("id", "type", "map", "path"))
        }
        ?.run {
            val mapColumn = this.first().indexOf("map")
            val pathColumn = this.first().indexOf("path")

            this.mapNotNull {
                try {
                    val mapType = when (it[mapColumn]) {
                        "normal" -> GraphicsLibInfo.MapType.Normal
                        "material" -> GraphicsLibInfo.MapType.Material
                        "surface" -> GraphicsLibInfo.MapType.Surface
                        else -> return@mapNotNull null
                    }
                    val path = it[pathColumn].trim()
                    GraphicsLibInfo(mapType, path)
                } catch (e: Exception) {
                    progressText.appendAndPrint("$this - ${e.message}")
                    null
                }
            }
        }
        ?.filter {
            when (it.mapType) {
                GraphicsLibInfo.MapType.Normal -> !graphicsLibConfig.areGfxLibNormalMapsEnabled
                GraphicsLibInfo.MapType.Material -> !graphicsLibConfig.areGfxLibMaterialMapsEnabled
                GraphicsLibInfo.MapType.Surface -> !graphicsLibConfig.areGfxLibSurfaceMapsEnabled
            }
        }
        .also {
            if (showGfxLibDebugOutput) it?.forEach { info -> progressText.appendAndPrint(info.toString()) }
        }
}

fun getUserEnabledModIds(jsonMapper: JsonMapper, progressText: StringBuilder): List<String>? {
    val enabledModsJsonFile = currentFolder.parentFile?.listFiles()
        ?.firstOrNull { it.name == "enabled_mods.json" }

    if (enabledModsJsonFile == null || !enabledModsJsonFile.exists()) {
        progressText.appendAndPrint("Unable to find 'enabled_mods.json'.")
        return null
    }

    return try {
        jsonMapper.readValue(enabledModsJsonFile, EnabledModsJsonModel::class.java).enabledMods
    } catch (e: Exception) {
        progressText.appendAndPrint(e.toString())
        null
    }
}

fun getModInfo(jsonMapper: JsonMapper, modFolder: File, progressText: StringBuilder): ModInfo? {
    return try {
        modFolder.listFiles()
            ?.firstOrNull { file -> file.name == "mod_info.json" }
            ?.let { modInfoFile ->
                runCatching {
                    val model = jsonMapper.readValue(modInfoFile, ModInfoJsonModel_095a::class.java)

                    ModInfo(
                        id = model.id,
                        folder = modFolder,
                        name = model.name,
                        version = "${model.version.major}.${model.version.minor}.${model.version.patch}"
                    )
                }
                    .recoverCatching {
                        val model = jsonMapper.readValue(modInfoFile, ModInfoJsonModel_091a::class.java)

                        ModInfo(
                            id = model.id,
                            folder = modFolder,
                            name = model.name,
                            version = model.version
                        )
                    }
                    .getOrThrow()
            }
    } catch (e: Exception) {
        progressText.appendAndPrint("Unable to find or read 'mod_info.json' in ${modFolder.absolutePath}. (${e.message})")
        null
    }
}

fun StringBuilder.appendAndPrint(line: String) {
    println(line)
    this.appendLine(line)
}

val File.relativePath: String
    get() = this.toRelativeString(gameModsFolder)

val Long.bytesAsReadableMiB: String
    get() = "%.3f MiB".format(this / 1048576f)

suspend fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> = coroutineScope {
    mapNotNull { async { f(it) } }.awaitAll()
}

fun copyToClipboard(string: String) {
    val stringSelection = StringSelection(string)
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(stringSelection, null)
}