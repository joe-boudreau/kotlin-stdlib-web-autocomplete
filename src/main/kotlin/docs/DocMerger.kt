package docs

import model.DenormalizedEntry

class DocMerger {

    fun merge(entries: List<DenormalizedEntry>, kdocs: Map<KDocKey, KDocEntry>): List<DenormalizedEntry> {
        var matched = 0
        var unmatched = 0

        // Build secondary index: memberName → list of KDocEntries for broader matching
        val byName = mutableMapOf<String, MutableList<KDocEntry>>()
        for ((_, entry) in kdocs) {
            byName.getOrPut(entry.memberName) { mutableListOf() }.add(entry)
        }

        val result = entries.map { entry ->
            val doc = findDoc(entry, kdocs, byName)
            if (doc != null) {
                matched++
                entry.copy(
                    summary = doc.summary,
                    description = doc.description,
                    since = doc.since,
                    params = entry.params.map { p ->
                        val paramDoc = doc.paramDocs[p.name]
                        if (paramDoc != null) p.copy(doc = paramDoc) else p
                    },
                )
            } else {
                unmatched++
                entry
            }
        }

        println("KDoc merge: $matched matched, $unmatched unmatched (${matched * 100 / (matched + unmatched)}% coverage)")
        return result
    }

    private fun findDoc(
        entry: DenormalizedEntry,
        kdocs: Map<KDocKey, KDocEntry>,
        byName: Map<String, List<KDocEntry>>,
    ): KDocEntry? {
        val receiverType = extractReceiverFromSignature(entry)
        val paramCount = entry.params.size

        // 1. Exact: receiver from signature + name + param count
        if (receiverType != null) {
            val key = KDocKey(entry.member, receiverType, paramCount)
            kdocs[key]?.let { return it }
        }

        // 2. Try with entry.type as receiver (for inherited extensions)
        val key2 = KDocKey(entry.member, entry.type, paramCount)
        kdocs[key2]?.let { return it }

        // 3. Try without receiver (class-declared members) — only for non-common names
        val key3 = KDocKey(entry.member, null, paramCount)
        kdocs[key3]?.let { doc ->
            // Only use null-receiver match if param names align (avoids Array.get matching Map.get)
            if (paramCount == 0) return doc
            val entryParamNames = entry.params.map { it.name }.toSet()
            if (doc.paramNames.toSet() == entryParamNames) return doc
        }

        // 4. Name + param count, require param names to match
        val candidates = byName[entry.member] ?: return null
        val paramCountMatches = candidates.filter { it.paramCount == paramCount }
        if (paramCountMatches.isEmpty()) return null
        if (paramCount == 0) return paramCountMatches.firstOrNull()

        val entryParamNames = entry.params.map { it.name }.toSet()
        return paramCountMatches.firstOrNull { candidate ->
            candidate.paramNames.toSet() == entryParamNames
        }
    }

    private fun extractReceiverFromSignature(entry: DenormalizedEntry): String? {
        if (entry.kind != "extension") return null
        val sig = entry.signature
        val dotIdx = sig.indexOf('.')
        if (dotIdx < 0) return null
        val beforeDot = sig.substring(0, dotIdx)
        val withoutGenerics = beforeDot.replace(Regex("<[^>]*>"), "")
        return withoutGenerics.trim().split(Regex("\\s+")).lastOrNull()
    }
}
