import kotlin.math.roundToLong

data class Mod(
    val info: ModInfo,
    val images: List<ModImage>
) {

    val totalBytesForMod by lazy {
        images.sumByDouble { it.bytesUsed.toDouble() }.roundToLong()
    }
}