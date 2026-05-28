# Kotlin Stdlib Search

A browser-based autocomplete and documentation reference for the entire Kotlin standard library. Built for programming interviews where you can use online materials but not your own IDE. Replicates the IntelliJ autocomplete + quick-doc experience in a browser.

## Architecture

```
[Build step — ./gradlew run]
  kotlin-stdlib.jar ──→ MetadataParser ──→ ParseResult (types + extensions)
  .kotlin_builtins   ──→ BuiltinsParser ──┘
                                          ↓
                         InheritanceResolver → denormalized entries
                                          ↓
  kotlin-stdlib-sources.jar → KDocParser → DocMerger → entries with docs
                                          ↓
                                     methods-<v>.json.gz (one per version, ~620KB each)

[Deploy — static site]
  frontend/index.html + frontend/data/methods-<v>.json.gz + frontend/data/versions.json

[Runtime]
  fetch versions.json → fetch+gunzip methods-<v>.json.gz → in-memory array → fuzzy search → render
```

## How to Build

```bash
./gradlew generateAll            # generates frontend/data/methods-<version>.json.gz for all versions + versions.json
./gradlew run                    # single-version: methods.json in project root (build's own stdlib)
```

`generateAll` loops the versions in `stdlibVersions` (build.gradle.kts). For each it resolves `kotlin-stdlib:<v>` (binary), `kotlin-stdlib:<v>:sources`, and `kotlin-stdlib-common:<v>:sources` via detached configurations, then runs `MainKt <binary-jar> <output-path> <sources-jar>...`. The common-sources jar is needed for pre-2.0 versions, which kept generated extension sources (`_Collections.kt`, `_Strings.kt`, …) in `kotlin-stdlib-common`; 2.x merged them into the main sources jar.

The parser reads the `@Metadata` annotation straight from `.class` bytes via ASM (`MetadataAnnotationReader`), so it can parse any version's jar regardless of the build's own classpath. `kotlin-metadata-jvm` must be pinned ≥ the newest version parsed (currently 2.3.21) — readers are backward-compatible with older metadata.

To serve locally: `cd frontend && python3 -m http.server 8090`

## Project Structure

```
src/main/kotlin/
  Main.kt                          — entry point, orchestrates parse → resolve → merge → serialize
  model/Model.kt                   — data classes (ParamInfo, MemberInfo, TypeInfo, DenormalizedEntry)
  parser/MetadataParser.kt          — scans kotlin-stdlib.jar, reads @Metadata from .class files
  parser/BuiltinsParser.kt          — reads .kotlin_builtins protobuf files for mapped types
  parser/InheritanceResolver.kt     — walks supertype graph, propagates extensions, deduplicates
  docs/KDocParser.kt                — parses KDoc from kotlin-stdlib-sources.jar .kt files
  docs/DocMerger.kt                 — matches KDoc entries to denormalized entries by name + params

src/main/java/
  parser/BuiltinsReader.java        — Java bridge to read .kotlin_builtins protobuf (Kotlin blocks
                                      access to kotlin.metadata.internal.* packages, Java doesn't)

frontend/
  index.html                        — single-page app
  style.css                         — Darcula-inspired dark theme, JetBrains Mono + IBM Plex Sans
  app.js                            — search, fuzzy matching, keyboard nav, KDoc rendering
  methods.json                      — generated data file (not checked in, ~7MB)
  kotlin-icon.png                   — Kotlin logo for branding
```

## Data Pipeline Details

### 1. MetadataParser
Iterates every `.class` in kotlin-stdlib.jar, reads `@Metadata` annotations via `Class.forName`, parses with `KotlinClassMetadata.readLenient()`. Handles `Class`, `FileFacade`, and `MultiFileClassPart` metadata types. Extracts types with declared members and extension functions with receiver types. Filters out private/internal visibility. No package filtering — covers the entire stdlib.

### 2. BuiltinsParser
Reads `.kotlin_builtins` protobuf files from the stdlib JAR for **mapped types** (List, Map, String, Int, etc.) that don't have `@Metadata` annotations because they're compiled to JVM primitives/interfaces. Uses `BuiltinsReader.java` as a bridge since Kotlin restricts access to `kotlin.metadata.internal.*` packages. Extracts class declarations, member functions, properties, supertypes, and type parameters.

