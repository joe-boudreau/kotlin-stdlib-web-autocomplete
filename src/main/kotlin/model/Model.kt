package model

enum class MemberKind { FUNCTION, PROPERTY, CONSTRUCTOR }

enum class TypeKind { CLASS, INTERFACE, ENUM_CLASS, OBJECT, ANNOTATION_CLASS, COMPANION_OBJECT }

data class ParamInfo(
    val name: String,
    val type: String,
    val hasDefault: Boolean = false,
    val isVararg: Boolean = false,
)

data class MemberInfo(
    val name: String,
    val kind: MemberKind,
    val signature: String,
    val receiverType: String? = null,
    val returnType: String,
    val params: List<ParamInfo> = emptyList(),
    val isOperator: Boolean = false,
    val isInfix: Boolean = false,
    val isInline: Boolean = false,
    val isSuspend: Boolean = false,
    val isDeprecated: Boolean = false,
)

data class TypeInfo(
    val name: String,
    val qualifiedName: String,
    val packageName: String,
    val supertypes: List<String>,
    val typeParameters: List<String>,
    val declaredMembers: MutableList<MemberInfo> = mutableListOf(),
    val kind: TypeKind,
)

data class ParseResult(
    val types: Map<String, TypeInfo>,
    val extensionFunctions: List<MemberInfo>,
)
