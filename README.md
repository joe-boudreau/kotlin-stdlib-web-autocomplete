# Kotlin stdlib API Search

A browser-based autocomplete and documentation reference for the entire Kotlin standard library. Same information as the [official docs](https://kotlinlang.org/api/core/kotlin-stdlib/) but more easily searchable — similar to autocomplete in the IDE

Search 300+ types and 13,000+ members with fuzzy matching, full method signatures, and official KDoc documentation — all client-side, no backend required.

**Multi-version:** pick any Kotlin release from **1.8 through 2.3** in the version dropdown, and results re-scope to that version's stdlib.

## Quick Start

```bash
# 1. Generate data for every supported Kotlin version
./gradlew generateAll

# 2. Serve locally (output is written straight into frontend/data/)
cd frontend && python3 -m http.server 8090
```

Open http://localhost:8090

> `./gradlew run` still exists for a quick single-version build (the stdlib the build itself runs on), writing `methods.json` to the project root.

## Search Syntax

| Input | Behavior |
|-------|----------|
| `List.filter` | Search for "filter" within `List` members |
| `map` | Search "map" across all types |
| `List.` | Browse all `List` members |
| `Mut` | Matches types starting with "Mut" (MutableList, MutableMap, ...) |

Keyboard: **↓** from the search box jumps to the type chips (**←→** to move between them); **↓** again drops into the method results (**↑↓** to move). **Enter** selects a type chip (scopes the search) or expands/collapses a result. **Escape** steps back out / clears.

## How It Works

A Kotlin build script extracts structural metadata and documentation from the stdlib JARs, then a static frontend searches it client-side.

```
kotlin-stdlib.jar ─── @Metadata (read from .class bytes via ASM) ──→ types, members, extensions
                  ─── .kotlin_builtins files ─────────────────────→ mapped types (List, Map, Int, ...)
                                                      ↓
                                          inheritance resolution + deduplication
                                                      ↓
sources jars ─── KDoc parsing ──→ merge docs into entries
                                                      ↓
                                       methods-<version>.json.gz  (one per Kotlin version)
                                                      ↓
                                 static frontend (HTML/JS/CSS) + version dropdown
```

The pipeline runs **once per Kotlin version**. `./gradlew generateAll` loops the versions listed in `stdlibVersions` (in `build.gradle.kts`), resolving each version's jars from Maven and producing a gzipped dataset plus a `versions.json` manifest. The frontend reads the manifest to populate the dropdown and lazily fetches one dataset at a time.

### Multi-version support

- **Versions** — latest patch of each minor from 1.8 through 2.3. Edit `stdlibVersions` in `build.gradle.kts` to add or remove versions.
- **Version-independent parsing** — the parser reads the `@Metadata` annotation directly from `.class` bytes with ASM (`MetadataAnnotationReader`), so it can parse any version's jar regardless of which Kotlin runtime the build itself uses. `kotlin-metadata-jvm` is pinned to the newest supported version (2.3.21); metadata readers are backward-compatible, so a recent reader handles all older stdlibs.
- **Output** — `frontend/data/methods-<v>.json.gz` for each version, plus `frontend/data/versions.json` (`{"default": "...", "versions": [...]}`).
- **Frontend** — a dropdown in the toolbar switches versions; the choice is remembered (localStorage) and reflected in the URL hash (`#2.3.21/List.filter`), so links are shareable and version-pinned.

### Data sources

**`@Metadata` annotations** — read straight from compiled `.class` files (via ASM, without loading the classes) to extract class hierarchies, function signatures, extension functions, operator/infix/inline modifiers, and visibility. Parsed with `kotlin-metadata-jvm`.

**`.kotlin_builtins`** — protobuf files inside the stdlib JAR that define mapped types (List, Map, String, Int, etc.) which don't have `@Metadata` annotations because they compile directly to JVM primitives and interfaces. Parsed via a Java bridge class since Kotlin restricts access to the internal protobuf API.

**Sources jars** — Kotlin source files with KDoc comments. Parsed with a text-based extractor that handles top-level functions, extension functions, and members declared inside class/interface bodies. Matched to structural data by member name, receiver type, and parameter names. ~85–86% documentation coverage across versions. Two jars are read per version: `kotlin-stdlib:<v>:sources` plus `kotlin-stdlib-common:<v>:sources` — pre-2.0 releases kept the generated extension sources (`_Collections.kt`, `_Strings.kt`, …) in the common module, which 2.x later merged into the main sources jar.

### Inheritance resolution

The build walks each type's supertype chain (BFS) and collects inherited members plus all extension functions whose receiver matches the type or any of its supertypes. The result is fully denormalized — `List` includes everything from `Collection`, `Iterable`, and all extension functions declared on any of those types.

### Transfer size

Each dataset is ~7MB of JSON, written gzip-compressed to ~620KB (`.json.gz`) and decompressed in the browser with `DecompressionStream`. The files are served as opaque `.gz` (no `Content-Encoding: gzip`), so static hosts like GitHub Pages and `python3 -m http.server` won't double-decompress. Only the selected version is fetched.

## Tech Stack

- **Kotlin 2.3.21** — parsing script
- **kotlin-metadata-jvm** — reading class metadata (pinned to the newest supported stdlib version)
- **ASM** — reading `@Metadata` from `.class` bytes so parsing isn't tied to the build's classpath
- **kotlinx-serialization-json** — JSON output
- **Gradle 9.3** — build system; multi-version generation via detached configurations
- **Vanilla HTML/CSS/JS** — frontend (no framework); gzip datasets decompressed with `DecompressionStream`

## Project Structure

```
src/main/kotlin/
  Main.kt                          — orchestrates the pipeline (args: binary jar, output path, sources jars)
  model/Model.kt                   — shared data classes
  parser/MetadataParser.kt          — scans .class files for @Metadata
  parser/MetadataAnnotationReader.kt — reads @Metadata from .class bytes via ASM (version-independent)
  parser/BuiltinsParser.kt          — reads .kotlin_builtins protobuf
  parser/InheritanceResolver.kt     — supertype walking + deduplication
  docs/KDocParser.kt                — extracts KDoc from source files (one or more jars)
  docs/DocMerger.kt                 — matches KDoc to structural entries

src/main/java/
  parser/BuiltinsReader.java        — Java bridge for internal protobuf API

frontend/
  index.html, style.css, app.js     — static search UI with version dropdown
  data/methods-<v>.json.gz          — generated per-version datasets
  data/versions.json                — version manifest (default + list)
```

## License

MIT
