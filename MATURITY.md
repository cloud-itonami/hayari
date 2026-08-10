# hayari 流行 — maturity ladder

The workspace scores repos on 7 axes (ADR-2608052000: substrate, test, governed,
ingest, docs, surface, fresh). This file states where hayari actually stands on
each, and what the next increment is. **Boxes are ticked by measurement, not by
intent** — the observatory's whole premise is that "the README says it works" is
not evidence, and that applies to this file first.

## Measured 2026-08-10

| axis | state | evidence |
|---|---|---|
| **substrate** | `src/hayari/{core.cljc,collect.cljs,corpus.cljs,xmile.cljc,simulate.cljs}` | decision core is pure and I/O-free; effects and the XMILE engine are separate |
| **test** | 31 tests / 143 assertions | 26/125 core (no sibling checkout needed) + 5/18 XMILE integration |
| **governed** | **none** | hayari publishes no assessments and actuates nothing, so it carries no governor. See "Why no governor" below |
| **ingest** | 3 live public APIs, unauthenticated | Wikimedia pageviews · MediaWiki pageprops · Wikidata claims+labels. All probed 2026-08-10 |
| **docs** | README · MATURITY.md · `docs/operator-quickstart.md` · ADR-2608103000 | quickstart carries real pasted output, not invented output |
| **surface** | registry entry + **workspace query plane** + XMILE projection | `manifest/edn-query.cljs` loads `data/hayari-summary.edn`; `data/hayari-xmile.edn` is consumable by `dynamics` |
| **fresh** | daily | registry `:change-rate 1.0`, matching the source's daily granularity |

### Why no governor

The governed-actor pattern exists to contain a model that *asserts* things.
hayari asserts nothing: it reports counts, the provenance of those counts, and
what it failed to collect. Adding a governor here would be ceremony — there is
no judgement for it to bound. If hayari ever publishes an interpretation
("this work is culturally significant"), that changes and it needs one.

## R0 — observe honestly (current)

