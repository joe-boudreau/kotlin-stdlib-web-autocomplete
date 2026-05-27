package parser

import model.*
import model.MemberKind as MK

class InheritanceResolver {

    fun resolve(parseResult: ParseResult): List<DenormalizedEntry> {
        val allTypes = buildCompleteTypeMap(parseResult)
        val extensionsByReceiver = parseResult.extensionFunctions.groupBy { it.receiverType!! }
        val supertypeCache = mutableMapOf<String, List<String>>()

        mergeCompanionMembers(allTypes)

        val entries = mutableListOf<DenormalizedEntry>()

        for ((qualName, typeInfo) in allTypes) {
            if (typeInfo.kind == TypeKind.COMPANION_OBJECT) continue

            val allSupertypes = getAllSupertypes(qualName, allTypes, supertypeCache)

            val memberMap = LinkedHashMap<MemberKey, MemberInfo>()

            for (m in typeInfo.declaredMembers) memberMap.putIfAbsent(memberKey(m), m)
            for (m in extensionsByReceiver[qualName].orEmpty()) memberMap.putIfAbsent(memberKey(m), m)

            for (superName in allSupertypes) {
                for (m in allTypes[superName]?.declaredMembers.orEmpty()) {
                    memberMap.putIfAbsent(memberKey(m), m)
                }
                for (m in extensionsByReceiver[superName].orEmpty()) {
                    memberMap.putIfAbsent(memberKey(m), m)
                }
            }

            for ((_, m) in memberMap) {
                entries.add(toDenormalizedEntry(typeInfo, m))
            }
        }

        return entries
    }

    private fun mergeCompanionMembers(types: MutableMap<String, TypeInfo>) {
        val companions = types.values.filter { it.kind == TypeKind.COMPANION_OBJECT }
        for (companion in companions) {
            val outerQualName = companion.qualifiedName.substringBeforeLast('.')
            val outer = types[outerQualName] ?: continue
            outer.declaredMembers.addAll(companion.declaredMembers)
        }
    }

    private fun getAllSupertypes(
        qualifiedName: String,
        allTypes: Map<String, TypeInfo>,
        cache: MutableMap<String, List<String>>,
    ): List<String> {
        cache[qualifiedName]?.let { return it }

        val result = mutableListOf<String>()
        val visited = mutableSetOf(qualifiedName)
        val queue = ArrayDeque<String>()
        queue.addAll(allTypes[qualifiedName]?.supertypes.orEmpty())

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            result.add(current)
            queue.addAll(allTypes[current]?.supertypes.orEmpty())
        }

        cache[qualifiedName] = result
        return result
    }

    private fun buildCompleteTypeMap(parseResult: ParseResult): MutableMap<String, TypeInfo> {
        val types = parseResult.types.toMutableMap()

        // Create minimal entries for extension receivers still missing (e.g. Java types like File, Path)
        val allReceivers = parseResult.extensionFunctions.mapNotNull { it.receiverType }.toSet()
        for (qualName in allReceivers) {
            if (qualName in types) continue
            types[qualName] = TypeInfo(
                name = qualName.substringAfterLast('.'),
                qualifiedName = qualName,
                packageName = qualName.substringBeforeLast('.', ""),
                supertypes = listOf("kotlin.Any"),
                typeParameters = emptyList(),
                kind = TypeKind.CLASS,
            )
        }

        return types
    }

    // --- Deduplication ---

    private data class MemberKey(val name: String, val kind: MK, val paramTypes: List<String>)

    private fun memberKey(m: MemberInfo): MemberKey =
        MemberKey(m.name, m.kind, m.params.map { it.type })

    // --- Conversion ---

    private fun toDenormalizedEntry(ownerType: TypeInfo, m: MemberInfo): DenormalizedEntry {
        val kind = when {
            m.kind == MK.CONSTRUCTOR -> "constructor"
            m.kind == MK.PROPERTY -> if (m.receiverType != null) "extension property" else "property"
            m.receiverType != null -> "extension"
            else -> "function"
        }

        return DenormalizedEntry(
            type = ownerType.name,
            packageName = ownerType.packageName,
            member = m.name,
            kind = kind,
            signature = m.signature,
            returnType = m.returnType,
            params = m.params,
            isOperator = m.isOperator,
            operatorSymbol = if (m.isOperator) OPERATOR_SYMBOLS[m.name] else null,
            isInfix = m.isInfix,
            isInline = m.isInline,
            isSuspend = m.isSuspend,
            isDeprecated = m.isDeprecated,
        )
    }

    companion object {
        private val OPERATOR_SYMBOLS = mapOf(
            "plus" to "+",
            "minus" to "-",
            "times" to "*",
            "div" to "/",
            "rem" to "%",
            "unaryPlus" to "+",
            "unaryMinus" to "-",
            "not" to "!",
            "get" to "[]",
            "set" to "[]=",
            "contains" to "in",
            "rangeTo" to "..",
            "rangeUntil" to "..<",
            "compareTo" to "<, >, <=, >=",
            "invoke" to "()",
            "plusAssign" to "+=",
            "minusAssign" to "-=",
            "timesAssign" to "*=",
            "divAssign" to "/=",
            "remAssign" to "%=",
            "inc" to "++",
            "dec" to "--",
            "iterator" to "for loop",
        )
    }
}
