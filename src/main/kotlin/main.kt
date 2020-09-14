import com.github.doyaaaaaken.kotlincsv.client.CsvReader
import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import javax.imageio.ImageIO
import kotlin.math.ceil
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
val gameModsFolder = currentFolder.parentFile

suspend fun main(args: Array<String>) {
    val properties = runCatching {
        Properties().apply { load(File(currentFolder, "config.properties").bufferedReader()) }
    }.getOrNull()
    val showSkippedFiles = properties?.getProperty("showSkippedFiles")?.toBoolean() ?: false
    val showCountedFiles = properties?.getProperty("showCountedFiles")?.toBoolean() ?: true
    val showPerformance = properties?.getProperty("showPerformance")?.toBoolean() ?: false
    val showGraphicsLibInfo = properties?.getProperty("showGraphicsLibInfo")?.toBoolean() ?: false

    val progressText = StringBuilder()
    val summaryText = StringBuilder()
    var totalBytes = 0L
    var totalBytesWithGfxLibConf = 0L
    val startTime = Date().time

    val csvReader = csvReader {
        skipEmptyLine = true
        skipMissMatchedRow = true
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
        printAndAddLine("Folder: ${modFolder.name}", progressText)
        val startTimeForMod = Date().time

        val filesInMod =
            modFolder.walkTopDown()
                .filter { it.isFile }
                .toList()

        val graphicsLibDataForMod =
            getGraphicsLibDataForMod(filesInMod, csvReader, progressText, showGraphicsLibInfo)

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
                        file.relativePath.contains("backgrounds") -> ImageType.Background
                        file.relativePath.contains("_CURRENTLY_UNUSED") -> ImageType.Unused
                        else -> ImageType.Texture
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
            .filter { it.imageType == ImageType.Unused }
            .also { if (it.any() && showSkippedFiles) printAndAddLine("Skipping unused files", progressText) }
            .onEach { printAndAddLine("  ${it.file.relativePath}", progressText) }
        )


        // The game only loads one background at a time and vanilla always has one loaded.
        // Therefore, a mod only increases the VRAM use by the size difference of the largest background over vanilla.
        val largestBackgroundBiggerThanVanilla = modImages
            .filter { it.imageType == ImageType.Background && it.textureWidth > VANILLA_BACKGROUND_WIDTH }
            .maxByOrNull { it.bytesUsed }
        filesToSumUp.removeAll(
            modImages.filter { it.imageType == ImageType.Background && it != largestBackgroundBiggerThanVanilla }
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
            if (graphicsLibDataForMod != null)
                filesToSumUp.filterNot { it.file.relativeTo(modFolder).path in graphicsLibDataForMod.map { it.relativeFilePath } }
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
        summaryText.appendLine(totalBytesForMod.asReadableSize + " (${totalBytesWithoutMaps.asReadableSize} without maps)")
    }

    if (showPerformance) printAndAddLine(
        "Finished run in ${(Date().time - startTime)} ms",
        progressText
    )

    printAndAddLine("\n", progressText)
    summaryText.appendLine()
    summaryText.appendLine("-------------")
    summaryText.appendLine("Total Modlist VRAM use")
    summaryText.appendLine(totalBytes.asReadableSize + " (${totalBytesWithGfxLibConf.asReadableSize} without maps)")

    val totalBytesPlusVanillaUse = totalBytes + VANILLA_GAME_VRAM_USAGE_IN_BYTES
    val totalBytesPlusVanillaUseWithGfxLibConf = totalBytesWithGfxLibConf + VANILLA_GAME_VRAM_USAGE_IN_BYTES

    summaryText.appendLine()
    summaryText.appendLine("Total Modlist + Vanilla VRAM Use")
    summaryText.appendLine(totalBytesPlusVanillaUse.asReadableSize + " (${totalBytesPlusVanillaUseWithGfxLibConf.asReadableSize} without maps)")

    summaryText.appendLine()
    summaryText.appendLine("*This is only an estimate of VRAM use and actual use may be higher.*")

    println(summaryText.toString())
    val outputFile = File("$gameModsFolder/mods_VRAM_usage.txt")
    outputFile.delete()
    outputFile.createNewFile()
    outputFile.writeText(progressText.toString())
    outputFile.appendText(summaryText.toString())

    println("\nFile written to ${outputFile.name}\nPress any key to continue.")
    readLine()
}

