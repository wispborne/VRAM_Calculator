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
    val properties = Properties().apply { load(File(currentFolder, "config.properties").bufferedReader()) }
    val showSkippedFiles = properties.getProperty("showSkippedFiles")?.toBoolean() ?: false
    val showCountedFiles = properties.getProperty("showCountedFiles")?.toBoolean() ?: true
    val showPerformance = properties.getProperty("showPerformance")?.toBoolean() ?: false

    val progressText = StringBuilder()
    val summaryText = StringBuilder()
    var totalBytes = 0L
    val startTime = Date().time

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
                    type = when {
                        file.relativePath.contains("backgrounds") -> Type.Background
                        file.relativePath.contains("_CURRENTLY_UNUSED") -> Type.Unused
                        else -> Type.Texture
                    }
                )
            }
            .filterNotNull()

        val timeFinishedGettingFileData = Date().time
        if (showPerformance) printAndAddLine(
            "Finished getting file data for ${modFolder.name} in ${(timeFinishedGettingFileData - startTimeForMod) / 1000} seconds",
            progressText
        )

        val filesToSumUp = modImages.toMutableList()

        filesToSumUp.removeAll(modImages
            .filter { it.type == Type.Unused }
            .also { if (it.any() && showSkippedFiles) printAndAddLine("Skipping unused files", progressText) }
            .onEach { printAndAddLine("  ${it.file.relativePath}", progressText) }
        )


        // The game only loads one background at a time and vanilla always has one loaded.
        // Therefore, a mod only increases the VRAM use by the size difference of the largest background over vanilla.
        val largestBackgroundBiggerThanVanilla = modImages
            .filter { it.type == Type.Background && it.textureWidth > VANILLA_BACKGROUND_WIDTH }
            .maxByOrNull { it.bytesUsed }
        filesToSumUp.removeAll(
            modImages.filter { it.type == Type.Background && it != largestBackgroundBiggerThanVanilla }
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
        totalBytes += totalBytesForMod

        if (showPerformance) printAndAddLine(
            "Finished calculating file sizes for ${modFolder.name} in ${(Date().time - timeFinishedGettingFileData) / 1000} seconds",
            progressText
        )

        if (totalBytesForMod == 0L) {
            continue
        }

        summaryText.appendLine("${modFolder.name} (${filesInMod.count()} images)")
        summaryText.appendLine(createSizeBreakdown(totalBytesForMod))
    }

    if (showPerformance) printAndAddLine(
        "Finished run in ${(Date().time - startTime) / 1000} seconds",
        progressText
    )

    printAndAddLine("\n", progressText)
    summaryText.appendLine("-------------")
    summaryText.appendLine("Total Modlist VRAM use")
    summaryText.appendLine(createSizeBreakdown(totalBytes))
    summaryText.appendLine("Total Modlist + Vanilla VRAM Use")
    summaryText.appendLine(createSizeBreakdown(totalBytes + VANILLA_GAME_VRAM_USAGE_IN_BYTES))
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

fun printAndAddLine(line: String, stringBuilder: StringBuilder) {
    println(line)
    stringBuilder.appendLine(line)
}

val File.relativePath: String
    get() = this.toRelativeString(gameModsFolder)

fun createSizeBreakdown(byteCount: Long): String {
    val sb = StringBuilder("$byteCount bytes\n")

    if (byteCount > 1024) {
        sb.appendLine("%.3f KiB".format(byteCount / 1024f))
    }

    if (byteCount > 1048576) {
        sb.appendLine("%.3f MiB".format(byteCount / 1048576f))
    }

    if (byteCount > 1073741824) {
        sb.appendLine("%.3f GiB".format(byteCount / 1073741824f))
    }

    return sb.toString()
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
    val type: Type
) {
    /**
     * Textures are mipmapped and therefore use 125% memory. Backgrounds are not.
     */
    val multiplier = if (type == Type.Background) 1f else 4f / 3f
    val bytesUsed by lazy {
        ceil( // Round up
            (textureHeight *
                    textureWidth *
                    (bitsInAllChannels.sum() / 8) *
                    multiplier) -
                    // Number of bytes in a vanilla background image
                    // Only count any excess toward the mod's VRAM hit
                    if (type == Type.Background) VANILLA_BACKGROUND_TEXTURE_SIZE_IN_BYTES else 0f
        )
            .toLong()
    }
}

enum class Type {
    Texture,
    Background,
    Unused
}