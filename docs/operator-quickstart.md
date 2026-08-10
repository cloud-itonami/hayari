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

Ran 16 tests containing 71 assertions.
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
$ nbb src/hayari/collect.cljs --date 2026-08-08 --days 4 --top 10 \
      --countries JP,US,FR,KR,TW,BR --out /tmp/multi2.edn
hayari: 2026-08-05 .. 2026-08-08 · 6 countries · top 10 · budget 480s · prior 0 datoms
  2026-08-05: 60 rows from 6 countries · 0 no data
    qid 52/60 · kind 43 · era 15 · dropped 10 (ns 9)
  2026-08-06: 60 rows from 6 countries · 0 no data
    qid 52/60 · kind 40 · era 17 · dropped 11 (ns 9)
  2026-08-07: 60 rows from 6 countries · 0 no data
    qid 50/60 · kind 41 · era 18 · dropped 11 (ns 11)
  2026-08-08: 60 rows from 6 countries · 0 no data
    qid 48/60 · kind 40 · era 20 · dropped 12 (ns 12)
  wrote /tmp/multi2.edn — 200 datoms across 4 day(s): 2026-08-05, 2026-08-06, 2026-08-07, 2026-08-08
  audience-generation: :uncomputable-until-measured
```

Reading that:

- **`dropped 12 (ns 12)`** — rows rejected as non-articles. `ns` is MediaWiki's
  own namespace number, so this works in every language; the parenthetical
  tells you how many of the drops that rule caught versus the P31 rule that
  catches list and disambiguation pages.
- **`qid 48/60`** — titles resolved to a Wikidata item. The rest are real
  articles with no Wikidata entry, which is a fact about the article.
- **`era 20`** — rows that got a year. Most misses are people, who have no
  publication date because they are not works.
- **The last line is not decoration.** No source here carries viewer age.

## 3. The full sweep

```
$ nbb src/hayari/collect.cljs
```

Defaults: all 249 M49 countries, top 25, the date two days back (Wikimedia's
per-country aggregates lag), and a 480-second wall-clock budget.

Expect roughly 100 countries to answer and roughly 66 to yield an observation —
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

## 5. Through the registry

This is the invocation that matters, because it is the one that runs unattended:

```
$ nbb --classpath ".:scripts/nbb_compat" scripts/observatory-run.cljs --only hayari
  ✓ hayari — produces-datoms (expect produces-datoms) exit=0 Δbytes=447531 units=891 63s
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