Currently parses ~135 builtin types including:
- All primitive types (Int, Long, Double, etc.) with arithmetic operators
- Collection interfaces (List, MutableList, Map, Set, Iterator, etc.)
- CharSequence, String, Comparable, Number, Array, primitive arrays
- Reflection types (KClass, KProperty, etc.)

### 3. InheritanceResolver
Takes the combined types map and extension function list, then:
- Merges companion object members into their parent types
- Creates stub TypeInfo for extension receivers not in any parsed data (e.g. Java types like File, Path)
- Walks supertypes via BFS, collecting inherited members + applicable extensions
- Deduplicates by (name, kind, paramTypes), prioritizing more specific definitions
- Maps operator names to symbolic forms (get→[], plus→+, contains→in, etc.)

Output: flat list of `DenormalizedEntry` — every type lists every method callable on it.

### 4. KDocParser
Parses `.kt` source files from `kotlin-stdlib-sources.jar`. Tracks enclosing class/interface scope to associate KDoc for members declared inside type bodies (critical for builtin interfaces like `List`, `Map`). Extracts summary (first sentence/paragraph), full description, @param docs, @return, @since tags.

### 5. DocMerger
Matches KDoc entries to denormalized entries using a multi-step strategy:
1. Exact: receiver type from signature + member name + param count
2. Entry's owning type as receiver (for inherited extensions)
3. Null receiver with param-name validation (class-declared members)
4. Name + param count with param-name matching (cross-type fallback)

Current coverage: ~86% of entries have documentation.

## Data Model (methods.json)

Each entry in the JSON array:

```json
{
  "type": "List",
  "packageName": "kotlin.collections",
  "member": "filter",
  "kind": "extension",
  "signature": "inline fun <T> Iterable<T>.filter(predicate: (T) -> Boolean): List<T>",
  "returnType": "List<T>",
  "params": [{"name": "predicate", "type": "(T) -> Boolean", "doc": "..."}],
  "isOperator": true,
  "operatorSymbol": "[]",
  "isInline": true,
  "isInfix": true,
  "isSuspend": true,
  "isDeprecated": true,
  "summary": "Returns a list containing only elements matching the given predicate.",
  "description": "Full KDoc description with markdown...",
  "since": "1.0"
}
```

Boolean/optional fields use `encodeDefaults = false` — they only appear in the JSON when true/non-empty.

## Frontend

Plain HTML/CSS/JS, no framework. Dark theme inspired by IntelliJ Darcula.

Key features:
- **Two-mode search**: `List.filter` (type-scoped), `map` (global), `List.` (all members)
- **Fuzzy matching** with scoring (prefix > consecutive chars > sparse match)
- **Keyboard navigation**: ↑↓ arrows, Enter to expand, Escape to clear
- **KDoc rendering**: converts KDoc markdown to HTML (inline code, code blocks, bold, symbol links)
- **Type bar**: clickable chips for matching types
- **Operator symbols** shown alongside method names
- **URL hash state**: `#List.filter` is bookmarkable

## Tech Stack

- **Kotlin 2.3.21** with `kotlin-metadata-jvm` for metadata parsing
- **kotlinx-serialization-json** for JSON output
- **ASM 9.10.1** — reads `@Metadata` from `.class` bytes (`MetadataAnnotationReader`) so the parser isn't tied to the build's classpath
- **Gradle 9.3** with JVM toolchain 25
- **Frontend**: vanilla HTML/CSS/JS

## Known Limitations

- ~14% of entries lack KDoc (mostly builtin operator overloads on primitive types like `Int.plus(Long)` — these have many overloads with identical docs in the compiler source but different param type combos that don't match)
- Type parameters on mapped types from builtins sometimes show `*` instead of the resolved type variable (e.g. `Iterator<*>` instead of `Iterator<E>`)
- No Java interop methods (only Kotlin stdlib declarations)
- Per-version datasets are written gzip-compressed as `methods-<v>.json.gz` (~620KB each, down from ~7MB raw) and decompressed in the browser via `DecompressionStream`. The static host must serve `.gz` as an opaque file (no `Content-Encoding: gzip`), which GitHub Pages and `python3 -m http.server` both do; otherwise the browser double-decompresses.
