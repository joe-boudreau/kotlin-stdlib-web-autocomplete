# Kotlin stdlib Search

A browser-based autocomplete and documentation reference for the entire Kotlin standard library. Built for programming interviews where you can use online materials but not your own IDE.

Search 388 types and 13,000+ members with fuzzy matching, full method signatures, and official KDoc documentation — all client-side, no backend required.

## Quick Start

```bash
# 1. Generate the data
./gradlew run

# 2. Copy to frontend
cp methods.json frontend/

# 3. Serve locally
cd frontend && python3 -m http.server 8090
```

Open http://localhost:8090

## Search Syntax

| Input | Behavior |
|-------|----------|
| `List.filter` | Search for "filter" within `List` members |
| `map` | Search "map" across all types |
| `List.` | Browse all `List` members |
| `Mut` | Matches types starting with "Mut" (MutableList, MutableMap, ...) |

Keyboard: **↑↓** navigate, **Enter** expand/collapse details, **Escape** clear

## How It Works

A Kotlin build script extracts structural metadata and documentation from the stdlib JAR, then a static frontend searches it client-side.

```
kotlin-stdlib.jar ─── @Metadata annotations ──→ types, members, extensions
                  ─── .kotlin_builtins files ──→ mapped types (List, Map, Int, ...)
                                                      ↓
                                          inheritance resolution + deduplication
                                                      ↓
kotlin-stdlib-sources.jar ─── KDoc parsing ──→ merge docs into entries
                                                      ↓
                                                 methods.json
                                                      ↓
                                            static frontend (HTML/JS/CSS)
```

### Data sources

**`kotlin-metadata-jvm`** — reads `@Metadata` annotations from compiled `.class` files to extract class hierarchies, function signatures, extension functions, operator/infix/inline modifiers, and visibility.

**`.kotlin_builtins`** — protobuf files inside the stdlib JAR that define mapped types (List, Map, String, Int, etc.) which don't have `@Metadata` annotations because they compile directly to JVM primitives and interfaces. Parsed via a Java bridge class since Kotlin restricts access to the internal protobuf API.

**`kotlin-stdlib-sources.jar`** — Kotlin source files with KDoc comments. Parsed with a text-based extractor that handles top-level functions, extension functions, and members declared inside class/interface bodies. Matched to structural data by member name, receiver type, and parameter names. ~86% documentation coverage.

### Inheritance resolution

The build walks each type's supertype chain (BFS) and collects inherited members plus all extension functions whose receiver matches the type or any of its supertypes. The result is fully denormalized — `List` includes everything from `Collection`, `Iterable`, and all extension functions declared on any of those types.

## Tech Stack

- **Kotlin 2.3.21** — parsing script
- **kotlin-metadata-jvm** — reading class metadata
- **kotlinx-serialization-json** — JSON output
- **Gradle 9.3** — build system
- **Vanilla HTML/CSS/JS** — frontend (no framework)

## Project Structure

```
src/main/kotlin/
  Main.kt                       — orchestrates the full pipeline
  model/Model.kt                — shared data classes
  parser/MetadataParser.kt       — scans .class files for @Metadata
  parser/BuiltinsParser.kt       — reads .kotlin_builtins protobuf
  parser/InheritanceResolver.kt  — supertype walking + deduplication
  docs/KDocParser.kt             — extracts KDoc from source files
  docs/DocMerger.kt              — matches KDoc to structural entries

src/main/java/
  parser/BuiltinsReader.java     — Java bridge for internal protobuf API

frontend/
  index.html, style.css, app.js  — static search UI
  methods.json                   — generated data (not checked in)
```

## License

MIT