private fun getGraphicsLibDataForMod(
    filesInMod: List<File>,
    csvReader: CsvReader,
    progressText: StringBuilder,
    showGraphicsLibInfo: Boolean
): List<GraphicsLibInfo>? {
    return filesInMod
        .filter { it.name.endsWith(".csv") }
        .mapNotNull { file ->
            runCatching { csvReader.readAll(file) }
                .recover {
                    printAndAddLine("Unable to read ${file.relativePath}: ${it.message}", progressText)
                    null
                }
                .getOrNull()
        }
        // Look for a CSV with a header row containing certain column names
        .firstOrNull {
            it.first().containsAll(listOf("id", "type", "map", "path"))
        }
        ?.run {
            val mapColumn = this.first().indexOf("map")
            val pathColumn = this.first().indexOf("path")
            this
                .mapNotNull {
                    val mapType = when (it[mapColumn]) {
                        "normal" -> GraphicsLibInfo.MapType.Normal
                        "material" -> GraphicsLibInfo.MapType.Material
                        "surface" -> GraphicsLibInfo.MapType.Surface
                        else -> return@mapNotNull null
                    }
                    val path = it[pathColumn]
                    GraphicsLibInfo(mapType, path)
                }
        }
        .also {
            if (showGraphicsLibInfo) it?.forEach { info -> printAndAddLine(info.toString(), progressText) }
        }
}

fun printAndAddLine(line: String, stringBuilder: StringBuilder) {
    println(line)
    stringBuilder.appendLine(line)
}

val File.relativePath: String
    get() = this.toRelativeString(gameModsFolder)

val Long.asReadableBytes: String
    get() = "$this bytes"

val Long.asReadableKiB: String?
    get() = if (this > 1024) "%.3f KiB".format(this / 1024f) else null

val Long.asReadableMiB: String?
    get() = if (this > 1048576f) "%.3f MiB".format(this / 1048576f) else null

val Long.asReadableGiB: String?
    get() = if (this > 1073741824f) "%.3f GiB".format(this / 1073741824f) else null

val Long.asReadableSize: String
    get() =
        when {
            this.asReadableGiB != null -> this.asReadableGiB!!
            this.asReadableMiB != null -> this.asReadableMiB!!
            this.asReadableKiB != null -> this.asReadableKiB!!
            else -> this.asReadableBytes
        }


suspend fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> = coroutineScope {
    mapNotNull { async { f(it) } }.awaitAll()
}

/**
 * @param textureHeight Next highest power of two
 * @param textureWidth Next highest power of two
 */
data class ModImage(
    val file: File,
    val textureHeight: Int,
    val textureWidth: Int,
    val bitsInAllChannels: List<Int>,
    val imageType: ImageType
) {
    /**
     * Textures are mipmapped and therefore use 125% memory. Backgrounds are not.
     */
    val multiplier = if (imageType == ImageType.Background) 1f else 4f / 3f
    val bytesUsed by lazy {
        ceil( // Round up
            (textureHeight *
                    textureWidth *
                    (bitsInAllChannels.sum() / 8) *
                    multiplier) -
                    // Number of bytes in a vanilla background image
                    // Only count any excess toward the mod's VRAM hit
                    if (imageType == ImageType.Background) VANILLA_BACKGROUND_TEXTURE_SIZE_IN_BYTES else 0f
        )
            .toLong()
    }
}

enum class ImageType {
    Texture,
    Background,
    Unused
}

data class GraphicsLibInfo(
    val mapType: MapType,
    val relativeFilePath: String
) {
    enum class MapType {
        Normal,
        Material,
        Surface
    }
}