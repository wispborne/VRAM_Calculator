import java.io.File

data class Mod(
    val id: String,
    val folder: File,
    val name: String,
    val version: String
) {
    val formattedName = "$name $version ($id)"
}