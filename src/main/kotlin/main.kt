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
    var totalBytes = 0L
    var totalBytesWithGfxLibConf = 0L
    val startTime = Date().time

    val csvReader = CsvReader().apply {
        setSkipEmptyRows(true)
        setErrorOnDifferentFieldCount(false)
    }

    if (!gameModsFolder.exists()) {
        println("This doesn't exist! ${gameModsFolder.absolutePath}")
        readLine()
        return
    }

    val modFolders = gameModsFolder
        .listFiles()!!
        .filter { it.isDirectory }

    printAndAddLine("Mods folder: ${gameModsFolder.absolutePath}", progressText)

    for (modFolder in modFolders) {
        printAndAddLine("\nFolder: ${modFolder.name}", progressText)
        val startTimeForMod = Date().time

        val filesInMod =
            modFolder.walkTopDown()
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
            "Finished getting graphicslib data for ${modFolder.name} in ${(timeFinishedGettingGraphicsLibData - startTimeForMod)} ms",
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
            "Finished getting file data for ${modFolder.name} in ${(timeFinishedGettingFileData - timeFinishedGettingGraphicsLibData)} ms",
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
                filesToSumUp.filterNot { it.file.relativeTo(modFolder) in graphicsLibFilesToExcludeForMod.map { File(it.relativeFilePath) } }
            else filesToSumUp
        val totalBytesWithoutMaps = filesWithoutMaps.sumByDouble { it.bytesUsed.toDouble() }.roundToLong()
        totalBytes += totalBytesForMod
        totalBytesWithGfxLibConf += totalBytesWithoutMaps

        if (showPerformance) printAndAddLine(
            "Finished calculating file sizes for ${modFolder.name} in ${(Date().time - timeFinishedGettingFileData)} ms",
            progressText
        )

        if (totalBytesForMod == 0L) {
            continue
        }

        summaryText.appendLine()
        summaryText.appendLine("${modFolder.name} (${filesInMod.count()} images)")
        summaryText.appendLine(totalBytesForMod.asReadableSize + " - with GraphicsLib")
        summaryText.appendLine(totalBytesWithoutMaps.asReadableSize + " - with specified GraphicsLib settings")
    }

    if (showPerformance) printAndAddLine(
        "Finished run in ${(Date().time - startTime)} ms",
        progressText
    )

    printAndAddLine("\n", progressText)
    summaryText.appendLine()
    summaryText.appendLine("-------------")
    summaryText.appendLine("Total Modlist VRAM use")
    summaryText.appendLine(totalBytes.asReadableSize + " - with GraphicsLib")
    summaryText.appendLine(totalBytesWithGfxLibConf.asReadableSize + " - with specified GraphicsLib settings")

    val totalBytesPlusVanillaUse = totalBytes + VANILLA_GAME_VRAM_USAGE_IN_BYTES
    val totalBytesPlusVanillaUseWithGfxLibConf = totalBytesWithGfxLibConf + VANILLA_GAME_VRAM_USAGE_IN_BYTES

    summaryText.appendLine()
    summaryText.appendLine("Total Modlist + Vanilla VRAM Use")
    summaryText.appendLine(totalBytesPlusVanillaUse.asReadableSize + " - with GraphicsLib")
    summaryText.appendLine(totalBytesPlusVanillaUseWithGfxLibConf.asReadableSize + " - with specified GraphicsLib settings")

    summaryText.appendLine()
    summaryText.appendLine("*This is only an estimate of VRAM use and actual use may be higher.*")

    println(summaryText.toString())
    val outputFile = File("$gameModsFolder/mods_VRAM_usage.txt")
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