- [x] Per-country attention from Wikimedia, all 249 M49 countries by default
- [x] Region axis from UN M49, generated from a real source with provenance
- [x] Work kind from Wikidata P31, 91 types, every label pinned from the live API
- [x] Genre from P136 — the axis that actually separates ドラマ from アニメ
- [x] Occupation from P106 — the only thing that separates an actor from a politician
- [x] Domain roll-up (culture / person / science / sport / event / organisation / place)
- [x] Work year AND decade from P577, falling back to P571/P1191/P580 in priority order
- [x] One work across language editions is one work (QID identity)
- [x] Non-articles rejected by MediaWiki's own namespace number, not by title prefixes
- [x] Wikimedia list/disambiguation pages rejected by P31
- [x] Coverage emitted as data: responded vs yielded rows, no-data by name, budget skips
- [x] Audience generation declared `:uncomputable-until-measured`, never bucketed from release year
- [x] Wall-clock budget under the registry timeout; partial results are written and declared
- [x] Runs with no `--classpath` from any directory (the registry's invocation shape)
- [x] Accumulates across runs — a day observed is a day kept
- [x] Attention decay fitted and simulated as OASIS XMILE via `org-oasis-open-xmile`
- [x] **The content and the entity behind each observation are actually fetched**
      (`corpus.cljs`): Wikipedia lead extracts and Wikidata labels/descriptions,
      with the licence stamped on every record — CC BY-SA 4.0 for prose,
      CC0-1.0 for entities. Targets ordered by attention so a budget-truncated
      run holds what was actually being looked at

## R0.x — coverage growth (each is one increment)

Country reach. **The ceiling here is the source, not the code**: Wikimedia
suppresses articles below a privacy threshold, so small populations return
nothing on most days.

- [x] Roster defaults to all 249 countries rather than a hand-picked list
- [x] Multi-day windows (`--days N`), so a country that clears the threshold on
      *any* day in the window enters the record
- [ ] Measure whether a 30-day union actually lifts Oceania above 2/29 — if it
      does not, the honest conclusion is that this source cannot see the Pacific
      and a second source is required, not that the countries are uninteresting
- [ ] Evaluate per-language `top` (`/metrics/pageviews/top/{project}`) as a
      complement for languages whose speakers are spread across small states.
      **Note the axis change**: that endpoint is per-language, not per-country,
      so it cannot be merged into the country axis without lying about it

Classification depth.

- [x] 91 P31 types. The head was chosen by counting what actually went
      unclassified; a marked [BREADTH] block adds chemistry, medicine,
      astronomy and biology, which had **not** been observed yet. The two
      provenance classes are labelled in `data/kinds.edn` so the size of the
      table is never read as evidence of what has been seen
- [x] **P31 alone cannot tell ドラマ from アニメ.** Measured 2026-08-10:
      進撃の巨人 and 鬼滅の刃 are both `manga series`; ひよっこ, 半沢直樹 and
      愛の不時着 are all `television series`. P136 genre is what separates them
      (`drama television series` / `adventure anime and manga` / `drama anime`)
- [x] **A person's P31 is always Q5.** P106 occupation is the resolution —
      `actor`, `seiyū`, `tarento`, `basketball player`, `announcer` all observed
- [x] Genre and occupation labels are resolved at collection time, not from a
      curated table. There are thousands of occupations; a hand-maintained map
      would be permanently and invisibly stale
- [ ] Re-measure the unclassified tail after a 30-day collection and extend again
- [ ] `:hayari/kind` currently returns the lowest-ranked match; consider emitting
      all matches so a consumer can regroup without re-querying Wikidata

Dating depth.

- [x] P577 → P571 → P1191 → P580, with `:hayari/dated-via` recording which answered
- [x] **A single-year axis from 1900**, emitted every run as
      `:hayari.era-coverage/by-year` with the empty years present as zeros.
      Distinct works per year, not rows. Works dated before 1900 are counted as
      `:outside-range` rather than clipped — 660s and 1860s works were observed
- [x] **Depth, not breadth, is what reaches old work.** Measured 2026-08-08,
      JP for one day: `--top 25` returned almost nothing before 2000, `--top 400`
      returned 34 pre-2000 works (1950s 4 / 1960s 5 / 1970s 1 / 1980s 3 /
      1990s 9). The per-country endpoint serves up to 1000; old work lives in
      the long tail of daily attention
- [ ] The registry's daily run stays at `--top 25`. Depth costs enrichment time
      and the daily job's purpose is the time series, not catalogue depth. A
      deep sweep on a slower cadence is the open item — **and until it has a
      cadence, the historical years stay as sparse as the table says they are**
- [ ] Persons dominate the unrated rows. A person is not a work and must not get
      a `work-era`; if a person-era axis is ever wanted it needs its own name and
      its own justification

Corpus depth.

- [x] Lead extracts, not full articles. The summary endpoint returns the lead
      paragraph — 167 characters for a feature film, measured. Full text would
      put a mirror of Wikipedia in a git checkout and answer nothing new
- [x] Thumbnail **URLs** only, never image bytes: images carry per-file licences
      that are frequently not CC BY-SA, and holding the bytes would import that
      problem into this corpus
- [x] Claims are not re-fetched. `props=claims` measured 109,873 bytes per
      entity against 438 for language-filtered labels+descriptions, and the
      claims this observatory asks about are already extracted at collection
- [ ] Extracts are en/ja-agnostic — they come in the language of the project
      that was observed. A cross-language comparison of *content* would need a
      deliberate decision about which language is canonical, and that decision
      has not been made
- [ ] The corpus is gitignored like the observations, so it shares R1's open
      question about where durable history lives

## R1 — the time axis (not yet reached)

- [x] Observations accumulate instead of overwriting
- [x] **The committed half exists.** `data/hayari-summary.edn` — one entity per
      (country, day) plus era coverage, 78 KB for 122 country-days — is tracked
      and loaded by `manifest/edn-query.cljs`. For five waves every observation
      row carried `:source/dataset "hayari"`, claiming membership of a query
      plane that had never loaded a single one of them
- [ ] **The summary is a snapshot, not a feed.** It is whatever the last person
      to run collect committed. Nothing lands it on a cadence, so freshness is
      unowned — the same open question as the corpus
- [ ] **Decide where the RAW history lives.** `data/hayari.datoms.edn` is gitignored per
      the observatory convention, so today the history exists only on whichever
      machine ran the collector. A year-by-year record needs a home: a DataLad
      dataset (as `hirameki-patents` does) or a summary projection under
      `90-docs/observatory/`. Until that is decided, **there is no durable
      year-over-year history and this file will not claim one**
- [x] Registry entry `:expect` is `:produces-datoms-idempotent`, and both
      branches were observed rather than assumed: first run Δbytes=398668,
      immediate re-run Δbytes=0, both passing

## R2 — system dynamics (started)

- [x] Attention decay as a one-stock XMILE model, simulated by the standard engine
- [x] λ, half-life, r² and in-sample MAPE reported per work
- [x] `--by era` fits the same model to per-decade aggregates, so "does
      attention to 1960s work drain differently from attention to this year's"
      is one command. Undated rows form their own series rather than vanishing
- [x] `--by domain` fits the same model to per-domain aggregates, so the
      question can be "does attention to culture drain differently from
      attention to events". Measured 2026-08-08: person λ=0.19 (half-life
      3.6d, r²=0.90) while culture was still rising over the same window
- [ ] Feed `data/hayari-xmile.edn` into `kotoba-lang/dynamics` for Meadows
      leverage scoring, as `loop-system-dynamics` does for its entities
- [ ] Model the inflow. Today only decay is modelled, so a work still rising
      reports a negative λ and no half-life — honest, but half a model
- [ ] Out-of-sample error. MAPE is in-sample and labelled so; a forecast claim
      requires holding days back, which requires a longer history (R1)
