// ═══════════════════════════════════════════════
// Kotlin stdlib Quick Reference — app.js
// ═══════════════════════════════════════════════

let allEntries = [];
let typeIndex = {};   // type name → [entries]
let allTypes = [];    // sorted unique type names
let filtered = [];    // current search results
let selectedIdx = -1;
let expandedIdx = -1;
let currentVersion = null;     // selected Kotlin version
let availableVersions = [];    // versions from the manifest

const MAX_RESULTS = 200;

// ── DOM refs ────────────────────────────────────
const searchInput = document.getElementById('search');
const resultsEl = document.getElementById('results');
const resultCount = document.getElementById('resultCount');
const emptyState = document.getElementById('emptyState');
const typeBar = document.getElementById('typeBar');
const footer = document.getElementById('footer');
const typeChips = document.getElementById('typeChips');
const themeToggle = document.getElementById('themeToggle');
const versionSelect = document.getElementById('versionSelect');

// ── Theme toggle ────────────────────────────────
themeToggle.addEventListener('click', () => {
  const next = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
  document.documentElement.setAttribute('data-theme', next);
  localStorage.setItem('theme', next);
});

// ── Bootstrap ───────────────────────────────────
const LS_VERSION_KEY = 'kotlinVersion';

fetch('data/versions.json')
  .then(r => {
    if (!r.ok) throw new Error(`Failed to load versions.json: ${r.status}`);
    return r.json();
  })
  .then(manifest => {
    availableVersions = manifest.versions;
    populateVersionSelect(manifest);

    const fromHash = parseHash().version;
    const stored = localStorage.getItem(LS_VERSION_KEY);
    const initial = [fromHash, stored, manifest.default]
      .find(v => v && availableVersions.includes(v)) || manifest.default;

    versionSelect.value = initial;
    return loadVersion(initial);
  })
  .then(() => {
    initFromHash();
    searchInput.focus();
  })
  .catch(err => {
    emptyState.querySelector('.empty-title').textContent = 'Failed to load data';
    emptyState.querySelector('.empty-hint').textContent = err.message;
    emptyState.querySelector('.empty-examples').style.display = 'none';
  });

function populateVersionSelect(manifest) {
  versionSelect.innerHTML = '';
  for (const v of manifest.versions) {
    const opt = document.createElement('option');
    opt.value = v;
    opt.textContent = v;
    versionSelect.appendChild(opt);
  }
}

// Fetch the dataset for a version and rebuild the index. Returns a promise.
function loadVersion(version) {
  currentVersion = version;
  localStorage.setItem(LS_VERSION_KEY, version);
  return fetch(`data/methods-${version}.json.gz`)
    .then(r => {
      if (!r.ok) throw new Error(`Failed to load methods-${version}.json.gz: ${r.status}`);
      // Files are gzip-compressed; decompress in the browser (the static host serves
      // them as opaque .gz, not with Content-Encoding, so fetch won't auto-decompress).
      const stream = r.body.pipeThrough(new DecompressionStream('gzip'));
      return new Response(stream).json();
    })
    .then(data => {
      allEntries = data;
      buildIndex();
    });
}

versionSelect.addEventListener('change', () => {
  loadVersion(versionSelect.value)
    .then(() => doSearch())
    .catch(err => console.error(err));
});

function buildIndex() {
  typeIndex = {};
  for (const entry of allEntries) {
    if (!typeIndex[entry.type]) typeIndex[entry.type] = [];
    typeIndex[entry.type].push(entry);
  }
  allTypes = Object.keys(typeIndex).sort((a, b) => a.localeCompare(b));
}

// ── Search parsing ──────────────────────────────
function parseQuery(raw) {
  const trimmed = raw.trim();
  if (!trimmed) return { type: '', query: '' };

  const dotIdx = trimmed.indexOf('.');
  if (dotIdx === -1) return { type: '', query: trimmed };

  const typePart = trimmed.slice(0, dotIdx);
  const queryPart = trimmed.slice(dotIdx + 1);
  return { type: typePart, query: queryPart };
}

