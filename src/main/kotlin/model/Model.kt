package model

import kotlinx.serialization.Serializable

enum class MemberKind { FUNCTION, PROPERTY, CONSTRUCTOR }

enum class TypeKind { CLASS, INTERFACE, ENUM_CLASS, OBJECT, ANNOTATION_CLASS, COMPANION_OBJECT }

@Serializable
data class ParamInfo(
    val name: String,
    val type: String,
    val hasDefault: Boolean = false,
    val isVararg: Boolean = false,
    val doc: String = "",
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

@Serializable
data class DenormalizedEntry(
    val type: String,
    val packageName: String,
    val member: String,
    val kind: String,
    val signature: String,
    val returnType: String,
    val params: List<ParamInfo> = emptyList(),
    val isOperator: Boolean = false,
    val operatorSymbol: String? = null,
    val isInfix: Boolean = false,
    val isInline: Boolean = false,
    val isSuspend: Boolean = false,
    val isDeprecated: Boolean = false,
    val summary: String = "",
    val description: String = "",
    val since: String = "",
)
