# hayari — operator quickstart

Every block below is **output that was actually produced**, pasted as it came
out on 2026-08-10. Where a number here differs from what you get, the world
moved — not the docs.

## 0. What you need

`nbb` on PATH. Nothing else: the three sources are public and unauthenticated,
and there are no npm or maven dependencies to resolve.

The XMILE step additionally needs `kotoba-lang/org-oasis-open-xmile` checked
out. Under west it lands at the expected relative path automatically:

```bash
west update --fetch smart org-oasis-open-xmile
```

## 1. Tests first — they need no network

```
$ nbb --classpath src:test test/hayari/core_test.cljs
Testing hayari.core-test

Ran 25 tests containing 115 assertions.
0 failures, 0 errors.
```

The decision core is pure, so this is the fastest way to know the checkout is
sound. The XMILE integration test needs the sibling library:

```
$ nbb --classpath src:test:../../kotoba-lang/org-oasis-open-xmile/src \
      test/hayari/xmile_test.cljs
Ran 5 tests containing 18 assertions.
0 failures, 0 errors.
```

## 2. A small collection, to see the shape

Start narrow. This finishes in under a minute and shows every counter you will
later read at full scale.

```
$ nbb src/hayari/collect.cljs --date 2026-08-08 --days 4 --top 15 \
      --countries JP,KR,US,FR,BR,TW --out /tmp/dom.edn
  2026-08-07: 90 rows from 6 countries · 0 no data
    qid 74/90 · kind 63 · domain 63 · genre 22 · occ 36 · era 22 · dropped 20 (ns 16)
  2026-08-08: 90 rows from 6 countries · 0 no data
    qid 72/90 · kind 64 · domain 64 · genre 21 · occ 35 · era 24 · dropped 21 (ns 19)
  wrote /tmp/dom.edn — 279 datoms across 4 day(s): 2026-08-05 … 2026-08-08
  audience-generation: :uncomputable-until-measured
```

Reading that:

- **`dropped 21 (ns 19)`** — rows rejected as non-articles. `ns` is MediaWiki's
  own namespace number, so this works in every language; the parenthetical
  tells you how many of the drops that rule caught versus the P31 rule that
  catches list and disambiguation pages.
- **`qid 72/90`** — titles resolved to a Wikidata item. The rest are real
  articles with no Wikidata entry, which is a fact about the article.
- **`genre 21` / `occ 35`** — the two axes that carry the resolution. P31 says
  "television series" for both a Japanese drama and a Korean one, and says Q5
  for every person alive; genre and occupation are what separate them. Both are
  sparse by nature — most articles are neither a work nor a person — so these
  are counts, not a completeness score.
- **`era 24`** — rows that got a year. Most misses are people, who have no
  publication date because they are not works.
- **The last line is not decoration.** No source here carries viewer age.

## 3. The full sweep

```
$ nbb src/hayari/collect.cljs
```

Defaults: all 249 M49 countries, top 25, the date two days back (Wikimedia's
per-country aggregates lag), and a 480-second wall-clock budget.

Measured 2026-08-08 at full scale: 911 rows from 67 countries, 638 classified
(85 unclassified), 638 rolled up to a domain with 0 unmapped, 226 genres, 369
occupations, 188 non-articles dropped.

Expect roughly 100 countries to answer and roughly 60 to yield an observation —
those are **different numbers** and both are in the coverage entity. The gap is
countries whose only above-threshold pages were navigation.

If the budget runs out you get a partial result, not a killed process:

```
$ nbb src/hayari/collect.cljs --date 2026-08-07 --top 5 --budget-ms 3000
hayari: 2026-08-07 · 249 countries · top 5 · budget 3s
  attention: 54 rows from ... · 17 countries with no data · 215 countries SKIPPED (budget)
  qid: 0/54 resolved · 47 titles SKIPPED (budget)
  wrote ... — 55 datoms
  DEGRADED: failed batches 0 titles / 0 qids · budget-skipped 215 countries / 47 titles / 0 qids — the observation is partial and says so
```

**Read `:enrichment/degraded?` before quoting any count.** A throttled run once
reported 1 QID out of 874 while every other counter looked healthy.

## 3b. Reach the old work

Every run prints the single-year axis and writes it as
`:hayari.era-coverage/by-year`, with the empty years present as zeros:

```
$ nbb src/hayari/collect.cljs --date 2026-08-08 --top 200 --countries JP,GB
  2026-08-08: 400 rows from 2 countries · 0 no data
    qid 399/400 · kind 355 · domain 355 · genre 115 · occ 263 · era 94 · dropped 7 (ns 1)
  wrote ... — 395 datoms across 1 day(s): 2026-08-08
  years 1900-2026: 43 populated / 84 empty · 86 works · oldest 1903 · 7 dated outside the range
```

**Depth is the lever, not country count.** Measured 2026-08-08 on JP alone for
one day:

| | dated works |
|---|---|
| `--top 25` | almost nothing before 2000 |
| `--top 400` | 91 dated, **34 of them pre-2000** — 1950s 4, 1960s 5, 1970s 1, 1980s 3, 1990s 9, oldest 660s |

The per-country endpoint serves up to 1000 articles; old work sits in the long
tail of daily attention, so `--top` decides whether you reach it. Depth costs
enrichment time, which is why the registry's daily run stays at 25 — that job's
purpose is the time series. Run depth separately:

```bash
nbb src/hayari/collect.cljs --top 300 --days 4 --budget-ms 1500000 \
    --countries JP,US,GB,FR,DE,IT,ES,KR,TW,BR,IN,MX,PL,NL,SE,RU,TR
```

## 3c. Fetch the content and the entities

The collector records that a country looked at something. This fetches what
that something is.

