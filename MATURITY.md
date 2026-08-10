# hayari 流行 — maturity ladder

The workspace scores repos on 7 axes (ADR-2608052000: substrate, test, governed,
ingest, docs, surface, fresh). This file states where hayari actually stands on
each, and what the next increment is. **Boxes are ticked by measurement, not by
intent** — the observatory's whole premise is that "the README says it works" is
not evidence, and that applies to this file first.

## Measured 2026-08-10

| axis | state | evidence |
|---|---|---|
| **substrate** | `src/hayari/{core.cljc,collect.cljs,xmile.cljc,simulate.cljs}` | decision core is pure and I/O-free; effects and the XMILE engine are separate |
| **test** | 21 tests / 89 assertions | 16/71 core (no sibling checkout needed) + 5/18 XMILE integration |
| **governed** | **none** | hayari publishes no assessments and actuates nothing, so it carries no governor. See "Why no governor" below |
| **ingest** | 3 live public APIs, unauthenticated | Wikimedia pageviews · MediaWiki pageprops · Wikidata claims. All probed 2026-08-10 |
| **docs** | README · MATURITY.md · `docs/operator-quickstart.md` · ADR-2608103000 | quickstart carries real pasted output, not invented output |
| **surface** | registry entry + XMILE projection | `manifest/observatories.edn`; `data/hayari-xmile.edn` is consumable by `dynamics` |
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
- [x] Work kind from Wikidata P31, 44 types, every label pinned from the live API
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

- [x] 44 P31 types, chosen by counting what actually went unclassified
- [ ] Re-measure the unclassified tail after a 30-day collection and extend again
- [ ] `:hayari/kind` currently returns the lowest-ranked match; consider emitting
      all matches so a consumer can regroup without re-querying Wikidata

Dating depth.

- [x] P577 → P571 → P1191 → P580, with `:hayari/dated-via` recording which answered
- [ ] Persons dominate the unrated rows. A person is not a work and must not get
      a `work-era`; if a person-era axis is ever wanted it needs its own name and
      its own justification

## R1 — the time axis (not yet reached)

- [x] Observations accumulate instead of overwriting
- [ ] **Decide where history lives.** `data/hayari.datoms.edn` is gitignored per
      the observatory convention, so today the history exists only on whichever
      machine ran the collector. A year-by-year record needs a home: a DataLad
      dataset (as `hirameki-patents` does) or a summary projection under
      `90-docs/observatory/`. Until that is decided, **there is no durable
      year-over-year history and this file will not claim one**
- [ ] Registry entry `:expect` should move to `:produces-datoms-idempotent` once
      history is durable — a same-day re-run correctly adds nothing

## R2 — system dynamics (started)

- [x] Attention decay as a one-stock XMILE model, simulated by the standard engine
- [x] λ, half-life, r² and in-sample MAPE reported per work
- [ ] Feed `data/hayari-xmile.edn` into `kotoba-lang/dynamics` for Meadows
      leverage scoring, as `loop-system-dynamics` does for its entities
- [ ] Model the inflow. Today only decay is modelled, so a work still rising
      reports a negative λ and no half-life — honest, but half a model
- [ ] Out-of-sample error. MAPE is in-sample and labelled so; a forecast claim
      requires holding days back, which requires a longer history (R1)
