package parser;

import kotlin.Pair;
import kotlin.metadata.internal.metadata.ProtoBuf;
import kotlin.metadata.internal.metadata.builtins.BuiltInsBinaryVersion;
import kotlin.metadata.internal.metadata.builtins.ReadPackageFragmentKt;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Java bridge to read .kotlin_builtins files.
 * Kotlin blocks access to kotlin.metadata.internal — Java does not.
 * This class extracts structured data into plain Java records that Kotlin can consume.
 */
public class BuiltinsReader {

    public record BuiltinType(String className, List<BuiltinTypeArg> arguments, boolean nullable, int typeParamId) {}
    public record BuiltinTypeArg(BuiltinType type, String variance) {} // variance: IN, OUT, INVARIANT, or STAR
    public record BuiltinParam(String name, BuiltinType type, boolean hasDefault, BuiltinType varargType) {}
    public record BuiltinFunction(String name, List<BuiltinParam> params, BuiltinType returnType,
                                  List<String> typeParams, BuiltinType receiverType, int flags) {}
    public record BuiltinProperty(String name, BuiltinType returnType, boolean isVar, int flags) {}
    public record BuiltinConstructor(List<BuiltinParam> params, int flags) {}
    public record BuiltinTypeParam(String name, int id, String variance, List<BuiltinType> upperBounds) {}
    public record BuiltinClass(String qualifiedName, List<String> supertypeNames, List<BuiltinType> supertypes,
                               List<BuiltinTypeParam> typeParams, List<BuiltinFunction> functions,
                               List<BuiltinProperty> properties, List<BuiltinConstructor> constructors, int flags) {}
    public record BuiltinsResult(List<BuiltinClass> classes) {}

    public static BuiltinsResult readBuiltins(InputStream stream) {
        Pair<ProtoBuf.PackageFragment, BuiltInsBinaryVersion> pair =
                ReadPackageFragmentKt.readBuiltinsPackageFragment(stream);
        ProtoBuf.PackageFragment fragment = pair.getFirst();

        var strings = fragment.getStrings();
        var qualNames = fragment.getQualifiedNames();

        var classes = new ArrayList<BuiltinClass>();

        for (int i = 0; i < fragment.getClass_Count(); i++) {
            var cls = fragment.getClass_(i);
            var typeTable = cls.hasTypeTable() ? cls.getTypeTable() : null;

            String className = resolveQualifiedName(cls.getFqName(), strings, qualNames);

            // Type parameters
            var typeParams = new ArrayList<BuiltinTypeParam>();
            for (int j = 0; j < cls.getTypeParameterCount(); j++) {
                var tp = cls.getTypeParameter(j);
                var upperBounds = new ArrayList<BuiltinType>();
                for (int k = 0; k < tp.getUpperBoundCount(); k++) {
                    upperBounds.add(convertType(tp.getUpperBound(k), strings, qualNames, typeTable, cls));
                }
                String variance = switch (tp.getVariance()) {
                    case IN -> "IN";
                    case OUT -> "OUT";
                    case INV -> "INVARIANT";
                };
                typeParams.add(new BuiltinTypeParam(strings.getString(tp.getName()), tp.getId(), variance, upperBounds));
            }

            // Supertypes
            var supertypes = new ArrayList<BuiltinType>();
            var supertypeNames = new ArrayList<String>();
            for (int j = 0; j < cls.getSupertypeCount(); j++) {
                var st = convertType(cls.getSupertype(j), strings, qualNames, typeTable, cls);
                supertypes.add(st);
                supertypeNames.add(st.className());
            }
            // Supertypes by ID (via supertypeIdList)
            for (int stId : cls.getSupertypeIdList()) {
                if (typeTable != null) {
                    var st = convertType(typeTable.getType(stId), strings, qualNames, typeTable, cls);
                    supertypes.add(st);
                    supertypeNames.add(st.className());
                }
            }

            // Functions
            var functions = new ArrayList<BuiltinFunction>();
            for (int j = 0; j < cls.getFunctionCount(); j++) {
                var fn = cls.getFunction(j);
                var fnTypeTable = fn.hasTypeTable() ? fn.getTypeTable() : typeTable;
                functions.add(convertFunction(fn, strings, qualNames, fnTypeTable, cls));
            }

            // Properties
            var properties = new ArrayList<BuiltinProperty>();
            for (int j = 0; j < cls.getPropertyCount(); j++) {
                var prop = cls.getProperty(j);
                BuiltinType propType = prop.hasReturnType()
                        ? convertType(prop.getReturnType(), strings, qualNames, typeTable, cls)
                        : prop.hasReturnTypeId() && typeTable != null
                            ? convertType(typeTable.getType(prop.getReturnTypeId()), strings, qualNames, typeTable, cls)
                            : null;
                boolean isVar = (prop.getFlags() & 0x04) != 0; // approximate var flag
                properties.add(new BuiltinProperty(strings.getString(prop.getName()), propType,
                        isVar, prop.getFlags()));
            }

            // Constructors
            var constructors = new ArrayList<BuiltinConstructor>();
            for (int j = 0; j < cls.getConstructorCount(); j++) {
                var ctor = cls.getConstructor(j);
                var params = convertParams(ctor.getValueParameterList(), strings, qualNames, typeTable, cls);
                constructors.add(new BuiltinConstructor(params, ctor.getFlags()));
            }

            classes.add(new BuiltinClass(className, supertypeNames, supertypes, typeParams,
                    functions, properties, constructors, cls.getFlags()));
        }

        return new BuiltinsResult(classes);
    }