```
$ nbb src/hayari/corpus.cljs --data /tmp/era-test.edn \
      --content-limit 25 --entity-limit 100
hayari corpus: 25/393 articles · 100/386 entities · budget 200s
  content: 25 fetched · 0 failed · 0 skipped → 25 held
  entity:  100 fetched · 0 failed · 0 skipped → 100 held
  licences: content CC-BY-SA-4.0 (attribution + share-alike) · entity CC0-1.0
  wrote data/hayari-content.edn / data/hayari-entities.edn
```

A content record, as written:

```clojure
{:hayari.content/project     "en.wikipedia"
 :hayari.content/article     "Briana_Corrigan"
 :hayari.content/wikidata-qid "Q4965736"
 :hayari.content/description "Northern Ireland singer (born 1965)"
 :hayari.content/extract     "Briana Corrigan is a Northern Irish singer. …"
 :hayari.content/revision    "1368488189"
 :hayari.content/revised-at  "2026-08-09T09:13:51Z"
 :hayari.content/license     "CC-BY-SA-4.0"
 :hayari.content/attribution "Wikipedia contributors, en.wikipedia.org"}
```

**The licence is on every record, not just in this file.** Wikipedia prose is
CC BY-SA 4.0 — attribution and share-alike — and Wikidata is CC0. Written once
in a README, those terms are lost the moment somebody excerpts the corpus.

Deliberately not fetched: full article text (the extract is the lead paragraph,
167 characters for a feature film), image bytes (per-file licences, frequently
not CC BY-SA — only the thumbnail URL is kept), and Wikidata claims
(`props=claims` measured 109,873 bytes per entity against 438 for
language-filtered labels, and the claims this observatory uses are already
extracted at collection time).

Targets are ordered by observed attention, so a run cut short by its budget
holds what was actually being looked at — and `:content/skipped-budget` says
how much it did not reach.

## 4. Fit and simulate the decay

Needs at least three days of history — a straight line through two points fits
perfectly and means nothing, so the estimator refuses it.

```
$ nbb src/hayari/simulate.cljs --data /tmp/multi2.edn --out /tmp/xmile-fit2.edn
hayari xmile: 4 day(s) held · 110 works · 18 fitted (>= 3 days)
  λ=0.7328  half-life=0.95d  r²=1.000  MAPE=1.1%  n=3  Perez_Hilton [:person]
  λ=0.1288  half-life=5.38d  r²=0.998  MAPE=9.4%  n=3  Pauline_Ferrand-Prévot [:person]
  λ=0.5745  half-life=1.21d  r²=0.968  MAPE=9.9%  n=4  Abdul_El-Sayed [:person]
  λ=0.0414  half-life=16.74d  r²=0.893  MAPE=1.4%  n=4  한국교육방송공사
  λ=-0.0713  half-life=growing  r²=0.824  MAPE=3.3%  n=4  杀人者的购物中心 [:tv/series]
  ...
  wrote /tmp/xmile-fit2.edn
```

- **λ** is a continuous per-day rate: the model is `Decay = Attention · λ`,
  integrated by RK4 at dt 0.25 in `org-oasis-open-xmile`. hayari does not
  contain a simulator.
- **`half-life=growing`** means λ came out negative — attention was still
  rising over the window. A negative half-life would be a number that means
  nothing, so none is printed.
- **MAPE is in-sample.** It scores the days the curve was fitted on. It is not
  a forecast claim and the output file repeats that in its header.

## 4b. Ask a domain-level question

The same model, applied to per-domain aggregates instead of single works:

```
$ nbb src/hayari/simulate.cljs --by domain --data /tmp/dom.edn
hayari xmile [domain]: 4 day(s) held · 7 series · 5 fitted (>= 3 days)
  λ=0.1947  half-life=3.56d  r²=0.897  MAPE=6.8%   n=4  person [:person]
  λ=-0.0595  half-life=growing  r²=0.808  MAPE=2.6%   n=4  culture [:culture]
  λ=-0.1658  half-life=growing  r²=0.600  MAPE=13.0%  n=4  event [:event]
```

Attention to people drained over that window while attention to culture was
still rising. Rows with no domain appear as `unmapped` rather than being left
out — an aggregate that quietly omits what the roll-up table missed would
understate the total it appears to describe.

## 5. Through the registry

This is the invocation that matters, because it is the one that runs unattended:

```
$ nbb --classpath ".:scripts/nbb_compat" scripts/observatory-run.cljs --only hayari
  ✓ hayari — produces-datoms (expect produces-datoms-idempotent) exit=0 Δbytes=398668 units=754 62s
```

Run it twice. Observations accumulate, so a new day grows the ledger and a
same-day repeat does not — and both must pass:

```
  ✓ hayari — produces-datoms-idempotent (expect produces-datoms-idempotent) exit=0 Δbytes=0 units=754 40s
```

`--check` will happily say `登録 OK / checkout 有り` for a build that cannot
start. It did, once. Run it for real.

## Troubleshooting

| symptom | cause |
|---|---|
| `Could not find namespace: hayari.core` | you invoked a file that expects to resolve its own classpath — both entry scripts do this from `*file*`; check the checkout is intact |
| `XMILE library not found at ...` | `west update --fetch smart org-oasis-open-xmile`, or pass `--xmile-src` |
| `exit=124`, no output file | a timeout killed the process before it could write. hayari's own budget is 480s; if the runner's is lower, pass a smaller `--budget-ms` |
| every count healthy but `qid` near zero | rate limiting. `:qid/batch-failed` will be non-zero and `:enrichment/degraded?` true |
| a country you expect is missing | check `:countries/no-data` — the API returns nothing for it below its privacy threshold. That is coverage, not exclusion |
