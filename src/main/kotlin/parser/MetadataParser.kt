package parser

import model.*
import model.MemberKind as MK
import kotlin.metadata.*
import kotlin.metadata.jvm.*
import java.io.File
import java.util.jar.JarFile

class MetadataParser {
    fun parseStdlib(): ParseResult {
        val types = mutableMapOf<String, TypeInfo>()
        val extensionFunctions = mutableListOf<MemberInfo>()

        val jarPath = findStdlibJarPath()
        println("Scanning: $jarPath")
        val jar = JarFile(File(jarPath))
        var classCount = 0
        var skipCount = 0

        for (entry in jar.entries().asSequence()) {
            if (!entry.name.endsWith(".class")) continue
            if (entry.name.startsWith("META-INF/")) continue

            val className = entry.name.removeSuffix(".class").replace('/', '.')
            if (className == "module-info") continue
            // Skip anonymous/synthetic inner classes (Foo$1, Foo$2) but keep named ones (Map$Entry)
            val lastPart = className.substringAfterLast('$', "")
            if (lastPart.isNotEmpty() && lastPart[0].isDigit()) continue

            try {
                val clazz = Class.forName(className, false, Thread.currentThread().contextClassLoader)
                val metadata = clazz.getAnnotation(Metadata::class.java) ?: continue
                val parsed = KotlinClassMetadata.readLenient(metadata)

                when (parsed) {
                    is KotlinClassMetadata.Class -> {
                        val typeInfo = processClass(parsed.kmClass)
                        if (typeInfo != null) {
                            types[typeInfo.qualifiedName] = typeInfo
                        }
                    }
                    is KotlinClassMetadata.FileFacade -> {
                        processPackage(parsed.kmPackage, extensionFunctions)
                    }
                    is KotlinClassMetadata.MultiFileClassPart -> {
                        processPackage(parsed.kmPackage, extensionFunctions)
                    }
                    else -> {}
                }
                classCount++
            } catch (_: Exception) {
                skipCount++
            }
        }

        jar.close()
        println("Processed $classCount class entries, skipped $skipCount")

        // Parse .kotlin_builtins for mapped types (List, Map, String, Int, etc.)
        val builtinsParser = BuiltinsParser()
        val builtinsTypes = builtinsParser.parseBuiltins(jarPath)
        println("Parsed ${builtinsTypes.size} builtin types")

        // Merge: builtins provide mapped types not in metadata; metadata wins if both exist
        for ((qualName, builtinType) in builtinsTypes) {
            if (qualName !in types) {
                types[qualName] = builtinType
            }
        }

        return ParseResult(types, extensionFunctions)
    }

    private fun findStdlibJarPath(): String {
        val url = Unit::class.java.protectionDomain?.codeSource?.location
            ?: throw IllegalStateException("Cannot locate kotlin-stdlib JAR")
        return url.toURI().path
    }

    private fun processClass(kmClass: KmClass): TypeInfo? {
        val qualifiedName = kmClass.name.replace('/', '.')
        val packageName = qualifiedName.substringBeforeLast('.', "")
        val simpleName = kmClass.name.substringAfterLast('/').replace('$', '.')

        if (kmClass.visibility == Visibility.PRIVATE || kmClass.visibility == Visibility.INTERNAL) return null

        val classTypeParams = buildTypeParamMap(kmClass.typeParameters)

        val kind = when (kmClass.kind) {
            ClassKind.INTERFACE -> TypeKind.INTERFACE
            ClassKind.ENUM_CLASS -> TypeKind.ENUM_CLASS
            ClassKind.OBJECT -> TypeKind.OBJECT
            ClassKind.COMPANION_OBJECT -> TypeKind.COMPANION_OBJECT
            ClassKind.ANNOTATION_CLASS -> TypeKind.ANNOTATION_CLASS
            else -> TypeKind.CLASS
        }

        val supertypes = kmClass.supertypes.mapNotNull { type ->
            val classifier = type.classifier as? KmClassifier.Class ?: return@mapNotNull null
            classifier.name.replace('/', '.')
        }

        val typeParamStrings = kmClass.typeParameters.map { tp ->
            renderTypeParameter(tp, classTypeParams)
        }

        val members = mutableListOf<MemberInfo>()
        members.addAll(extractFunctions(kmClass.functions, classTypeParams))
        members.addAll(extractProperties(kmClass.properties, classTypeParams))
        members.addAll(extractConstructors(kmClass.constructors, simpleName, classTypeParams))

        return TypeInfo(
            name = simpleName,
            qualifiedName = qualifiedName,
            packageName = packageName,
            supertypes = supertypes,
            typeParameters = typeParamStrings,
            declaredMembers = members,
            kind = kind,
        )
    }