// ── Matching ────────────────────────────────────
function fuzzyScore(name, query) {
  const nl = name.toLowerCase();
  const ql = query.toLowerCase();

  if (nl === ql) return 1000;
  if (nl.startsWith(ql)) return 900 + (ql.length / nl.length) * 100;

  // Consecutive match bonus
  let qi = 0, consecutive = 0, maxConsecutive = 0, firstMatch = -1;
  for (let ni = 0; ni < nl.length && qi < ql.length; ni++) {
    if (nl[ni] === ql[qi]) {
      if (firstMatch === -1) firstMatch = ni;
      consecutive++;
      maxConsecutive = Math.max(maxConsecutive, consecutive);
      qi++;
    } else {
      consecutive = 0;
    }
  }
  if (qi < ql.length) return -1;

  const coverageBonus = (ql.length / nl.length) * 50;
  const earlyBonus = firstMatch === 0 ? 100 : (50 / (firstMatch + 1));
  return maxConsecutive * 30 + coverageBonus + earlyBonus;
}

function matchType(typeName, query) {
  if (!query) return true;
  return typeName.toLowerCase().startsWith(query.toLowerCase());
}

// ── Search execution ────────────────────────────
function search(raw) {
  const { type, query } = parseQuery(raw);
  let results = [];

  if (type) {
    // Type-scoped search
    const matchingTypes = allTypes.filter(t => matchType(t, type));

    if (matchingTypes.length === 0) {
      updateTypeBar([], type);
      return [];
    }

    updateTypeBar(matchingTypes, type);

    for (const t of matchingTypes) {
      const entries = typeIndex[t] || [];
      if (!query) {
        results.push(...entries.map(e => ({ entry: e, score: 500 })));
      } else {
        for (const e of entries) {
          const score = fuzzyScore(e.member, query);
          if (score >= 0) results.push({ entry: e, score });
        }
      }
    }
  } else if (query) {
    // Global search — also surface types whose names prefix-match the query
    updateTypeBar(allTypes.filter(t => matchType(t, query)), query);

    for (const e of allEntries) {
      const score = fuzzyScore(e.member, query);
      if (score >= 0) results.push({ entry: e, score });
    }
  } else {
    updateTypeBar([], '');
    return [];
  }

  // Sort: score desc, deprecated last, then alphabetical
  results.sort((a, b) => {
    const aDeprecated = a.entry.isDeprecated ? 1 : 0;
    const bDeprecated = b.entry.isDeprecated ? 1 : 0;
    if (aDeprecated !== bDeprecated) return aDeprecated - bDeprecated;
    if (b.score !== a.score) return b.score - a.score;
    const typeCmp = a.entry.type.localeCompare(b.entry.type);
    if (typeCmp !== 0) return typeCmp;
    return a.entry.member.localeCompare(b.entry.member);
  });

  return results.slice(0, MAX_RESULTS).map(r => r.entry);
}

