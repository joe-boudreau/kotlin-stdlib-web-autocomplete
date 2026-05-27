# Kotlin Stdlib Web Autocomplete — Implementation Plan

## Context

We're building a browser-based autocomplete/documentation tool for the Kotlin standard library, aimed at programming interviews. The project currently has a barebones Gradle setup with a proof-of-concept `Main.kt` that demonstrates reading Kotlin metadata from a single class. We need to go from this PoC to a working tool in 3 phases.

**Build order:** Phase 1 → Phase 3 → Phase 2 (get a working tool faster, add docs last)

---

## Phase 1: Structural Data Extraction (kotlin-metadata-jvm)

**Goal:** Scan the entire kotlin-stdlib, extract all types and their members, resolve inheritance, and produce a flat denormalized list.

### 1.1 — Enumerate all stdlib classes

- Use `ClassLoader.getSystemClassLoader()` to access kotlin-stdlib (it's already on the classpath since we depend on `kotlin-stdlib`)
- Use a `JarFile` to iterate all `.class` entries in the kotlin-stdlib JAR
- For each `.class` file, use ASM's `ClassReader` to read the `@Metadata` annotation without fully loading the class (more reliable than `Class.forName` for internal classes)
- **Add dependency:** `org.ow2.asm:asm:9.7.1` in `build.gradle.kts`

### 1.2 — Parse metadata into intermediate model

Define data classes for the intermediate representation:

```
TypeInfo(name, packageName, supertypes, typeParameters, members, kind)
MemberInfo(name, signature, kind[function/property/constructor], receiverType?, returnType, params, visibility, modality, isOperator, isInfix, isInline, isDeprecated)
```

Handle each metadata type:
- `KotlinClassMetadata.Class` → extract class members, supertypes, type parameters
- `KotlinClassMetadata.FileFacade` → extract extension functions (have `receiverParameterType`), top-level functions
- `KotlinClassMetadata.MultiFileClassPart` → same as FileFacade (these are parts of split files like `StringsKt`)
- `KotlinClassMetadata.MultiFileClassFacade` → skip (just a facade pointing to parts)
- Filter to target packages: `kotlin`, `kotlin.collections`, `kotlin.text`, `kotlin.ranges`, `kotlin.sequences`, `kotlin.comparisons`, `kotlin.io`
- Filter out private/internal members

### 1.3 — Build inheritance graph and denormalize

1. Collect all parsed types into a map by fully-qualified name
2. Build supertype adjacency list from `KmClass.supertypes`
3. Collect extension functions, bucket by receiver type
4. For each type, walk the supertype chain (BFS/DFS) and collect:
   - Own declared members
   - Inherited members from all supertypes
   - Extension functions whose receiver matches this type or any supertype
5. Deduplicate overridden methods (keep the most specific)
6. Flag operators with their symbolic form

### 1.4 — Serialize to JSON

- Use `kotlinx.serialization` (add dependency) to write the denormalized list to `methods.json`
- Each entry is self-contained per the data model in CLAUDE.md (type, package, member, kind, signature, params, returnType, etc.)
- At this stage, `summary` and `description` fields will be empty strings — filled in Phase 2

### Files to create/modify:
- `build.gradle.kts` — add ASM + kotlinx-serialization dependencies
- `src/main/kotlin/Main.kt` — replace PoC with orchestration entry point
- `src/main/kotlin/model/` — data classes for TypeInfo, MemberInfo, output JSON model
- `src/main/kotlin/parser/MetadataParser.kt` — scanning + parsing logic
- `src/main/kotlin/parser/InheritanceResolver.kt` — supertype walking + denormalization

### Verification:
- Run the program, inspect `methods.json`
- Spot-check: `List` should have `filter`, `map`, `forEach` (inherited from Iterable), `size` (own property), `plus` (extension)
- Check that extension functions appear on receiver type AND subtypes
- Check count is in the ~1500-2000 method range

---

## Phase 3: Static Frontend (built before Phase 2)

**Goal:** Build a fast, clean search UI that loads `methods.json` and provides IntelliJ-like autocomplete + quick-doc.

### 3.1 — Basic HTML/CSS/JS structure

- `frontend/index.html` — single page
- `frontend/style.css` — clean, minimal design (light theme, monospace signatures)
- `frontend/app.js` — all logic
- Copy `methods.json` into `frontend/` at build time (or reference it from project root)

### 3.2 — Data loading and indexing

- Fetch `methods.json` on page load
- Build in-memory indexes:
  - `typeIndex`: map of type name → array of methods
  - `allTypes`: sorted list of unique type names for the type picker
- No external libraries needed — dataset is small enough for native array methods

### 3.3 — Search UX

Two-mode input parsing:
- `List.filt` → type=`List`, query=`filt`
- `filter` (no dot) → search across all types
- Type-only (`List.`) → show all methods for List

Search algorithm:
- Prefix match first (highest priority)
- Then fuzzy match (characters appear in order) for the method name
- Deprecated methods sorted to the bottom
- Operator methods show both forms (e.g., `get` / `[]`)

### 3.4 — Results display

- **Compact list:** Each result row shows: `type` badge, `method name`, abbreviated signature, one-line summary
- **Expanded view:** Click/Enter on a result to expand inline: full signature, full description, parameter docs, return type, since version
- Keyboard navigation: arrow keys to move through results, Enter to expand/collapse, Escape to clear

### 3.5 — Polish

- Debounced input (100-150ms)
- Result count indicator
- Syntax highlighting for signatures (keyword coloring for `fun`, `val`, type names)
- Mobile-responsive layout
- URL hash state so you can bookmark/share a search

### Files to create:
- `frontend/index.html`
- `frontend/style.css`
- `frontend/app.js`

### Verification:
- Open `frontend/index.html` in a browser (no server needed — just `file://` or a simple HTTP server)
- Search for `List.filter` — should show the method with docs
- Search for `map` — should show results across multiple types
- Test keyboard navigation
- Test on mobile viewport

---

## Phase 2: KDoc Extraction and Merging (last)

**Goal:** Extract documentation strings from Kotlin stdlib source code and merge them into the structural data from Phase 1.

**Source:** `kotlin-stdlib-sources.jar` from Maven Central (simpler, self-contained — no GitHub clone needed).

### 2.1 — Obtain stdlib sources

- Use `kotlin-stdlib-sources.jar` from Maven Central (same version as stdlib)
- Add it as a dependency with a custom configuration (not compiled, just accessed as a JAR resource)
- Alternatively, download it at build time via a Gradle task
- The sources JAR contains `.kt` files with full KDoc comments

### 2.2 — Parse KDoc from source files

- Iterate `.kt` files in the sources JAR
- Use text-based parsing (not a full Kotlin parser — KDoc follows predictable patterns):
  - Find `/** ... */` comment blocks
  - Associate each with the immediately following declaration (`fun`, `val`, `var`, `class`, `interface`, `object`)
  - Extract: first sentence as `summary`, full body as `description`, `@param` tags, `@return`, `@since`, `@throws`
- Build a lookup map: `(typeName, memberName, paramTypes) → KDocInfo`
- For extension functions in FileFacade files, the receiver type is in the signature

### 2.3 — Merge docs into structural data

- After Phase 1 produces the denormalized list, iterate entries and look up docs by matching on:
  1. Type name + member name + parameter count/types
  2. Fall back to type name + member name if param matching is ambiguous
- Fill in `summary`, `description`, and per-param `doc` fields
- Methods without matching KDoc get empty doc fields (this is fine — structural info is still useful)

### Files to create/modify:
- `build.gradle.kts` — add sources JAR access
- `src/main/kotlin/docs/KDocParser.kt` — KDoc text extraction
- `src/main/kotlin/docs/DocMerger.kt` — matching + merging logic
- `src/main/kotlin/Main.kt` — orchestrate parse → merge → serialize

### Verification:
- Inspect `methods.json` — common methods like `String.substring`, `List.filter`, `Map.getOrDefault` should have non-empty summaries
- Check that `@param` docs are populated
- Estimate coverage: aim for >70% of methods having at least a summary
