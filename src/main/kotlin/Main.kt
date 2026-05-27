import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import parser.InheritanceResolver
import parser.MetadataParser
import java.io.File

val json = Json {
    prettyPrint = false
    encodeDefaults = false
}

fun main() {
    val parser = MetadataParser()
    val parseResult = parser.parseStdlib()

    val resolver = InheritanceResolver()
    val entries = resolver.resolve(parseResult)

    val sorted = entries.sortedWith(compareBy({ it.type }, { it.member }))

    val outputFile = File("methods.json")
    outputFile.writeText(json.encodeToString(sorted))

    val fileSizeKb = outputFile.length() / 1024
    println("\nWrote ${sorted.size} entries to ${outputFile.absolutePath} (${fileSizeKb} KB)")

    // Quick stats
    val byType = sorted.groupBy { it.type }
    println("${byType.size} types")
    println("Top 10: ${byType.entries.sortedByDescending { it.value.size }.take(10).joinToString { "${it.key}(${it.value.size})" }}")
}
