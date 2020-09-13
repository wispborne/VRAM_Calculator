import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.roundToLong


/**
 * v1.2.0
 *
 * Original Python script by Dark Revenant.
 * Transcoded to Kotlin and edited to show more info by Wisp.
 *
 * v1.2.0 - Image channels are now accurately detected for all known cases, improving accuracy (now on par with original Python script).
 * v1.1.0 - Backgrounds are now only counted if larger than vanilla size and only by their increase over vanilla.
 * v1.0.0 - Original release of Wisp's version.
 */

const val VANILLA_BACKGROUND_WIDTH = 2048
const val VANILLA_BACKGROUND_TEXTURE_SIZE_IN_BYTES = 12582912f

//val gameModsFolder = File("C:\\Program Files (x86)\\Fractal Softworks\\Starsector\\mods")
val gameModsFolder = File(System.getProperty("user.dir")) //

fun main(args: Array<String>) {
    val debugMode = false

    val finalReadout = StringBuilder()
    var totalBytes = 0L

    if (!gameModsFolder.exists()) {
        println("This doesn't exist! ${gameModsFolder.absolutePath}")
        readLine()
        return
    }

    val modFolders = gameModsFolder
        .listFiles()!!
        .filter { it.isDirectory }

    println("Mods folder: ${gameModsFolder.absolutePath}")

    for (modFolder in modFolders) {
        println("Folder: ${modFolder.name}")

        val filesInMod =
            modFolder.walkTopDown()
                .filter { it.isFile }
                .toList()

        val modImages = filesInMod
            .mapNotNull { file ->
                val image = try {
                    ImageIO.read(file)!!
                } catch (e: Exception) {
                    if (debugMode)
                        println("Skipping non-image ${file.relativePath} (${e.message})")
                    return@mapNotNull null
                }

                ModImage(
                    file = file,
                    textureHeight = if (image.width == 1) 1 else Integer.highestOneBit(image.width - 1) * 2,
                    textureWidth = if (image.height == 1) 1 else Integer.highestOneBit(image.height - 1) * 2,
                    bitsInAllChannels = image.colorModel.componentSize.toList(),
                    type = when {
                        file.relativePath.contains("backgrounds") -> Type.Background
                        file.relativePath.endsWith("_CURRENTLY_UNUSED") -> Type.Unused
                        else -> Type.Texture
                    }
                )
            }

        val filesToSumUp = modImages.toMutableList()

        filesToSumUp.removeAll(modImages
            .filter { it.type == Type.Unused }
            .also { if (it.any()) println("Skipping unused files") }
            .onEach { println("  ${it.file.relativePath}") }
        )


        // The game only loads one background at a time and vanilla always has one loaded.
        // Therefore, a mod only increases the VRAM use by the size difference of the largest background over vanilla.
        val largestBackgroundBiggerThanVanilla = modImages
            .filter { it.type == Type.Background && it.textureWidth > VANILLA_BACKGROUND_WIDTH }
            .maxBy { it.bytesUsed }
        filesToSumUp.removeAll(
            modImages.filter { it.type == Type.Background && it != largestBackgroundBiggerThanVanilla }
                .also {
                    if (it.any())
                        println("Skipping backgrounds that are not larger than vanilla and/or not the mod's largest background.")
                }
                .onEach { println("   ${it.file.relativePath}") }
        )

        filesToSumUp.forEach { image ->
            println(
                "${image.file.relativePath} - TexHeight: ${image.textureHeight}, " +
                        "TexWidth: ${image.textureWidth}, " +
                        "Channels: ${image.bitsInAllChannels}, " +
                        "Mult: ${image.multiplier}\n" +
                        "   --> ${image.textureHeight} * ${image.textureWidth} * ${image.bitsInAllChannels.sum()} * ${image.multiplier} = ${image.bytesUsed} bytes added over vanilla"
            )
        }

        val totalBytesForMod = filesToSumUp.sumByDouble { it.bytesUsed.toDouble() }.roundToLong()
        totalBytes += totalBytesForMod

        if (totalBytesForMod == 0L) {
            continue
        }

        finalReadout.appendLine("${modFolder.name} (${filesInMod.count()} images)")
        finalReadout.appendLine(createSizeBreakdown(totalBytesForMod))
    }

    finalReadout.appendLine("-------------")
    finalReadout.appendLine("Total Modlist")
    finalReadout.appendLine(createSizeBreakdown(totalBytes))

    println(finalReadout.toString())
    val outputFile = File("$gameModsFolder/mods_VRAM_usage.txt")
    outputFile.createNewFile()
    outputFile.writeText(finalReadout.toString())

    println("\nFile written to ${outputFile.name}\nPress any key to continue.")
    readLine()
}

val File.relativePath: String
    get() = this.toRelativeString(gameModsFolder)

fun createSizeBreakdown(byteCount: Long): String {
    val sb = StringBuilder("$byteCount bytes\n")

    if (byteCount > 1024) {
        sb.appendLine("%.3f KB".format(byteCount / 1024f))
    }

    if (byteCount > 1048576) {
        sb.appendLine("%.3f MB".format(byteCount / 1048576f))
    }

    if (byteCount > 1073741824) {
        sb.appendLine("%.3f GB".format(byteCount / 1073741824f))
    }

    return sb.toString()
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