    private static BuiltinFunction convertFunction(ProtoBuf.Function fn, ProtoBuf.StringTable strings,
                                                    ProtoBuf.QualifiedNameTable qualNames,
                                                    ProtoBuf.TypeTable typeTable, ProtoBuf.Class cls) {
        String name = strings.getString(fn.getName());

        // Type params
        var typeParamNames = new ArrayList<String>();
        for (int k = 0; k < fn.getTypeParameterCount(); k++) {
            typeParamNames.add(strings.getString(fn.getTypeParameter(k).getName()));
        }

        // Params
        var params = convertParams(fn.getValueParameterList(), strings, qualNames, typeTable, cls);

        // Return type
        BuiltinType returnType = fn.hasReturnType()
                ? convertType(fn.getReturnType(), strings, qualNames, typeTable, cls)
                : fn.hasReturnTypeId() && typeTable != null
                    ? convertType(typeTable.getType(fn.getReturnTypeId()), strings, qualNames, typeTable, cls)
                    : null;

        // Receiver type
        BuiltinType receiverType = fn.hasReceiverType()
                ? convertType(fn.getReceiverType(), strings, qualNames, typeTable, cls)
                : fn.hasReceiverTypeId() && typeTable != null
                    ? convertType(typeTable.getType(fn.getReceiverTypeId()), strings, qualNames, typeTable, cls)
                    : null;

        return new BuiltinFunction(name, params, returnType, typeParamNames, receiverType, fn.getFlags());
    }

    private static List<BuiltinParam> convertParams(List<ProtoBuf.ValueParameter> vpList,
                                                     ProtoBuf.StringTable strings,
                                                     ProtoBuf.QualifiedNameTable qualNames,
                                                     ProtoBuf.TypeTable typeTable, ProtoBuf.Class cls) {
        var params = new ArrayList<BuiltinParam>();
        for (var vp : vpList) {
            BuiltinType vpType = vp.hasType()
                    ? convertType(vp.getType(), strings, qualNames, typeTable, cls)
                    : vp.hasTypeId() && typeTable != null
                        ? convertType(typeTable.getType(vp.getTypeId()), strings, qualNames, typeTable, cls)
                        : null;
            BuiltinType varargType = vp.hasVarargElementType()
                    ? convertType(vp.getVarargElementType(), strings, qualNames, typeTable, cls)
                    : vp.hasVarargElementTypeId() && typeTable != null
                        ? convertType(typeTable.getType(vp.getVarargElementTypeId()), strings, qualNames, typeTable, cls)
                        : null;
            boolean hasDefault = (vp.getFlags() & 0x02) != 0; // DECLARES_DEFAULT_VALUE flag
            params.add(new BuiltinParam(strings.getString(vp.getName()), vpType, hasDefault, varargType));
        }
        return params;
    }

    private static BuiltinType convertType(ProtoBuf.Type type, ProtoBuf.StringTable strings,
                                            ProtoBuf.QualifiedNameTable qualNames,
                                            ProtoBuf.TypeTable typeTable, ProtoBuf.Class cls) {
        String className = "";
        int typeParamId = -1;

        if (type.hasClassName()) {
            className = resolveQualifiedName(type.getClassName(), strings, qualNames);
        } else if (type.hasTypeParameter()) {
            typeParamId = type.getTypeParameter();
            // Resolve name from class type parameters
            for (int i = 0; i < cls.getTypeParameterCount(); i++) {
                if (cls.getTypeParameter(i).getId() == typeParamId) {
                    className = strings.getString(cls.getTypeParameter(i).getName());
                    break;
                }
            }
            if (className.isEmpty()) className = "T" + typeParamId;
        } else if (type.hasTypeParameterName()) {
            className = strings.getString(type.getTypeParameterName());
        }

        var args = new ArrayList<BuiltinTypeArg>();
        for (int i = 0; i < type.getArgumentCount(); i++) {
            var arg = type.getArgument(i);
            if (arg.hasType()) {
                String variance = switch (arg.getProjection()) {
                    case IN -> "IN";
                    case OUT -> "OUT";
                    case INV -> "INVARIANT";
                    case STAR -> "STAR";
                };
                args.add(new BuiltinTypeArg(convertType(arg.getType(), strings, qualNames, typeTable, cls), variance));
            } else {
                args.add(new BuiltinTypeArg(null, "STAR"));
            }
        }

        boolean nullable = type.hasNullable() && type.getNullable();
        return new BuiltinType(className, args, nullable, typeParamId);
    }

    private static String resolveQualifiedName(int id, ProtoBuf.StringTable strings,
                                                ProtoBuf.QualifiedNameTable qualNames) {
        var qn = qualNames.getQualifiedName(id);
        String shortName = strings.getString(qn.getShortName());
        String separator = qn.getKind() == ProtoBuf.QualifiedNameTable.QualifiedName.Kind.CLASS ? "." : "/";
        if (qn.hasParentQualifiedName() && qn.getParentQualifiedName() >= 0) {
            return resolveQualifiedName(qn.getParentQualifiedName(), strings, qualNames) + separator + shortName;
        }
        return shortName;
    }
}