// ── Rendering ───────────────────────────────────
function render(entries) {
  filtered = entries;
  selectedIdx = -1;
  expandedIdx = -1;

  if (entries.length === 0 && !searchInput.value.trim()) {
    resultsEl.innerHTML = '';
    emptyState.classList.remove('hidden');
    footer.classList.remove('hidden');
    resultCount.textContent = '';
    return;
  }

  emptyState.classList.add('hidden');
  footer.classList.add('hidden');
  resultCount.textContent = entries.length >= MAX_RESULTS
    ? `${MAX_RESULTS}+ results`
    : `${entries.length} result${entries.length !== 1 ? 's' : ''}`;

  const fragment = document.createDocumentFragment();

  for (let i = 0; i < entries.length; i++) {
    const e = entries[i];
    const row = document.createElement('div');
    row.className = 'result-row' + (e.isDeprecated ? ' deprecated' : '');
    row.setAttribute('role', 'option');
    row.dataset.index = i;

    const badgeClass = {
      'function': 'fn', 'extension': 'ext', 'property': 'prop', 'constructor': 'ctor',
      'extension property': 'ext'
    }[e.kind] || 'fn';

    const badgeLabel = {
      'function': 'fn', 'extension': 'ext', 'property': 'val', 'constructor': 'new',
      'extension property': 'ext'
    }[e.kind] || 'fn';

    const operatorSuffix = e.operatorSymbol
      ? `<span class="operator-sym">${escapeHtml(e.operatorSymbol)}</span>`
      : '';

    const params = abbreviateParams(e);
    const returnStr = e.returnType && e.returnType !== 'Unit'
      ? `→ ${e.returnType}` : '';

    const summaryStr = e.summary
      ? `<span class="sig-sep">—</span><span class="sig-summary">${escapeHtml(e.summary)}</span>`
      : '';

    row.innerHTML = `
      <span class="kind-badge ${badgeClass}">${badgeLabel}</span>
      <span class="type-tag">${escapeHtml(e.type)}.</span>
      <span class="member-name">${escapeHtml(e.member)}</span>
      ${operatorSuffix}
      <span class="sig-abbrev">${escapeHtml(params)}</span>
      <span class="return-type">${escapeHtml(returnStr)}</span>
      ${summaryStr}
    `;

    row.addEventListener('click', () => toggleExpand(i));
    fragment.appendChild(row);

    // Detail panel (hidden by default)
    const detail = document.createElement('div');
    detail.className = 'result-detail';
    detail.dataset.detailIndex = i;
    detail.innerHTML = buildDetailHTML(e);
    fragment.appendChild(detail);
  }

  resultsEl.innerHTML = '';
  resultsEl.appendChild(fragment);
}

function abbreviateParams(e) {
  if (e.kind === 'property') return '';
  if (!e.params || e.params.length === 0) return '()';
  const inner = e.params.map(p => {
    let s = p.name + ': ' + p.type;
    if (p.hasDefault) s += ' = …';
    return s;
  }).join(', ');
  return `(${inner})`;
}

function buildDetailHTML(e) {
  let html = '';

  // Highlighted signature
  html += `<div class="detail-signature">${highlightSignature(e.signature)}</div>`;

  // Modifier tags + meta
  const tags = [];
  if (e.isInline) tags.push('<span class="modifier-tag inline-tag">inline</span>');
  if (e.isInfix) tags.push('<span class="modifier-tag infix-tag">infix</span>');
  if (e.isOperator) tags.push('<span class="modifier-tag operator-tag">operator</span>');
  if (e.isSuspend) tags.push('<span class="modifier-tag suspend-tag">suspend</span>');

  html += '<div class="detail-meta">';
  if (tags.length) html += `<div class="detail-meta-item">${tags.join(' ')}</div>`;
  html += `<div class="detail-meta-item"><span class="detail-meta-label">Kind</span><span class="detail-meta-value">${e.kind}</span></div>`;
  html += `<div class="detail-meta-item"><span class="detail-meta-label">Package</span><span class="detail-meta-value">${e.packageName}</span></div>`;
  if (e.returnType && e.returnType !== 'Unit') {
    html += `<div class="detail-meta-item"><span class="detail-meta-label">Returns</span><span class="detail-meta-value">${escapeHtml(e.returnType)}</span></div>`;
  }
  if (e.operatorSymbol) {
    html += `<div class="detail-meta-item"><span class="detail-meta-label">Operator</span><span class="detail-meta-value">${escapeHtml(e.operatorSymbol)}</span></div>`;
  }
  if (e.since) {
    html += `<div class="detail-meta-item"><span class="detail-meta-label">Since</span><span class="detail-meta-value">${escapeHtml(e.since)}</span></div>`;
  }
  html += '</div>';

  // Parameters
  if (e.params && e.params.length > 0) {
    html += '<div class="detail-params">';
    html += '<div class="detail-params-title">Parameters</div>';
    for (const p of e.params) {
      const paramDocStr = p.doc ? `<div class="detail-param-doc">${renderKDoc(p.doc)}</div>` : '';
      html += `<div class="detail-param-row">
        <span class="detail-param-name">${escapeHtml(p.name)}</span>
        <span class="detail-param-type">${escapeHtml(p.type)}</span>
        ${p.hasDefault ? '<span class="detail-param-default">= default</span>' : ''}
        ${p.isVararg ? '<span class="detail-param-default">vararg</span>' : ''}
      </div>${paramDocStr}`;
    }
    html += '</div>';
  }

  if (e.description || e.summary) {
    const text = e.description || e.summary;
    html += '<div class="detail-description">';
    html += '<div class="detail-description-title">Description</div>';
    html += `<div class="detail-description-text">${renderKDoc(text)}</div>`;
    html += '</div>';
  }

  return html;
}

