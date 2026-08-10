(ns hayari.core
  "hayari 流行 — the DECISION CORE. Pure: no I/O, no clock, no network.

  A caller (`hayari.collect`) probes the world and hands the observations in;
  this namespace decides what they mean. That split is the same arrangement
  `loop-system-dynamics` has with `kotoba-lang/dynamics`, and it is what makes
  the judgements testable without a network.

  ## Kotoba migration status

  This is the decision core in the sense of the superproject CLAUDE.md rule
  『移行の単位は決定核』 — the judgement is here, the effects are in the .cljs
  sibling. It is not yet compiled as `.kotoba`. Two constraints shaped it:

  - **Errors are returned, never thrown.** Every fallible function returns
    `{:ok v}` / `{:error {...}}`. `explicit-errors` is an
    `:intentional-security-constraint` in `lang/surface-status.edn`, so this
    shape is PERMANENT and does not change when the backends catch up.
  - **Maps and vectors are used freely.** Those are `:implemented-partial`
    (`#{:compiler :kotoba-wasm :kotoba-cljs}`) and absent on native. That is a
    BACKEND GAP, not a style — do not imitate this as though word-typed
    scalars were the intended Kotoba idiom, and do not flatten this core to
    handles to chase native. See CLAUDE.md 『`.kotoba` で「書けない」は 2 種類ある』."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Meta-article rejection
;; ---------------------------------------------------------------------------

(def ^:private meta-titles
  "Titles that are navigation, not works. Observed in real responses: every
   project's main page and search special-page outrank actual subjects."
  #{"Main_Page" "メインページ" "Hauptseite" "Accueil" "Wikipedia:Hauptseite"
    "Portada" "Pagina_principale" "Главная_страница" "首页" "首頁"
    "대문" "Trang_Chính" "Halaman_Utama" "Pàgina_principal" "Основна_сторінка"
    "Strona_główna" "Hoofdpagina" "Huvudsida" "Forside" "Etusivu"
    "Página_principal" "Ana_Sayfa" "الصفحة_الرئيسية" "首頁_(維基百科)"})

(def ^:private meta-namespace-prefixes
  "A ':' in a Wikipedia title means a namespace unless the article genuinely
   contains one. Restricting to known namespace labels avoids discarding real
   titles like 「機動戦士ガンダム: 閃光のハサウェイ」."
  ["Special:" "特別:" "Спеціальна:" "Спеціальна:" "Especial:" "Spezial:"
   "Spécial:" "Speciale:" "Служебная:" "Bijzonder:" "Wikipedia:" "ウィキペディア:"
   "Portal:" "Category:" "カテゴリ:" "Categoría:" "Kategorie:" "Catégorie:"
   "File:" "ファイル:" "Help:" "ヘルプ:" "Template:" "Talk:" "User:" "利用者:"
   "Project:" "Wikipédia:" "Wikipedia_talk:" "Search" "特别:"])

