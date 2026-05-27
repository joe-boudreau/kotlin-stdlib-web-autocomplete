import parser.MetadataParser

fun main() {
    val parser = MetadataParser()
    val result = parser.parseStdlib()

    println("\n=== Types Found: ${result.types.size} ===")
    result.types.values
        .sortedBy { it.qualifiedName }
        .forEach { type ->
            val memberCount = type.declaredMembers.size
            val supers = if (type.supertypes.isNotEmpty()) {
                " : " + type.supertypes.joinToString(", ") { it.substringAfterLast('.') }
            } else ""
            val typeParamStr = if (type.typeParameters.isNotEmpty()) {
                "<" + type.typeParameters.joinToString(", ") + ">"
            } else ""
            println("  ${type.kind} ${type.name}$typeParamStr$supers  ($memberCount members)")
        }

    println("\n=== Extension Functions Found: ${result.extensionFunctions.size} ===")
    val byReceiver = result.extensionFunctions.groupBy { it.receiverType ?: "?" }
    byReceiver.entries.sortedBy { it.key }.forEach { (receiver, fns) ->
        println("  ${receiver.substringAfterLast('.')}: ${fns.size} extensions")
    }

    println("\n=== Sample Signatures ===")
    result.types["kotlin.collections.List"]?.let { list ->
        println("  List members (first 10):")
        list.declaredMembers.take(10).forEach { m ->
            println("    ${m.signature}")
        }
    }
    result.extensionFunctions
        .filter { it.receiverType == "kotlin.collections.List" || it.receiverType == "kotlin.collections.Iterable" }
        .take(10)
        .forEach { m ->
            println("  EXT: ${m.signature}")
        }
}
