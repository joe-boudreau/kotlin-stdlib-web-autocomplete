package parser

import model.*
import model.MemberKind as MK
import java.io.File
import java.util.jar.JarFile

class BuiltinsParser {

    fun parseBuiltins(jarPath: String): Map<String, TypeInfo> {
        val jar = JarFile(File(jarPath))
        val types = mutableMapOf<String, TypeInfo>()

        val builtinsEntries = jar.entries().asSequence()
            .filter { it.name.endsWith(".kotlin_builtins") }
            .toList()

        for (entry in builtinsEntries) {
            val result = BuiltinsReader.readBuiltins(jar.getInputStream(entry))

            for (cls in result.classes()) {
                val rawName = cls.qualifiedName() // e.g. "kotlin/collections.List"
                val qualifiedName = normalizeQualifiedName(rawName)
                val packageName = qualifiedName.substringBeforeLast('.', "")
                val simpleName = qualifiedName.substringAfterLast('.')
                    .let { if (it.contains('.')) it else it } // preserve nested like Map.Entry

                val typeParamMap = cls.typeParams().associate { it.id() to it.name() }

                val kind = inferKind(cls.flags())
                val typeParamStrings = cls.typeParams().map { tp ->
                    buildString {
                        when (tp.variance()) {
                            "IN" -> append("in ")
                            "OUT" -> append("out ")
                            else -> {}
                        }
                        append(tp.name())
                        val bounds = tp.upperBounds()
                            .filter { renderBuiltinType(it, typeParamMap) != "Any?" }
                            .map { renderBuiltinType(it, typeParamMap) }
                        if (bounds.isNotEmpty()) {
                            append(" : ${bounds.joinToString(", ")}")
                        }
                    }
                }

                val supertypes = (cls.supertypeNames() ?: emptyList<String>()).map { normalizeQualifiedName(it) }

                val members = mutableListOf<MemberInfo>()

                for (fn in cls.functions()) {
                    val fnTypeParamMap = typeParamMap.toMutableMap()
                    // Function-level type params get IDs after class-level ones
                    // But we can just use names since they're resolved in the Java bridge
                    val member = convertFunction(fn, fnTypeParamMap)
                    if (member != null) members.add(member)
                }

                for (prop in cls.properties()) {
                    val member = convertProperty(prop, typeParamMap)
                    if (member != null) members.add(member)
                }

                // Determine actual simple name for nested types
                val displayName = when {
                    simpleName.contains('.') -> simpleName // Map.Entry stays as Map.Entry
                    else -> simpleName
                }

                types[qualifiedName] = TypeInfo(
                    name = displayName,
                    qualifiedName = qualifiedName,
                    packageName = packageName,
                    supertypes = supertypes,
                    typeParameters = typeParamStrings,
                    declaredMembers = members,
                    kind = kind,
                )
            }
        }

        jar.close()
        return types
    }

    private fun convertFunction(fn: BuiltinsReader.BuiltinFunction, typeParamMap: Map<Int, String>): MemberInfo? {
        val name = fn.name()
        val flags = fn.flags()
        if (isPrivateOrInternal(flags)) return null

        val params = fn.params().map { p ->
            ParamInfo(
                name = p.name(),
                type = renderBuiltinType(p.type(), typeParamMap),
                hasDefault = p.hasDefault(),
                isVararg = p.varargType() != null,
            )
        }

        val returnType = renderBuiltinType(fn.returnType(), typeParamMap)
        val isOperator = (flags and (1 shl 8)) != 0
        val isInfix = (flags and (1 shl 9)) != 0
        val isInline = (flags and (1 shl 10)) != 0
        val isSuspend = (flags and (1 shl 12)) != 0

        val fnTypeParams = fn.typeParams() ?: emptyList()
        val signature = buildString {
            if (isInline) append("inline ")
            if (isInfix) append("infix ")
            if (isOperator) append("operator ")
            if (isSuspend) append("suspend ")
            append("fun ")
            if (fnTypeParams.isNotEmpty()) {
                append("<${fnTypeParams.joinToString(", ")}> ")
            }
            if (fn.receiverType() != null) {
                append("${renderBuiltinType(fn.receiverType(), typeParamMap)}.")
            }
            append("$name(")
            append(params.joinToString(", ") { p ->
                val vararg = if (p.isVararg) "vararg " else ""
                val default = if (p.hasDefault) " = ..." else ""
                "$vararg${p.name}: ${p.type}$default"
            })
            append(")")
            if (returnType != "Unit") append(": $returnType")
        }

        return MemberInfo(
            name = name,
            kind = MK.FUNCTION,
            signature = signature,
            returnType = returnType,
            params = params,
            isOperator = isOperator,
            isInfix = isInfix,
            isInline = isInline,
            isSuspend = isSuspend,
        )
    }

    private fun convertProperty(prop: BuiltinsReader.BuiltinProperty, typeParamMap: Map<Int, String>): MemberInfo? {
        val flags = prop.flags()
        if (isPrivateOrInternal(flags)) return null

        val name = prop.name()
        val returnType = renderBuiltinType(prop.returnType(), typeParamMap)
        val keyword = if (prop.isVar()) "var" else "val"

        return MemberInfo(
            name = name,
            kind = MK.PROPERTY,
            signature = "$keyword $name: $returnType",
            returnType = returnType,
        )
    }

    private fun renderBuiltinType(type: BuiltinsReader.BuiltinType?, typeParamMap: Map<Int, String>): String {
        if (type == null) return "Unit"

        val rawName = type.className()
        val name = when {
            type.typeParamId() >= 0 -> typeParamMap[type.typeParamId()] ?: rawName
            rawName.contains('/') -> {
                // e.g. "kotlin/collections.List" → "List"
                val afterSlash = rawName.substringAfterLast('/')
                val afterDot = afterSlash.substringAfterLast('.')
                afterDot
            }
            rawName.contains('.') -> rawName.substringAfterLast('.')
            else -> rawName
        }

        val args = if (type.arguments().isNotEmpty()) {
            "<" + type.arguments().joinToString(", ") { arg ->
                if (arg.variance() == "STAR") "*"
                else {
                    val prefix = when (arg.variance()) {
                        "IN" -> "in "
                        "OUT" -> "out "
                        else -> ""
                    }
                    prefix + renderBuiltinType(arg.type(), typeParamMap)
                }
            } + ">"
        } else ""

        val nullable = if (type.nullable()) "?" else ""
        return "$name$args$nullable"
    }

    private fun normalizeQualifiedName(raw: String): String {
        // "kotlin/collections.List" → "kotlin.collections.List"
        // "kotlin.Any" → "kotlin.Any"
        return raw.replace('/', '.')
    }

    private fun inferKind(flags: Int): TypeKind {
        val kindBits = (flags shr 1) and 0x07
        return when (kindBits) {
            0 -> TypeKind.CLASS
            1 -> TypeKind.INTERFACE
            2 -> TypeKind.ENUM_CLASS
            3 -> TypeKind.OBJECT // ENUM_ENTRY
            4 -> TypeKind.ANNOTATION_CLASS
            5 -> TypeKind.OBJECT
            6 -> TypeKind.COMPANION_OBJECT
            else -> TypeKind.CLASS
        }
    }

    private fun isPrivateOrInternal(flags: Int): Boolean {
        val visibility = (flags shr 4) and 0x07
        // 0=INTERNAL, 1=PRIVATE, 2=PROTECTED, 3=PUBLIC, 4=PRIVATE_TO_THIS, 5=LOCAL
        return visibility == 0 || visibility == 1 || visibility == 4 || visibility == 5
    }
}