// ── KDoc rendering ──────────────────────────────
function renderKDoc(text) {
  if (!text) return '';
  let html = escapeHtml(text);

  // Code blocks: ```code``` → <pre><code>
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) =>
    `<pre class="kdoc-codeblock"><code>${code.trim()}</code></pre>`
  );

  // Inline code: `code` → <code>
  html = html.replace(/`([^`]+)`/g, '<code class="kdoc-code">$1</code>');

  // KDoc symbol links: [name] → <code>name</code>
  // But skip markdown-style [text](url) links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a class="kdoc-link" href="$2">$1</a>');
  html = html.replace(/\[(\w+(?:\.\w+)*)\]/g, '<code class="kdoc-code">$1</code>');

  // Bold: **text** or __text__
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/__(.+?)__/g, '<strong>$1</strong>');

  // Italic: *text* or _text_ (but not inside words with underscores)
  html = html.replace(/(?<!\w)\*([^*]+)\*(?!\w)/g, '<em>$1</em>');

  // Paragraphs: double newlines
  html = html.replace(/\n\n+/g, '</p><p>');

  // Single newlines → <br> (within paragraphs)
  html = html.replace(/\n/g, '<br>');

  return `<p>${html}</p>`;
}

// ── Signature highlighting ──────────────────────
function highlightSignature(sig) {
  const keywords = ['fun', 'val', 'var', 'inline', 'infix', 'operator', 'suspend', 'constructor', 'vararg'];
  let result = escapeHtml(sig);

  // Keywords
  for (const kw of keywords) {
    result = result.replace(
      new RegExp(`\\b(${kw})\\b`, 'g'),
      '<span class="kw">$1</span>'
    );
  }

  // Generic type parameters <T>, <K, V>, <out T : Comparable<T>>
  result = result.replace(
    /(&lt;[^&]*?&gt;)/g,
    (match) => `<span class="tp">${match}</span>`
  );

  // Function name — the word right before (
  result = result.replace(
    /(\w+)(\()/g,
    '<span class="fn">$1</span>$2'
  );

  return result;
}

// ── Type bar ────────────────────────────────────
function updateTypeBar(types, activePrefix) {
  if (types.length === 0) {
    typeBar.classList.remove('visible');
    return;
  }

  typeBar.classList.add('visible');

  // Only show first 30 matching types
  const shown = types.slice(0, 30);
  typeChips.innerHTML = shown.map(t => {
    const isActive = t.toLowerCase() === activePrefix.toLowerCase();
    return `<button class="type-chip ${isActive ? 'active' : ''}" data-type="${escapeHtml(t)}">${escapeHtml(t)}</button>`;
  }).join('');
}

typeChips.addEventListener('click', (e) => {
  const chip = e.target.closest('.type-chip');
  if (!chip) return;
  const typeName = chip.dataset.type;
  searchInput.value = typeName + '.';
  searchInput.focus();
  doSearch();
});

// ── Expand/collapse ─────────────────────────────
function toggleExpand(idx) {
  const details = resultsEl.querySelectorAll('.result-detail');
  const rows = resultsEl.querySelectorAll('.result-row');

  if (expandedIdx === idx) {
    // Collapse
    details[idx].classList.remove('open');
    expandedIdx = -1;
  } else {
    // Collapse previous
    if (expandedIdx >= 0 && details[expandedIdx]) {
      details[expandedIdx].classList.remove('open');
    }
    // Expand new
    details[idx].classList.add('open');
    expandedIdx = idx;
  }

  // Update selection
  setSelected(idx);
}

function setSelected(idx) {
  const rows = resultsEl.querySelectorAll('.result-row');
  if (selectedIdx >= 0 && rows[selectedIdx]) {
    rows[selectedIdx].classList.remove('selected');
  }
  selectedIdx = idx;
  if (idx >= 0 && rows[idx]) {
    rows[idx].classList.add('selected');
    scrollIntoViewIfNeeded(rows[idx]);
  }
}

function scrollIntoViewIfNeeded(el) {
  const container = resultsEl;
  const elTop = el.offsetTop;
  const elBottom = elTop + el.offsetHeight;
  const viewTop = container.scrollTop;
  const viewBottom = viewTop + container.clientHeight;

  if (elTop < viewTop) {
    container.scrollTop = elTop - 4;
  } else if (elBottom > viewBottom) {
    container.scrollTop = elBottom - container.clientHeight + 4;
  }
}

// ── Keyboard navigation ─────────────────────────
searchInput.addEventListener('keydown', (e) => {
  const count = filtered.length;
  if (!count) return;

  if (e.key === 'ArrowDown') {
    e.preventDefault();
    setSelected(selectedIdx < count - 1 ? selectedIdx + 1 : 0);
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    setSelected(selectedIdx > 0 ? selectedIdx - 1 : count - 1);
  } else if (e.key === 'Enter') {
    e.preventDefault();
    if (selectedIdx >= 0) toggleExpand(selectedIdx);
    else if (count > 0) toggleExpand(0);
  } else if (e.key === 'Escape') {
    e.preventDefault();
    if (expandedIdx >= 0) {
      const details = resultsEl.querySelectorAll('.result-detail');
      if (details[expandedIdx]) details[expandedIdx].classList.remove('open');
      expandedIdx = -1;
    } else {
      searchInput.value = '';
      doSearch();
    }
  }
});

// ── Debounced search ────────────────────────────
let debounceTimer = null;
searchInput.addEventListener('input', () => {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(doSearch, 120);
});

function doSearch() {
  const raw = searchInput.value;
  const entries = search(raw);
  render(entries);
  updateHash(raw);
}

// ── URL hash state ──────────────────────────────
// Hash format: #<version>/<query>  (e.g. #2.3.21/List.filter)
function parseHash() {
  const h = window.location.hash.slice(1);
  if (!h) return { version: null, query: '' };
  const slash = h.indexOf('/');
  const first = slash === -1 ? h : h.slice(0, slash);
  if (availableVersions.includes(first)) {
    return { version: first, query: slash === -1 ? '' : decodeURIComponent(h.slice(slash + 1)) };
  }
  // Legacy bookmark with no version prefix: treat the whole hash as the query.
  return { version: null, query: decodeURIComponent(h) };
}

function updateHash(query) {
  const hash = '#' + currentVersion + (query ? '/' + encodeURIComponent(query) : '');
  if (window.location.hash !== hash) {
    history.replaceState(null, '', hash);
  }
}

function initFromHash() {
  const { query } = parseHash();
  if (query) {
    searchInput.value = query;
  }
  doSearch();
}

window.addEventListener('hashchange', () => {
  const { version, query } = parseHash();
  if (version && version !== currentVersion) {
    versionSelect.value = version;
    loadVersion(version).then(() => {
      searchInput.value = query;
      doSearch();
    });
    return;
  }
  if (searchInput.value !== query) {
    searchInput.value = query;
    doSearch();
  }
});

// ── Example buttons ─────────────────────────────
document.querySelectorAll('.example-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    searchInput.value = btn.dataset.query;
    searchInput.focus();
    doSearch();
  });
});

// ── Utility ─────────────────────────────────────
function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
