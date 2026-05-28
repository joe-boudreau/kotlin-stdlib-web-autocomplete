# Kotlin Stdlib Search

A browser-based autocomplete and documentation reference for the entire Kotlin standard library. Built for programming interviews where you can use online materials but not your own IDE. Replicates the IntelliJ autocomplete + quick-doc experience in a browser.

## Architecture

```
[Build step — ./gradlew generateAll, once per Kotlin version]
  kotlin-stdlib.jar ──→ MetadataParser ──→ ParseResult (types + extensions)
  .kotlin_builtins   ──→ BuiltinsParser ──┘   (@Metadata read from .class bytes via ASM)
                                          ↓
                         InheritanceResolver → denormalized entries
                                          ↓
  sources + common-sources jars → KDocParser → DocMerger → entries with docs
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
  Main.kt                          — entry point; args: <binary-jar> <output-path> <sources-jar>...
  model/Model.kt                   — data classes (ParamInfo, MemberInfo, TypeInfo, DenormalizedEntry)
  parser/MetadataParser.kt          — scans a stdlib jar, reads @Metadata from each .class
  parser/MetadataAnnotationReader.kt — extracts @Metadata from .class bytes via ASM (version-independent)
  parser/BuiltinsParser.kt          — reads .kotlin_builtins protobuf files for mapped types
  parser/InheritanceResolver.kt     — walks supertype graph, propagates extensions, deduplicates
  docs/KDocParser.kt                — parses KDoc from one or more sources jars
  docs/DocMerger.kt                 — matches KDoc entries to denormalized entries by name + params

src/main/java/
  parser/BuiltinsReader.java        — Java bridge to read .kotlin_builtins protobuf (Kotlin blocks
                                      access to kotlin.metadata.internal.* packages, Java doesn't)

frontend/
  index.html                        — single-page app (toolbar has the version dropdown)
  style.css                         — Darcula-inspired dark theme, JetBrains Mono + IBM Plex Sans
  app.js                            — search, fuzzy matching, keyboard nav, KDoc rendering, version loading
  data/methods-<v>.json.gz          — generated per-version datasets (gzipped)
  data/versions.json                — version manifest: {"default": "...", "versions": [...]}
  kotlin-icon.png                   — Kotlin logo for branding
```

## Data Pipeline Details

### 1. MetadataParser
Iterates every `.class` in the given stdlib jar and reads its `@Metadata` annotation **from the raw class bytes via ASM** (`MetadataAnnotationReader`), reconstructs a `kotlin.Metadata` instance, and parses it with `KotlinClassMetadata.readLenient()`. Reading from bytes (rather than `Class.forName`) is what lets the build parse an arbitrary version's jar that isn't on its own classpath. Handles `Class`, `FileFacade`, and `MultiFileClassPart` metadata types. Extracts types with declared members and extension functions with receiver types. Filters out private/internal visibility. No package filtering — covers the entire stdlib.

> ASM gotcha: primitive arrays in annotations (e.g. `mv`, the metadata version `int[]`) arrive through a single scalar `visit()` call, **not** `visitArray()`. String arrays (`d1`/`d2`) do go through `visitArray()`. Getting this wrong silently drops `metadataVersion` and `readLenient` rejects the result.

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
Parses `.kt` source files from one or more sources jars (`parseSourcesJar(vararg jarPaths)`). Tracks enclosing class/interface scope to associate KDoc for members declared inside type bodies (critical for builtin interfaces like `List`, `Map`). Extracts summary (first sentence/paragraph), full description, @param docs, @return, @since tags. Entries are merged with `putIfAbsent`, so jar order matters: the main sources jar is passed first, then `kotlin-stdlib-common` as a fallback.

### 5. DocMerger
Matches KDoc entries to denormalized entries using a multi-step strategy:
1. Exact: receiver type from signature + member name + param count
2. Entry's owning type as receiver (for inherited extensions)
3. Null receiver with param-name validation (class-declared members)
4. Name + param count with param-name matching (cross-type fallback)

Current coverage: ~85–86% of entries have documentation (consistent across all supported versions).

## Multi-version Generation

The whole pipeline runs once per Kotlin version and is orchestrated entirely in `build.gradle.kts`.

- **Version list** — `stdlibVersions` (in `build.gradle.kts`) holds the latest patch of each minor from 1.8 through 2.3. The first entry is the manifest default. Add/remove versions by editing this list.
- **Per-version jars** — for each version a `detached(...)` configuration resolves `kotlin-stdlib:<v>` (binary), `kotlin-stdlib:<v>:sources`, and `kotlin-stdlib-common:<v>:sources`, all `isTransitive = false`. The common-sources jar is resolved leniently (`runCatching`) because it may not exist for every version.
- **Tasks** — each version gets a `generate_<v_with_underscores>` JavaExec task running `MainKt`; the lifecycle task `generateAll` depends on all of them and, in `doLast`, writes `frontend/data/versions.json`.
- **Why common-sources** — pre-2.0 stdlibs keep the generated extension sources (`_Collections.kt`, `_Strings.kt`, `_Arrays.kt`, …) in `kotlin-stdlib-common`. Without it, 1.8/1.9 KDoc coverage drops to ~25%; with it, ~85%.
- **Reader/version compatibility** — `kotlin-metadata-jvm` and the `kotlin("jvm")` plugin are pinned to the **highest** supported version (2.3.21). Metadata readers are backward-compatible (a newer reader reads older metadata), so one reader handles the whole range. The constraint to preserve when bumping the range: reader version ≥ newest stdlib version parsed.
- **Output** — gzipped JSON (`Main` gzips when the output path ends in `.gz`); see Known Limitations for the transfer-size rationale.

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
- **Keyboard navigation**: two zones reached from the search box — type chips (↓ to enter, ←→ to move) then method results (↓ again, ↑↓ to move). Enter selects a chip (scopes the search) or expands a result; Escape steps back/clears. Chips are only navigable while still choosing a type (`typesNavigable`); once an exact type is committed, ↓ skips straight to results.
- **KDoc rendering**: converts KDoc markdown to HTML (inline code, code blocks, bold, symbol links)
- **Type bar**: clickable chips for matching types
- **Operator symbols** shown alongside method names
- **Version dropdown**: switches the active Kotlin version (see below)
- **URL hash state**: `#<version>/<query>` (e.g. `#2.3.21/List.filter`) is bookmarkable and version-pinned

### Version loading
On boot, `app.js` fetches `data/versions.json`, populates the dropdown, and picks the initial version in priority order: URL hash → `localStorage` (`kotlinVersion`) → manifest default. `loadVersion(v)` then fetches `data/methods-<v>.json.gz`, decompresses it in the browser with `DecompressionStream('gzip')` (the file is served opaquely, not via `Content-Encoding`, so no double-decompress), rebuilds the in-memory index, and re-runs the current query. Changing the dropdown or navigating the hash both route through the same path. Legacy hashes without a version prefix (`#List.filter`) are still honored as a bare query.

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