    private fun processPackage(kmPackage: KmPackage, extensionFunctions: MutableList<MemberInfo>) {
        for (fn in kmPackage.functions) {
            if (fn.visibility == Visibility.PRIVATE || fn.visibility == Visibility.INTERNAL) continue
            val receiver = fn.receiverParameterType ?: continue
            val receiverName = resolveClassifierName(receiver.classifier) ?: continue

            val typeParams = buildTypeParamMap(fn.typeParameters)
            val member = functionToMemberInfo(fn, typeParams, receiverName)
            extensionFunctions.add(member)
        }
    }

    // --- Member extraction ---

    private fun extractFunctions(functions: List<KmFunction>, classTypeParams: Map<Int, String>): List<MemberInfo> {
        return functions
            .filter { it.visibility != Visibility.PRIVATE && it.visibility != Visibility.INTERNAL }
            .map { fn ->
                val allTypeParams = classTypeParams + buildTypeParamMap(fn.typeParameters)
                functionToMemberInfo(fn, allTypeParams, receiverType = null)
            }
    }

    private fun extractProperties(properties: List<KmProperty>, classTypeParams: Map<Int, String>): List<MemberInfo> {
        return properties
            .filter { it.visibility != Visibility.PRIVATE && it.visibility != Visibility.INTERNAL }
            .map { prop ->
                val returnType = renderType(prop.returnType, classTypeParams)
                val keyword = if (prop.isVar) "var" else "val"
                val signature = "$keyword ${prop.name}: $returnType"

                MemberInfo(
                    name = prop.name,
                    kind = MK.PROPERTY,
                    signature = signature,
                    returnType = returnType,
                )
            }
    }

    private fun extractConstructors(
        constructors: List<KmConstructor>,
        className: String,
        classTypeParams: Map<Int, String>,
    ): List<MemberInfo> {
        return constructors
            .filter { it.visibility != Visibility.PRIVATE && it.visibility != Visibility.INTERNAL }
            .map { ctor ->
                val params = ctor.valueParameters.map { vp ->
                    paramToParamInfo(vp, classTypeParams)
                }
                val paramStr = ctor.valueParameters.joinToString(", ") { vp ->
                    renderValueParam(vp, classTypeParams)
                }
                MemberInfo(
                    name = className,
                    kind = MK.CONSTRUCTOR,
                    signature = "constructor($paramStr)",
                    returnType = className,
                    params = params,
                )
            }
    }

    private fun functionToMemberInfo(
        fn: KmFunction,
        typeParams: Map<Int, String>,
        receiverType: String?,
    ): MemberInfo {
        val params = fn.valueParameters.map { vp -> paramToParamInfo(vp, typeParams) }
        val signature = buildFunctionSignature(fn, typeParams)

        return MemberInfo(
            name = fn.name,
            kind = MK.FUNCTION,
            signature = signature,
            receiverType = receiverType,
            returnType = renderType(fn.returnType, typeParams),
            params = params,
            isOperator = fn.isOperator,
            isInfix = fn.isInfix,
            isInline = fn.isInline,
            isSuspend = fn.isSuspend,
        )
    }

    private fun paramToParamInfo(vp: KmValueParameter, typeParams: Map<Int, String>): ParamInfo {
        val type = if (vp.varargElementType != null) {
            renderType(vp.varargElementType!!, typeParams)
        } else {
            renderType(vp.type, typeParams)
        }
        return ParamInfo(
            name = vp.name,
            type = type,
            hasDefault = vp.declaresDefaultValue,
            isVararg = vp.varargElementType != null,
        )
    }

    // --- Signature building ---

