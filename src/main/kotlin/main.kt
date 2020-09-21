import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import de.siegmar.fastcsv.reader.CsvReader
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import javax.imageio.ImageIO
import kotlin.math.roundToLong


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
const val OUTPUT_LABEL_WIDTH = 20

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
    val areGfxLibNormalMapsEnabled = properties?.getProperty("areGfxLibNormalMapsEnabled")?.toBoolean() ?: false
    val areGfxLibMaterialMapsEnabled = properties?.getProperty("areGfxLibMaterialMapsEnabled")?.toBoolean() ?: false
    val areGfxLibSurfaceMapsEnabled = properties?.getProperty("areGfxLibSurfaceMapsEnabled")?.toBoolean() ?: false

    val progressText = StringBuilder()
    val summaryText = StringBuilder()
    var totalBytesOfEnabledMods = 0L
    var totalBytesWithGfxLibConf = 0L
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
    if (enabledModIds != null) printAndAddLine(
        "Enabled Mods:\n${enabledModIds.joinToString(separator = "\n")}",
        progressText
    )

    val mods = gameModsFolder
        .listFiles()!!
        .filter { it.isDirectory }
        .mapNotNull {
            getModInfo(jsonMapper = jsonMapper, modFolder = it, progressText = progressText)
        }

    printAndAddLine("Mods folder: ${gameModsFolder.absolutePath}", progressText)

    for (mod in mods) {
        printAndAddLine("\nFolder: ${mod.name}", progressText)
        val startTimeForMod = Date().time

        val filesInMod =
            mod.folder.walkTopDown()
                .filter { it.isFile }
                .toList()

        val graphicsLibFilesToExcludeForMod =
            graphicsLibFilesToExcludeForMod(
                filesInMod = filesInMod,
                csvReader = csvReader,
                progressText = progressText,
                showGfxLibDebugOutput = showGfxLibDebugOutput,
                areGfxLibNormalMapsEnabled = areGfxLibNormalMapsEnabled,
                areGfxLibMaterialMapsEnabled = areGfxLibMaterialMapsEnabled,
                areGfxLibSurfaceMapsEnabled = areGfxLibSurfaceMapsEnabled
            )

        val timeFinishedGettingGraphicsLibData = Date().time
        if (showPerformance) printAndAddLine(
            "Finished getting graphicslib data for ${mod.name} in ${(timeFinishedGettingGraphicsLibData - startTimeForMod)} ms",
            progressText
        )

        val modImages = filesInMod
            .parallelMap { file ->
                val image = try {
                    withContext(Dispatchers.IO) {
                        ImageIO.read(file)!!
                    }
                } catch (e: Exception) {
                    if (showSkippedFiles)
                        printAndAddLine("Skipped non-image ${file.relativePath} (${e.message})", progressText)
                    return@parallelMap null
                }

                ModImage(
                    file = file,
                    textureHeight = if (image.width == 1) 1 else Integer.highestOneBit(image.width - 1) * 2,
                    textureWidth = if (image.height == 1) 1 else Integer.highestOneBit(image.height - 1) * 2,
                    bitsInAllChannels = image.colorModel.componentSize.toList(),
                    imageType = when {
                        file.relativePath.contains("backgrounds") -> ModImage.ImageType.Background
                        file.relativePath.contains("_CURRENTLY_UNUSED") -> ModImage.ImageType.Unused
                        else -> ModImage.ImageType.Texture
                    }
                )
            }
            .filterNotNull()

        val timeFinishedGettingFileData = Date().time
        if (showPerformance) printAndAddLine(
            "Finished getting file data for ${mod.formattedName} in ${(timeFinishedGettingFileData - timeFinishedGettingGraphicsLibData)} ms",
            progressText
        )

        val filesToSumUp = modImages.toMutableList()

        filesToSumUp.removeAll(modImages
            .filter { it.imageType == ModImage.ImageType.Unused }
            .also { if (it.any() && showSkippedFiles) printAndAddLine("Skipping unused files", progressText) }
            .onEach { printAndAddLine("  ${it.file.relativePath}", progressText) }
        )


        // The game only loads one background at a time and vanilla always has one loaded.
        // Therefore, a mod only increases the VRAM use by the size difference of the largest background over vanilla.
        val largestBackgroundBiggerThanVanilla = modImages
            .filter { it.imageType == ModImage.ImageType.Background && it.textureWidth > VANILLA_BACKGROUND_WIDTH }
            .maxByOrNull { it.bytesUsed }
        filesToSumUp.removeAll(
            modImages.filter { it.imageType == ModImage.ImageType.Background && it != largestBackgroundBiggerThanVanilla }
                .also {
                    if (it.any())
                        printAndAddLine(
                            "Skipping backgrounds that are not larger than vanilla and/or not the mod's largest background.",
                            progressText
                        )
                }
                .onEach { printAndAddLine("   ${it.file.relativePath}", progressText) }
        )

        filesToSumUp.forEach { image ->
            if (showCountedFiles) printAndAddLine(
                "${image.file.relativePath} - TexHeight: ${image.textureHeight}, " +
                        "TexWidth: ${image.textureWidth}, " +
                        "Channels: ${image.bitsInAllChannels}, " +
                        "Mult: ${image.multiplier}\n" +
                        "   --> ${image.textureHeight} * ${image.textureWidth} * ${image.bitsInAllChannels.sum()} * ${image.multiplier} = ${image.bytesUsed} bytes added over vanilla",
                progressText
            )
        }

        val totalBytesForMod = filesToSumUp.sumByDouble { it.bytesUsed.toDouble() }.roundToLong()
        val filesWithoutMaps =
            if (graphicsLibFilesToExcludeForMod != null)
                filesToSumUp.filterNot { it.file.relativeTo(mod.folder) in graphicsLibFilesToExcludeForMod.map { File(it.relativeFilePath) } }
            else filesToSumUp
        val totalBytesWithoutMaps = filesWithoutMaps.sumByDouble { it.bytesUsed.toDouble() }.roundToLong()
        totalBytesWithGfxLibConf += totalBytesWithoutMaps
        totalBytesOfEnabledMods += if (mod.id in enabledModIds ?: emptyList()) totalBytesWithoutMaps else 0L

        if (showPerformance) printAndAddLine(
            "Finished calculating file sizes for ${mod.formattedName} in ${(Date().time - timeFinishedGettingFileData)} ms",
            progressText
        )

        if (totalBytesForMod == 0L) {
            continue
        }

        summaryText.appendLine()
        summaryText.appendLine("${mod.formattedName} (${modImages.count()} images)")
        summaryText.appendLine(totalBytesWithoutMaps.asReadableSize)
    }

    if (showPerformance) printAndAddLine(
        "Finished run in ${(Date().time - startTime)} ms",
        progressText
    )

    printAndAddLine("\n", progressText)
    summaryText.appendLine()
    summaryText.appendLine("-------------")
    summaryText.appendLine("VRAM Use Estimates")
    summaryText.appendLine("Edit 'config.properties' to choose your GraphicsLib settings.")
    summaryText.appendLine()

    summaryText.appendLine("All Mod Folders".padEnd(OUTPUT_LABEL_WIDTH) + totalBytesWithGfxLibConf.asReadableSize)
    summaryText.appendLine("Incl. Vanilla Usage".padEnd(OUTPUT_LABEL_WIDTH) + (totalBytesWithGfxLibConf + VANILLA_GAME_VRAM_USAGE_IN_BYTES).asReadableSize)
    summaryText.appendLine()
    summaryText.appendLine("Enabled Mods Only".padEnd(OUTPUT_LABEL_WIDTH) + totalBytesOfEnabledMods.asReadableSize)
    summaryText.appendLine("Incl. Vanilla Usage".padEnd(OUTPUT_LABEL_WIDTH) + (totalBytesOfEnabledMods + VANILLA_GAME_VRAM_USAGE_IN_BYTES).asReadableSize)

    summaryText.appendLine()
    summaryText.appendLine("*This is only an estimate of VRAM use and actual use may be higher.*")

    println(summaryText.toString())
    val outputFile = File("$currentFolder/VRAM_usage_of_mods.txt")
    outputFile.delete()
    outputFile.createNewFile()
    outputFile.writeText(progressText.toString())
    outputFile.appendText(summaryText.toString())

    println("\nFile written to ${outputFile.absolutePath}\nPress any key to continue.")
    readLine()
}

