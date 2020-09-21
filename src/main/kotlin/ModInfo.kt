import java.io.File

data class ModInfo(
    val id: String,
    val folder: File,
    val name: String,
    val version: String
) {
    val formattedName = "$name $version ($id)"
}