    private fun buildFunctionSignature(fn: KmFunction, typeParams: Map<Int, String>): String {
        val sb = StringBuilder()

        if (fn.isInline) sb.append("inline ")
        if (fn.isInfix) sb.append("infix ")
        if (fn.isOperator) sb.append("operator ")
        if (fn.isSuspend) sb.append("suspend ")

        sb.append("fun ")

        if (fn.typeParameters.isNotEmpty()) {
            sb.append("<")
            sb.append(fn.typeParameters.joinToString(", ") { renderTypeParameter(it, typeParams) })
            sb.append("> ")
        }

        fn.receiverParameterType?.let { receiver ->
            sb.append(renderType(receiver, typeParams))
            sb.append(".")
        }

        sb.append(fn.name)
        sb.append("(")
        sb.append(fn.valueParameters.joinToString(", ") { renderValueParam(it, typeParams) })
        sb.append(")")

        val returnType = renderType(fn.returnType, typeParams)
        if (returnType != "Unit") {
            sb.append(": ")
            sb.append(returnType)
        }

        return sb.toString()
    }

    private fun renderValueParam(vp: KmValueParameter, typeParams: Map<Int, String>): String {
        val sb = StringBuilder()
        if (vp.varargElementType != null) sb.append("vararg ")
        sb.append(vp.name)
        sb.append(": ")
        sb.append(renderType(vp.type, typeParams))
        if (vp.declaresDefaultValue) sb.append(" = ...")
        return sb.toString()
    }

    // --- Type rendering ---

    private fun renderType(type: KmType, typeParams: Map<Int, String>): String {
        val base = when (val classifier = type.classifier) {
            is KmClassifier.Class -> {
                val name = classifier.name
                if (isFunctionType(name)) {
                    return renderFunctionType(type, typeParams, isSuspend = name.contains("SuspendFunction"))
                }
                simpleName(name)
            }
            is KmClassifier.TypeParameter -> typeParams[classifier.id] ?: "T${classifier.id}"
            is KmClassifier.TypeAlias -> simpleName(classifier.name)
        }

        val args = if (type.arguments.isNotEmpty()) {
            "<" + type.arguments.joinToString(", ") { renderProjection(it, typeParams) } + ">"
        } else ""

        val nullable = if (type.isNullable) "?" else ""
        return "$base$args$nullable"
    }

    private fun renderFunctionType(type: KmType, typeParams: Map<Int, String>, isSuspend: Boolean): String {
        val args = type.arguments
        if (args.isEmpty()) {
            val base = if (isSuspend) "suspend () -> Unit" else "() -> Unit"
            return if (type.isNullable) "($base)?" else base
        }

        val paramTypes = args.dropLast(1).joinToString(", ") { proj ->
            proj.type?.let { renderType(it, typeParams) } ?: "*"
        }
        val returnType = args.last().type?.let { renderType(it, typeParams) } ?: "Unit"

        val prefix = if (isSuspend) "suspend " else ""
        val base = "$prefix($paramTypes) -> $returnType"
        return if (type.isNullable) "($base)?" else base
    }

    private fun renderProjection(proj: KmTypeProjection, typeParams: Map<Int, String>): String {
        val type = proj.type ?: return "*"
        val prefix = when (proj.variance) {
            KmVariance.IN -> "in "
            KmVariance.OUT -> "out "
            else -> ""
        }
        return prefix + renderType(type, typeParams)
    }

    private fun renderTypeParameter(tp: KmTypeParameter, allTypeParams: Map<Int, String>): String {
        val variance = when (tp.variance) {
            KmVariance.IN -> "in "
            KmVariance.OUT -> "out "
            else -> ""
        }
        val bounds = tp.upperBounds
            .filter { renderType(it, allTypeParams) != "Any?" }
            .joinToString(", ") { renderType(it, allTypeParams) }
        val boundsStr = if (bounds.isNotEmpty()) " : $bounds" else ""
        return "$variance${tp.name}$boundsStr"
    }

    // --- Utilities ---

    private fun buildTypeParamMap(typeParameters: List<KmTypeParameter>): Map<Int, String> =
        typeParameters.associate { it.id to it.name }

    private fun simpleName(internalName: String): String =
        internalName.substringAfterLast('/').replace('$', '.')

    private fun resolveClassifierName(classifier: KmClassifier): String? = when (classifier) {
        is KmClassifier.Class -> classifier.name.replace('/', '.')
        else -> null
    }

    private val FUNCTION_TYPE_REGEX = Regex("kotlin/Function\\d+")
    private val SUSPEND_FUNCTION_TYPE_REGEX = Regex("kotlin/coroutines/SuspendFunction\\d+")

    private fun isFunctionType(name: String): Boolean =
        FUNCTION_TYPE_REGEX.matches(name) || SUSPEND_FUNCTION_TYPE_REGEX.matches(name)
}