private fun graphicsLibFilesToExcludeForMod(
    filesInMod: List<File>,
    csvReader: CsvReader,
    progressText: StringBuilder,
    showGfxLibDebugOutput: Boolean,
    areGfxLibNormalMapsEnabled: Boolean,
    areGfxLibMaterialMapsEnabled: Boolean,
    areGfxLibSurfaceMapsEnabled: Boolean
): List<GraphicsLibInfo>? {
    return filesInMod
        .filter { it.name.endsWith(".csv") }
        .mapNotNull { file ->
            runCatching { csvReader.read(file.reader()) }
                .recover {
                    printAndAddLine("Unable to read ${file.relativePath}: ${it.message}", progressText)
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
                    printAndAddLine("$this - ${e.message}", progressText)
                    null
                }
            }
        }
        ?.filter {
            when (it.mapType) {
                GraphicsLibInfo.MapType.Normal -> !areGfxLibNormalMapsEnabled
                GraphicsLibInfo.MapType.Material -> !areGfxLibMaterialMapsEnabled
                GraphicsLibInfo.MapType.Surface -> !areGfxLibSurfaceMapsEnabled
            }
        }
        .also {
            if (showGfxLibDebugOutput) it?.forEach { info -> printAndAddLine(info.toString(), progressText) }
        }
}