(defn meta-article?
  "True when `title` names navigation/meta rather than a subject."
  [title]
  (let [t (str title)]
    (boolean
      (or (contains? meta-titles t)
          (some #(str/starts-with? t %) meta-namespace-prefixes)
          (str/blank? t)))))

(defn subject-articles
  "Drop meta rows, keep source order (the API returns them rank-ascending)."
  [articles]
  (vec (remove #(meta-article? (:article %)) articles)))

;; ---------------------------------------------------------------------------
;; Attention share
;; ---------------------------------------------------------------------------

(defn attention-shares
  "Attach `:share` = views / (sum of views in this country-day) to each row,
  computed AFTER meta rows are dropped so navigation does not dilute it.

  The denominator is the observed roster-day total for THIS country, not a
  global total. There is no global pageview total in these sources and one is
  not invented here."
  [articles]
  (let [rows  (subject-articles articles)
        total (reduce + 0 (map #(or (:views %) 0) rows))]
    (if (zero? total)
      {:ok []}
      {:ok (mapv (fn [r] (assoc r :share (/ (double (or (:views r) 0)) total))) rows)})))

;; ---------------------------------------------------------------------------
;; Work-era cohort  (NOT audience generation — see coverage/)
;; ---------------------------------------------------------------------------

(defn publication-year
  "Earliest 4-digit year among Wikidata P577 time strings (`+2010-07-17T..`).
  A work with re-releases (Arrietty has three P577 values) is dated by its
  first publication, which is what an era cohort means."
  [p577-times]
  (let [years (keep (fn [t]
                      (when-let [m (re-find #"^[+-](\d{4})" (str t))]
                        (let [y #?(:clj (Long/parseLong (second m))
                                   :cljs (js/parseInt (second m) 10))]
                          (when (and (pos? y) (< y 2200)) y))))
                    p577-times)]
    (if (seq years)
      {:ok (apply min years)}
      {:error {:reason :no-publication-date}})))

(defn work-era
  "Decade bucket of first publication. The honest, sourced cohort axis."
  [p577-times]
  (let [r (publication-year p577-times)]
    (if-let [y (:ok r)]
      {:ok (* 10 (quot y 10))}
      r)))

(def date-property-order
  "Wikidata date properties tried in order when dating a work.

  Not merged into one pool: P577 (publication date) is the work's own release,
  while P571 (inception) can belong to a franchise or a production company and
  P580 (start time) to a run rather than a debut. Taking the minimum across all
  of them would silently date a 2024 series by its 1963 franchise. First
  property that yields a year wins, and which one it was is recorded."
  [:p577 :p571 :p1191 :p580])

(defn dated-by
  "First of `date-property-order` that yields a year from `claims`.
  Returns {:ok {:year y :era decade :via :p577}} or the last error."
  [claims]
  (loop [ps date-property-order last-err {:error {:reason :no-date-property}}]
    (if (empty? ps)
      last-err
      (let [p (first ps)
            r (publication-year (get claims p))]
        (if-let [y (:ok r)]
          {:ok {:year y :era (* 10 (quot y 10)) :via p}}
          (recur (rest ps) r))))))

;; ---------------------------------------------------------------------------
;; Content kind
;; ---------------------------------------------------------------------------

(def wikimedia-meta-p31
  "P31 values that mark a page as Wikimedia machinery rather than a subject.

  The title-based filter cannot catch these: `List of highest-grossing films`
  and `Mercury (disambiguation)` look like ordinary article titles and were
  being counted as things a country's public paid attention to. Measured
  2026-08-10 across one day's 249-country sweep, they were the 5th and 19th
  most common unclassified P31 values.

  Labels are the ones the live API returned on 2026-08-10, pinned here for the
  same reason data/kinds.edn pins its own."
  {"Q13406463" "Wikimedia list article"
   "Q4167410"  "Wikimedia disambiguation page"
   "Q4167836"  "Wikimedia category"
   "Q11266439" "Wikimedia template"
   "Q11753321" "Wikimedia navigational template"
   "Q35252665" "MediaWiki non-main namespace"})

(defn wikimedia-meta?
  "True when P31 marks this as a Wikimedia-internal page.

  Kept separate from `meta-article?` on purpose: that one runs on the title
  before any network work, this one needs Wikidata. A row rejected here is
  counted in the coverage report rather than silently vanishing."
  [p31-qids]
  (boolean (some wikimedia-meta-p31 p31-qids)))

(defn classify-kind
  "Map Wikidata P31 (instance-of) QIDs to a content kind via `kinds`
  (data/kinds.edn: {\"Q20650540\" :anime/film ...}).

  A work usually carries several P31 values; the FIRST match in `kinds`
  iteration order would be nondeterministic, so matches are collected and the
  one with the lowest `:rank` in the kinds table wins. Unmapped is reported as
  `:unclassified`, never guessed — an unmapped QID is a coverage gap, and
  `coverage-report` counts it."
  [p31-qids kinds]
  (let [hits (keep (fn [q] (when-let [e (get kinds q)] (assoc e :qid q))) p31-qids)]
    (if (seq hits)
      {:ok (:kind (first (sort-by :rank hits)))}
      {:error {:reason :unclassified :p31 (vec p31-qids)}})))

;; ---------------------------------------------------------------------------
;; Region
;; ---------------------------------------------------------------------------

(defn region-of
  "ISO 3166-1 alpha-2 -> UN M49 region/sub-region, from data/m49-regions.edn."
  [iso2 regions]
  (if-let [e (get regions (str/upper-case (str iso2)))]
    {:ok e}
    {:error {:reason :country-not-in-region-table :iso2 iso2}}))

;; ---------------------------------------------------------------------------
;; Cross-country lift
;; ---------------------------------------------------------------------------

(defn work-key
  "Language-independent identity of a work.

  Keying on (project, article) looks right and is wrong for exactly the rows
  this observatory exists to find. Measured 2026-08-10: `The_Odyssey_(2026_film)`
  on en.wikipedia and `La_Odisea_(película_de_2026)` on es.wikipedia are the same
  film, and counting them separately made a work seen in 19 countries report 13
  and 6. The undercount lands hardest on works that crossed a language border —
  the subject of the measurement.

  The Wikidata QID is that identity when it resolved. When it did not, the row
  falls back to (project, article) and is therefore only ever compared with rows
  from the same language, which understates but never overstates reach."
  [r]
  (if-let [q (:qid r)] [:qid q] [:title (:project r) (:article r)]))

(defn cross-country-lift
  "For each work, how concentrated its attention is in one country relative to
  the mean share across the countries where it was actually observed.

  `:observed-in` travels with every result on purpose. A lift computed from two
  countries and a lift computed from ninety are not the same claim, and a
  consumer that cannot see the denominator will read them as if they were.

  A work can top several language editions inside ONE country; those rows are
  collapsed per country first, so `:observed-in` counts countries and not
  articles."
  [rows]
  (let [by-work (group-by work-key rows)]
    (vec
      (for [[_ rs] by-work
            :let [countries (distinct (map :country rs))
                  n         (count countries)
                  per-country (reduce (fn [m r]
                                        (update m (:country r) (fnil + 0.0) (:share r)))
                                      {} rs)
                  mean      (/ (reduce + 0.0 (vals per-country)) n)]
            r rs]
        {:project     (:project r)
         :article     (:article r)
         :country     (:country r)
         :share       (:share r)
         :mean-share  mean
         :lift        (if (pos? mean) (/ (get per-country (:country r)) mean) 0.0)
         :observed-in n}))))

;; ---------------------------------------------------------------------------
;; Coverage
;; ---------------------------------------------------------------------------

(defn coverage-report
  "What was asked for, what came back, and what remains unmeasurable.

  `manifest/observatories.edn` exists because 22 actors all claimed in their
  README to work and 8 of them had never run. The equivalent failure for a
  measurement is a count that reads as 'the world' when it is 33 countries, so
  the shortfall is emitted as data next to the datoms, not as prose in a README.

  `:audience-generation` is `:uncomputable-until-measured` and stays that way.
  Wikimedia's per-country endpoint carries no viewer age, and neither does
  Wikidata. Bucketing works by release decade and calling that a generation
  would be inventing the measurement."
  [{:keys [countries-requested countries-responded countries-with-rows countries-no-data
           titles-seen qid-resolved qid-unresolved
           kind-classified kind-unclassified era-dated era-undated
           qid-batch-failed claim-batch-failed
           countries-skipped qid-skipped claim-skipped]}]
  {:countries/requested   countries-requested
   ;; Answering the API is not the same as yielding an observation, and one
   ;; number for both flatters the result. Measured 2026-08-08: 101 countries
   ;; responded 200, but only 66 contributed a single subject row — for the
   ;; other 35 every article above Wikimedia's privacy threshold was a main
   ;; page or a search page, so the country is present in the feed and absent
   ;; from the findings. Quote :countries/with-rows when describing reach.
   :countries/responded   countries-responded
   :countries/with-rows   countries-with-rows
   :countries/no-data     (vec countries-no-data)
   ;; Countries the wall-clock budget cut off before they were ever asked.
   ;; Distinct from :countries/no-data — one means the API had nothing above its
   ;; privacy threshold, the other means we ran out of time. Reading them as the
   ;; same number would turn our own scheduling into a fact about the world.
   :countries/skipped-budget (vec countries-skipped)
   :titles/seen           titles-seen
   :qid/resolved          qid-resolved
   :qid/unresolved        qid-unresolved
   ;; A title inside a failed batch is NOT the same fact as a title that has no
   ;; Wikidata item, and collapsing the two is how a throttled run reports full
   ;; coverage. Measured 2026-08-10: throttling silently cut resolution to
   ;; 1/874 while every other count still looked healthy.
   :qid/batch-failed      (or qid-batch-failed 0)
   :claim/batch-failed    (or claim-batch-failed 0)
   :qid/skipped-budget    (or qid-skipped 0)
   :claim/skipped-budget  (or claim-skipped 0)
   :enrichment/degraded?  (boolean (or (pos? (or qid-batch-failed 0))
                                       (pos? (or claim-batch-failed 0))
                                       (pos? (or qid-skipped 0))
                                       (pos? (or claim-skipped 0))
                                       (seq countries-skipped)))
   :kind/classified       kind-classified
   :kind/unclassified     kind-unclassified
   :era/dated             era-dated
   :era/undated           era-undated
   :audience-generation   :uncomputable-until-measured
   :audience-generation/reason
   "No source in this observatory carries viewer age. Wikimedia's per-country
    pageview endpoint is aggregated and privacy-filtered; Wikidata describes
    works, not audiences. The era axis this observatory does emit is the work's
    own first-publication decade, which is a property of the work."})

;; ---------------------------------------------------------------------------
;; Attention as a quantity that changes  (input to the XMILE model)
;; ---------------------------------------------------------------------------

(defn work-series
  "Group observation datoms into a per-work time series of daily views.

  `opts` may carry :country to restrict to one country; otherwise a work's
  views are summed across every country that saw it on that day.

  Keyed by `work-key`, so a film that appears under six language editions is
  one series rather than six — the same identity rule the lift calculation
  uses, for the same reason."
  ([datoms] (work-series datoms {}))
  ([datoms {:keys [country]}]
   (let [rows (cond->> (filter :hayari/observed-on datoms)
                country (filter #(= country (:hayari/country-iso2 %))))]
     (into {}
           (for [[k rs] (group-by #(work-key {:qid     (:hayari/wikidata-qid %)
                                              :project (:hayari/project %)
                                              :article (:hayari/article %)})
                                  rows)]
             [k {:label  (:hayari/article (first rs))
                 :kind   (some :hayari/kind rs)
                 :points (vec (sort-by :day
                                       (for [[d ds] (group-by :hayari/observed-on rs)]
                                         {:day   d
                                          :views (reduce + 0 (map #(or (:hayari/views %) 0) ds))})))}])))))

(defn- day-index
  "Days since the first point, so the fit is over elapsed time rather than
  over row position — a gap in the collection must not compress the curve."
  [points]
  (let [->ms (fn [d] (.getTime (new #?(:clj java.util.Date :cljs js/Date) d)))
        t0   (->ms (:day (first points)))]
    (mapv (fn [p] (/ (- (->ms (:day p)) t0) 86400000.0)) points)))

(defn estimate-decay
  "Fit V(t) = V0 · e^(−λt) to a work's daily views by least squares on ln V.

  Returns {:ok {:lambda λ :v0 V0 :n n :r2 r²  :half-life days}} or an error.

  λ is the continuous rate the XMILE model uses directly as
  `Decay = Attention · lambda`. Points with zero views are dropped rather than
  clamped: ln 0 has no value, and substituting one would invent a data point.

  Fewer than three points is refused. Two points always fit a line exactly,
  which would report r² = 1 for a curve that was never tested — the number
  would look like confidence and carry none."
  [points]
  (let [pts (vec (filter #(pos? (or (:views %) 0)) points))]
    (if (< (count pts) 3)
      {:error {:reason :too-few-points :n (count pts) :need 3}}
      (let [xs (day-index pts)
            ys (mapv #(#?(:clj Math/log :cljs js/Math.log) (:views %)) pts)
            n  (count xs)
            mx (/ (reduce + xs) n)
            my (/ (reduce + ys) n)
            sxy (reduce + (map (fn [x y] (* (- x mx) (- y my))) xs ys))
            sxx (reduce + (map (fn [x] (let [d (- x mx)] (* d d))) xs))]
        (if (zero? sxx)
          {:error {:reason :no-time-spread :n n}}
          (let [slope (/ sxy sxx)
                icpt  (- my (* slope mx))
                pred  (mapv (fn [x] (+ icpt (* slope x))) xs)
                ss-res (reduce + (map (fn [y p] (let [d (- y p)] (* d d))) ys pred))
                ss-tot (reduce + (map (fn [y] (let [d (- y my)] (* d d))) ys))
                lambda (- slope)]
            {:ok {:lambda    lambda
                  :v0        (#?(:clj Math/exp :cljs js/Math.exp) icpt)
                  :n         n
                  :r2        (if (zero? ss-tot) 1.0 (- 1.0 (/ ss-res ss-tot)))
                  ;; Negative λ means attention was still GROWING over the window.
                  ;; Reported as nil rather than a negative "half-life", which
                  ;; would read as a number and mean nothing.
                  :half-life (when (pos? lambda)
                               (/ (#?(:clj Math/log :cljs js/Math.log) 2) lambda))}}))))))

(defn mape
  "Mean absolute percentage error between observed and simulated values.
  The honest scoreboard for whether the fitted model reproduces the days it
  was fitted on — reported, never used to silently discard a bad fit."
  [observed simulated]
  (let [pairs (filter (fn [[o _]] (pos? o)) (map vector observed simulated))]
    (if (empty? pairs)
      {:error {:reason :nothing-to-compare}}
      {:ok (/ (reduce + (map (fn [[o s]]
                               (/ (#?(:clj Math/abs :cljs js/Math.abs) (- o s)) o))
                             pairs))
              (count pairs))})))

;; ---------------------------------------------------------------------------
;; Accumulation across runs
;; ---------------------------------------------------------------------------

(defn observation-key
  "Identity of one observation row: a country's attention to one article on one
  day. Re-running the same day must replace those rows, not duplicate them."
  [d]
  [(:hayari/observed-on d) (:hayari/country-iso2 d)
   (:hayari/project d) (:hayari/article d)])

(defn merge-observations
  "Fold `fresh` datoms into `prior` ones, newest run winning per observation.

  Attention is only a time series if days survive each other. The registry
  gitignores this file and the actor overwrote it every run, so each day erased
  the last and the collection could never answer anything about change — which
  is what a stock-and-flow model needs and what a per-year question needs.

  Coverage entities (`:hayari.coverage/observed-on`) are kept one per day for
  the same reason: a reader must be able to see how partial each day was, not
  just the most recent one. Renumbering is left to the caller."
  [prior fresh]
  (let [obs?    (fn [d] (contains? d :hayari/observed-on))
        cov?    (fn [d] (contains? d :hayari.coverage/observed-on))
        by-key  (fn [ds] (into {} (map (juxt observation-key identity) (filter obs? ds))))
        by-day  (fn [ds] (into {} (map (juxt :hayari.coverage/observed-on identity) (filter cov? ds))))
        merged-obs (vals (merge (by-key prior) (by-key fresh)))
        merged-cov (vals (merge (by-day prior) (by-day fresh)))]
    {:coverage (vec (sort-by :hayari.coverage/observed-on merged-cov))
     :rows     (vec (sort-by (juxt :hayari/observed-on :hayari/country-iso2 :hayari/rank)
                             merged-obs))}))

(defn renumber
  "Assign fresh negative :db/id values to a merged datom sequence."
  [ds]
  (vec (map-indexed (fn [i d] (assoc d :db/id (- (inc i)))) ds)))

;; ---------------------------------------------------------------------------
;; Datom assembly
;; ---------------------------------------------------------------------------

(defn ->datoms
  "Assemble the observation rows into tx-data for the workspace datom plane
  (`manifest/edn-query.cljs`, `:source/dataset \"hayari\"`).

  `:hayari/country-iso2` joins to the surface/company planes by country, and
  `:hayari/origin-country-qid` is kept as the raw Wikidata QID rather than
  being resolved to a name, so it stays a join key."
  [{:keys [observed-on rows coverage source-urls]}]
  (let [obs (map-indexed
              (fn [i r]
                (cond-> {:db/id                    (- (+ i 2))
                         :source/dataset           "hayari"
                         :hayari/observed-on       observed-on
                         :hayari/country-iso2      (:country r)
                         :hayari/rank              (:rank r)
                         :hayari/views             (:views r)
                         :hayari/attention-share   (:share r)
                         :hayari/article           (:article r)
                         :hayari/project           (:project r)}
                  (:region-m49 r)     (assoc :hayari/region-m49 (:region-m49 r))
                  (:subregion-m49 r)  (assoc :hayari/subregion-m49 (:subregion-m49 r))
                  (:region-name r)    (assoc :hayari/region-name (:region-name r))
                  (:subregion-name r) (assoc :hayari/subregion-name (:subregion-name r))
                  (:qid r)            (assoc :hayari/wikidata-qid (:qid r))
                  (:kind r)           (assoc :hayari/kind (:kind r))
                  ;; Both granularities are emitted. The decade is the cohort a
                  ;; reader usually wants; the year is what was actually sourced
                  ;; from P577, and rounding it away in the only stored value
                  ;; would discard precision the source gave us for free.
                  (:work-year r)      (assoc :hayari/work-year (:work-year r))
                  (:work-era r)       (assoc :hayari/work-era (:work-era r))
                  ;; Which Wikidata property dated this row. A P577 release and
                  ;; a P571 inception are not equally strong evidence, and a
                  ;; consumer comparing eras should be able to see which it got.
                  (:dated-via r)      (assoc :hayari/dated-via (:dated-via r))
                  (:origin-qid r)     (assoc :hayari/origin-country-qid (:origin-qid r))
                  (:lift r)           (assoc :hayari/cross-country-lift (:lift r))
                  (:observed-in r)    (assoc :hayari/observed-in-countries (:observed-in r))))
              rows)]
    (vec (cons {:db/id                     -1
                :source/dataset            "hayari"
                :hayari.coverage/observed-on observed-on
                :hayari.coverage/report     (pr-str coverage)
                :hayari.coverage/source-urls (pr-str (vec source-urls))}
               obs))))
