# Kotlin Stdlib Autocomplete — Project Context

## What This Is

A web-based autocomplete and method documentation reference for the Kotlin standard library. The primary use case is programming interviews where you can use online materials but not your own IDE. It replicates the IntelliJ autocomplete + quick-doc experience in a browser: pick a type, fuzzy-search methods, see signatures and docs instantly.

## Architecture

Fully static frontend. No backend, no database, no API.

```
[One-time build step]
  Kotlin stdlib → parsing script → methods.json

[Deploy]
  Static site: index.html + methods.json

[Runtime]
  fetch methods.json → in-memory array → filter/search → render
```

The dataset is small enough (~50 core types, ~1500-2000 methods, <2MB JSON) to load entirely into the browser and search client-side. Prefix and fuzzy matching on this size is instant in JS.

## Data Sources

Two sources, combined:

### 1. `kotlin-metadata-jvm` — structural data (primary)

JetBrains' own library for reading `@Metadata` annotations from compiled `.class` files. Stable since Kotlin 2.0, ships as `org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion`.

Use this for:
- Class names, supertypes (inheritance graph), type parameters
- Member functions and properties (names, signatures, visibility, modality)
- Extension functions (via `FileFacade` metadata — `kmPackage.functions` where each `KmFunction` has a `receiverParameterType`)
- Operator flags, inline/infix modifiers, deprecation status
- Constructors

Key API surface:
- `KotlinClassMetadata.readStrict(metadataAnnotation)` → parse a `.class` file's metadata
- `KotlinClassMetadata.Class` → access `kmClass.functions`, `kmClass.properties`, `kmClass.supertypes`, `kmClass.typeParameters`, `kmClass.constructors`
- `KotlinClassMetadata.FileFacade` → access `kmPackage.functions` (this is where top-level and extension functions live)
- `KmFunction.receiverParameterType` → tells you which type an extension function extends
- `KmClass.supertypes` → `MutableList<KmType>` for building the inheritance graph

Approach: load `kotlin-stdlib.jar`, iterate `.class` files (using ASM's `ClassReader` or classloader reflection), read the `@Metadata` annotation from each, parse with `KotlinClassMetadata.readStrict()`.

**Limitation:** no KDoc documentation strings. Metadata only has structural info.

### 2. Kotlin stdlib source (GitHub) — KDoc descriptions

The stdlib source at `github.com/JetBrains/kotlin/tree/master/libraries/stdlib` contains inline KDoc comments. Many collection extensions are auto-generated (in `generated/_Collections.kt` etc.) but the generated files include full KDoc.

Join docs to structural data by matching on function name + parameter types.

## Data Model

Fully denormalized. Each entry is self-contained — no hierarchy resolution at query time.

```json
{
  "type": "String",
  "package": "kotlin",
  "member": "substringAfter",
  "kind": "extension",
  "signature": "fun String.substringAfter(delimiter: String, missingDelimiterValue: String = this): String",
  "summary": "Returns the substring after the first occurrence of delimiter.",
  "description": "If the string does not contain the delimiter, returns missingDelimiterValue which defaults to the original string.",
  "params": [
    { "name": "delimiter", "type": "String", "doc": "..." },
    { "name": "missingDelimiterValue", "type": "String", "doc": "..." }
  ],
  "returnType": "String",
  "since": "1.0"
}
```

If `filter` is declared on `Iterable` and `List` extends `Iterable`, duplicate the entry under both `"type": "Iterable"` and `"type": "List"`. Every type should list every method you can actually call on it.

## Inheritance Resolution (Parsing Script)

The parsing script must:

1. Parse every type and its declared members from metadata
2. Build the inheritance graph using `KmClass.supertypes`
3. Collect extension functions from `FileFacade` classes, bucketing by `receiverParameterType`
4. For each type, walk up the supertype chain and collect all inherited members + all extension functions whose receiver is that type or any of its supertypes
5. Emit a flat denormalized list

### Things to handle:
- **Extension functions** — not part of class hierarchy; declared in `FileFacade` metadata with a `receiverParameterType`. Attach to the receiver type AND all subtypes.
- **Operator overloading** — flag operators (e.g. `get` → `[]`, `plus` → `+`, `contains` → `in`) in the data so the UI can show both forms.
- **Member vs extension name collisions** — keep both as separate entries.
- **Deprecation** — flag deprecated methods, de-prioritize in search.

### Things to ignore (for now):
- Java interop / mapped Java methods (just Kotlin stdlib)
- Concrete generic type resolution (preserve signatures as-is)
- Internal/private members

## Scope

Start with these packages:
- `kotlin.collections` (List, MutableList, Map, MutableMap, Set, Sequence, etc.)
- `kotlin.text` (String, Char, Regex, StringBuilder)
- `kotlin` (core types: Any, Int, Boolean, etc. + scope functions)
- `kotlin.io` (File extensions, buffered readers, etc.)
- `kotlin.ranges`
- `kotlin.sequences`
- `kotlin.comparisons`

This covers ~90%+ of interview use cases. Expand later as needed.

## Frontend

- Two-mode search: filter by type, then fuzzy-search methods within that type. Or a single input that parses `List.filt` as type=List, query=filt.
- Show signature + one-line summary inline, full doc on expand.
- No framework requirement — could be plain JS, or a lightweight framework. Keep it fast.
- Host statically (GitHub Pages, S3+CloudFront, Vercel, etc.)

## Tech Stack

- **Parsing script:** Kotlin (using `kotlin-metadata-jvm`, ASM for classfile reading)
- **Output:** Single `methods.json` file
- **Frontend:** Static HTML/JS/CSS