fun getUserEnabledModIds(jsonMapper: JsonMapper, progressText: StringBuilder): List<String>? {
    val enabledModsJsonFile = currentFolder.parentFile?.listFiles()
        ?.firstOrNull { it.name == "enabled_mods.json" }

    if (enabledModsJsonFile == null || !enabledModsJsonFile.exists()) {
        printAndAddLine("Unable to find 'enabled_mods.json'.", progressText)
        return null
    }

    return try {
        jsonMapper.readValue(enabledModsJsonFile, EnabledModsJsonModel::class.java).enabledMods
    } catch (e: Exception) {
        printAndAddLine(e.toString(), progressText)
        null
    }
}

fun getModInfo(jsonMapper: JsonMapper, modFolder: File, progressText: StringBuilder): Mod? {
    return try {
        modFolder.listFiles()
            ?.firstOrNull { file -> file.name == "mod_info.json" }
            ?.let { modInfoFile -> jsonMapper.readValue(modInfoFile, ModInfoJsonModel::class.java) }
            ?.let {
                Mod(
                    id = it.id,
                    folder = modFolder,
                    name = it.name,
                    version = it.version
                )
            }
    } catch (e: Exception) {
        printAndAddLine("Unable to find 'mod_info.json' in ${modFolder.absolutePath}.", progressText)
        null
    }
}

data class EnabledModsJsonModel(@JsonProperty("enabledMods") val enabledMods: List<String>)
data class ModInfoJsonModel(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("version") val version: String,
)

fun printAndAddLine(line: String, stringBuilder: StringBuilder) {
    println(line)
    stringBuilder.appendLine(line)
}

val File.relativePath: String
    get() = this.toRelativeString(gameModsFolder)

val Long.asReadableSize: String
    get() = "%.3f MiB".format(this / 1048576f)

suspend fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> = coroutineScope {
    mapNotNull { async { f(it) } }.awaitAll()
}