import docs.DocMerger
import docs.KDocParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import parser.InheritanceResolver
import parser.MetadataParser
import java.io.File

val json = Json {
    prettyPrint = false
    encodeDefaults = false
}

fun main(args: Array<String>) {
    require(args.size >= 3) {
        "Usage: <stdlib-binary-jar> <output-json-path> <sources-jar>..."
    }
    val binaryJar = args[0]
    val outputPath = args[1]
    val sourcesJars = args.drop(2).toTypedArray()

    // Phase 1: Parse metadata + builtins
    val parser = MetadataParser()
    val parseResult = parser.parseStdlib(binaryJar)

    val resolver = InheritanceResolver()
    val entries = resolver.resolve(parseResult)

    // Phase 2: Parse KDoc and merge
    val kdocParser = KDocParser()
    val kdocs = kdocParser.parseSourcesJar(*sourcesJars)

    val merger = DocMerger()
    val documented = merger.merge(entries, kdocs)

    // Serialize
    val sorted = documented.sortedWith(compareBy({ it.type }, { it.member }))

    val outputFile = File(outputPath)
    outputFile.parentFile?.mkdirs()
    outputFile.writeText(json.encodeToString(sorted))

    val fileSizeKb = outputFile.length() / 1024
    println("\nWrote ${sorted.size} entries to ${outputFile.absolutePath} (${fileSizeKb} KB)")

    // Spot-check
    val samples = listOf("List" to "filter", "String" to "split", "Map" to "get", "MutableList" to "add")
    println("\n--- Spot-check docs ---")
    for ((type, member) in samples) {
        val match = sorted.firstOrNull { it.type == type && it.member == member }
        if (match != null) {
            val summary = match.summary.take(80)
            val paramDoc = match.params.firstOrNull()?.doc?.take(60) ?: ""
            println("  $type.$member: \"$summary\"")
            if (paramDoc.isNotEmpty()) println("    param: \"$paramDoc\"")
        } else {
            println("  $type.$member: NOT FOUND")
        }
    }
}
