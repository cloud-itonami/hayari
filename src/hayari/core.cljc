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
  (:require [clojure.string :as str]
            #?(:cljs [cljs.reader])))

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
  {"Q13406463"  "Wikimedia list article"
   "Q4167410"   "Wikimedia disambiguation page"
   "Q4167836"   "Wikimedia category"
   "Q11266439"  "Wikimedia template"
   "Q11753321"  "Wikimedia navigational template"
   "Q35252665"  "MediaWiki non-main namespace"
   ;; Found in the unclassified tail on 2026-08-10, after the first pass:
   ;; machinery that had been surviving as though it were a subject.
   "Q17442446"  "Wikimedia internal item"
   "Q104635718" "Wikimedia artist discography"
   "Q15633587"  "MediaWiki main namespace page"})

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

(defn domain-key
  "The key a kind rolls up under.

  Namespaced kinds (`:anime/film`) roll up by namespace; bare ones (`:person`,
  `:manga`, `:event`) by their own name. Using `namespace` alone silently
  returned nil for every bare kind — measured 2026-08-10, that dropped the
  single largest class, `:person`, out of the domain axis entirely while the
  namespaced kinds around it looked fine."
  [kind]
  (or (namespace kind) (name kind)))

(defn domain-of
  "Roll a kind up to a domain via `domains` (data/domains.edn).
  An unmapped kind is reported, never bucketed."
  [kind domains]
  (if (nil? kind)
    {:error {:reason :no-kind}}
    (if-let [d (get domains (domain-key kind))]
      {:ok d}
      {:error {:reason :domain-unmapped :kind kind}})))

;; ---------------------------------------------------------------------------
;; Genre and occupation
;; ---------------------------------------------------------------------------

(def ^:private max-labels
  "How many genre/occupation values to keep per row. Wikidata routinely lists
  a dozen; the first few carry the signal and the rest inflate every datom."
  4)

(defn labelled
  "Turn a list of QIDs into {:qids [...] :labels [...]}, keeping source order
  and dropping QIDs whose label was not fetched.

  Labels are resolved at collection time rather than from a curated table.
  There are thousands of occupations and tens of thousands of genres; a
  hand-maintained mapping for those would be permanently and invisibly stale,
  which is the failure mode data/kinds.edn can afford only because its head is
  short and measured."
  [qids labels]
  (let [ks (vec (take max-labels qids))]
    {:qids   ks
     :labels (vec (keep #(get labels %) ks))}))

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
           countries-skipped qid-skipped claim-skipped
           domain-mapped domain-unmapped genre-labelled occupation-labelled
           label-batch-failed]}]
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
   :label/batch-failed    (or label-batch-failed 0)
   ;; A classified row without a domain means data/domains.edn is missing a
   ;; roll-up key, not that the row belongs nowhere. Counted so the table can
   ;; be extended from evidence, the same way kinds.edn is.
   :domain/mapped         (or domain-mapped 0)
   :domain/unmapped       (or domain-unmapped 0)
   ;; Genre separates ドラマ from アニメ; occupation separates an actor from a
   ;; politician. Both are sparse by nature — most articles are neither — so
   ;; these are reported as counts, not as a completeness score.
   :genre/labelled        (or genre-labelled 0)
   :occupation/labelled   (or occupation-labelled 0)
   :enrichment/degraded?  (boolean (or (pos? (or qid-batch-failed 0))
                                       (pos? (or claim-batch-failed 0))
                                       (pos? (or label-batch-failed 0))
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

(defn domain-series
  "Daily total views per domain — the aggregate a stock-and-flow model wants
  when the question is about a class of attention rather than one work.

  Rows with no domain are grouped under :unmapped rather than dropped, so the
  aggregate cannot quietly exclude what the roll-up table failed to classify."
  [datoms]
  (let [rows (filter :hayari/observed-on datoms)]
    (into {}
          (for [[dom rs] (group-by #(or (:hayari/domain %) :unmapped) rows)]
            [dom {:label  (name dom)
                  :kind   dom
                  :points (vec (sort-by :day
                                        (for [[d ds] (group-by :hayari/observed-on rs)]
                                          {:day   d
                                           :views (reduce + 0 (map #(or (:hayari/views %) 0) ds))})))}]))))

(defn era-series
  "Daily total views per release-DECADE of the work.

  A different stock from `domain-series`: not \"what kind of thing is being
  looked at\" but \"how old is the thing being looked at\". Rows with no year
  are grouped under :undated rather than dropped — the undated share is large
  (most rows are people, who have no publication date) and hiding it would make
  the dated decades look like the whole picture."
  [datoms]
  (let [rows (filter :hayari/observed-on datoms)]
    (into {}
          (for [[era rs] (group-by #(or (:hayari/work-era %) :undated) rows)]
            [era {:label  (if (keyword? era) (name era) (str era "s"))
                  :kind   era
                  :points (vec (sort-by :day
                                        (for [[d ds] (group-by :hayari/observed-on rs)]
                                          {:day   d
                                           :views (reduce + 0 (map #(or (:hayari/views %) 0) ds))})))}]))))

(defn year-coverage
  "Which single years between `from` and `to` are represented at all.

  The observatory measures TODAY's attention, and today's attention is mostly
  on recent things — so a per-year axis is honest only if the empty years are
  visible next to the populated ones. This returns the full range with zeros
  present, not just the years that happened to appear.

  `:works` counts DISTINCT works per year, not rows: one film seen in forty
  countries is one work of its year, otherwise the axis would measure reach and
  be labelled as if it measured catalogue depth."
  [datoms from to]
  (let [rows   (filter :hayari/work-year datoms)
        in-rng (filter #(<= from (:hayari/work-year %) to) rows)
        by-year (into {}
                      (for [[y rs] (group-by :hayari/work-year in-rng)]
                        [y (count (distinct (map #(work-key {:qid     (:hayari/wikidata-qid %)
                                                             :project (:hayari/project %)
                                                             :article (:hayari/article %)})
                                                 rs)))]))
        full   (into (sorted-map) (for [y (range from (inc to))] [y (get by-year y 0)]))
        populated (filter (comp pos? val) full)
        outside (- (count rows) (count in-rng))]
    {:from            from
     :to              to
     :by-year         full
     :years-populated (count populated)
     :years-empty     (- (count full) (count populated))
     :oldest-year     (when (seq populated) (key (first populated)))
     :newest-year     (when (seq populated) (key (last populated)))
     :works-total     (reduce + 0 (vals full))
     ;; Works dated outside [from,to] — antiquity, and anything Wikidata dates
     ;; into the future. Counted rather than clipped silently.
     :outside-range   outside}))

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
;; The committed summary  (what the workspace query plane can actually load)
;; ---------------------------------------------------------------------------

(defn country-day-summary
  "One entity per (country, day): small enough to commit, joinable by country.

  Every observation datom carries `:source/dataset \"hayari\"`, which claims
  membership of the workspace query plane — but the raw rows are gitignored, so
  for the first five waves that claim was not true of anything a reader could
  load. This is the part that is small enough to keep in git and therefore the
  part that makes the claim real.

  `:hayari.summary/country-iso2` joins to the first two characters of
  `:company/jurisdiction` in the LEI plane, which carries ISO 3166-1 alpha-2
  (sometimes with a subdivision, `US-DE`). Rolled-up distributions travel as
  `pr-str` strings, the same shape `:adr/body` and `tos/*` use, because a datom
  value has to be flat."
  [datoms]
  (let [rows (filter :hayari/observed-on datoms)]
    (vec
      (for [[[day country] rs] (sort-by key (group-by (juxt :hayari/observed-on
                                                            :hayari/country-iso2)
                                                      rows))
            :let [top (first (sort-by #(- (or (:hayari/views %) 0)) rs))
                  works (count (distinct (map #(work-key {:qid     (:hayari/wikidata-qid %)
                                                          :project (:hayari/project %)
                                                          :article (:hayari/article %)})
                                              rs)))]]
        (cond-> {:source/dataset               "hayari"
                 :hayari.summary/observed-on   day
                 :hayari.summary/country-iso2  country
                 :hayari.summary/rows          (count rs)
                 :hayari.summary/works         works
                 :hayari.summary/views-total   (reduce + 0 (map #(or (:hayari/views %) 0) rs))
                 :hayari.summary/domains       (pr-str (frequencies (keep :hayari/domain rs)))
                 :hayari.summary/kinds         (pr-str (frequencies (keep :hayari/kind rs)))
                 :hayari.summary/eras          (pr-str (frequencies (keep :hayari/work-era rs)))
                 ;; Single years live here so the per-year axis can be rebuilt from
                 ;; the committed summary alone. Deriving it from the raw made the
                 ;; axis only as wide as whatever raw the current machine held —
                 ;; and the raw is gitignored, so on a fresh clone that is one day.
                 :hayari.summary/years         (pr-str (frequencies (keep :hayari/work-year rs)))}
          (:hayari/region-name (first rs))
          (assoc :hayari.summary/region-name (:hayari/region-name (first rs)))
          (:hayari/subregion-name (first rs))
          (assoc :hayari.summary/subregion-name (:hayari/subregion-name (first rs)))
          (:hayari/article top)
          (assoc :hayari.summary/top-article (:hayari/article top))
          (:hayari/wikidata-qid top)
          (assoc :hayari.summary/top-qid (:hayari/wikidata-qid top))
          (:hayari/kind top)
          (assoc :hayari.summary/top-kind (:hayari/kind top)))))))

;; ---------------------------------------------------------------------------
;; Corpus targets  (what to actually fetch the text and the entity for)
;; ---------------------------------------------------------------------------

(defn- edn-read
  "Read a pr-str'd value back. Local so the decision core keeps its only
  require being clojure.string."
  [s]
  #?(:clj (read-string s) :cljs (cljs.reader/read-string s)))

(defn tick-health
  "The unattended loop's own vital signs, as a datom.

  A loop nobody watches is a loop that fails silently, and this one already did:
  on 2026-08-10 `west update` failed, the pin moved without the checkout, and
  the query plane sat four days behind while every other number looked healthy.
  The only trace was a line in /tmp. That is the exact failure this whole
  observatory is built to refuse, so the loop reports on itself in the same
  place it reports on the world.

  It rides in the committed summary rather than a file of its own so the query
  plane needs no second loader — and so a stale heartbeat is visible to anyone
  already querying hayari, not only to whoever thinks to look for it.

  `:consecutive-failures` is what a monitor should alarm on. `:last-run-at`
  updates only on runs that had news, which during backfill is every run and
  afterwards is roughly daily; a heartbeat older than about 36 hours means the
  loop has stopped, not that it had nothing to do."
  [{:keys [at outcome day days-held backfill-remaining prior detail]}]
  (let [prior-fails (or (:hayari.tick/consecutive-failures prior) 0)
        failed?     (not (contains? #{:added-day :nothing-to-do} outcome))]
    (cond-> {:source/dataset                    "hayari"
             :hayari.tick/last-run-at           at
             :hayari.tick/last-outcome          outcome
             :hayari.tick/days-held             days-held
             :hayari.tick/backfill-remaining    backfill-remaining
             :hayari.tick/consecutive-failures  (if failed? (inc prior-fails) 0)
             :hayari.tick/healthy?              (not failed?)
             :hayari.tick/note
             (str "The loop's own vital signs. :consecutive-failures is what to alarm on. "
                  ":last-run-at only advances on runs that had news — during backfill that "
                  "is every run, afterwards roughly daily — so a heartbeat older than about "
                  "36 hours means the loop stopped, not that it was idle.")}
      day    (assoc :hayari.tick/last-day day)
      detail (assoc :hayari.tick/last-detail detail)
      ;; Keep the last failure visible after recovery: a loop that failed
      ;; yesterday and works today is not the same as one that never failed.
      (and (not failed?) (pos? prior-fails))
      (assoc :hayari.tick/recovered-from-failures prior-fails))))

(defn merge-summaries
  "Union prior and fresh country-day rows, newest run winning per (day, country).

  The summary must accumulate on its OWN, not be re-derived from the raw
  observations. Measured 2026-08-10, the hard way: the tick runs in a fresh
  clone, the raw is gitignored, so rebuilding the summary from raw replaced five
  days of committed history with the single day that clone had just collected —
  and pushed it. A day that has been observed must survive the next machine to
  run the collector."
  [prior fresh]
  (let [k (juxt :hayari.summary/observed-on :hayari.summary/country-iso2)
        row? #(contains? % :hayari.summary/country-iso2)]
    (vec (sort-by k (vals (merge (into {} (map (juxt k identity) (filter row? prior)))
                                 (into {} (map (juxt k identity) (filter row? fresh)))))))))

(defn era-coverage-from-summary
  "Rebuild the 1900-onwards year axis from the committed summary alone.

  Deliberately a different quantity from the raw-derived version it replaces,
  and named for what it is: `:country-days-by-year` counts the country-days in
  which a work first published in that year appeared. Summing distinct works
  across countries would double-count a film seen in forty of them, and calling
  that a catalogue count would be false. `:years-populated` is exact either way,
  and it is what 『1年ごとに分けられている?』 actually asks."
  [summary from to]
  (let [per-day (for [r summary
                      :let [ys (try (edn-read (str (:hayari.summary/years r)))
                                    (catch #?(:clj Exception :cljs :default) _ nil))]
                      :when (map? ys)
                      [y n] ys
                      :when (and (integer? y) (<= from y to) (pos? n))]
                  y)
        tally   (frequencies per-day)
        full    (into (sorted-map) (for [y (range from (inc to))] [y (get tally y 0)]))
        pop*    (filter (comp pos? val) full)]
    {:from from :to to
     :country-days-by-year full
     :years-populated (count pop*)
     :years-empty (- (count full) (count pop*))
     :oldest-year (when (seq pop*) (key (first pop*)))
     :newest-year (when (seq pop*) (key (last pop*)))}))

(defn content-targets
  "Distinct (project, article) pairs from the observations, most-attended first.

  Ordered by total observed views so that a run cut short by its budget has
  fetched the things the world was actually looking at, rather than an
  arbitrary prefix. `:qid` travels along where known so the corpus can be
  joined to the entity records without a second lookup."
  [datoms]
  (->> (filter :hayari/observed-on datoms)
       (group-by (juxt :hayari/project :hayari/article))
       (map (fn [[[project article] rs]]
              {:project project
               :article article
               :qid     (some :hayari/wikidata-qid rs)
               :views   (reduce + 0 (map #(or (:hayari/views %) 0) rs))}))
       (sort-by (comp - :views))
       vec))

(defn entity-targets
  "Distinct Wikidata QIDs from the observations, most-attended first."
  [datoms]
  (->> (filter :hayari/wikidata-qid datoms)
       (group-by :hayari/wikidata-qid)
       (map (fn [[qid rs]] {:qid qid :views (reduce + 0 (map #(or (:hayari/views %) 0) rs))}))
       (sort-by (comp - :views))
       vec))

(def content-license
  "Wikipedia prose is CC BY-SA 4.0 and requires attribution and share-alike.
  Wikidata is CC0. Recording that PER RECORD rather than once in a README is
  the difference between a corpus someone can lawfully reuse and a pile of text
  whose terms have to be reconstructed later."
  {:wikipedia {:license "CC-BY-SA-4.0"
               :license-url "https://creativecommons.org/licenses/by-sa/4.0/"
               :attribution "Wikipedia contributors"}
   :wikidata  {:license "CC0-1.0"
               :license-url "https://creativecommons.org/publicdomain/zero/1.0/"
               :attribution "Wikidata contributors"}})

(defn corpus-coverage
  "What the corpus run reached and what it did not."
  [{:keys [content-requested content-fetched content-failed content-skipped
           entity-requested entity-fetched entity-failed entity-skipped
           retrieved-on]}]
  {:corpus/retrieved-on        retrieved-on
   :content/requested          content-requested
   :content/fetched            content-fetched
   :content/failed             content-failed
   ;; Skipped means the wall-clock budget ended before we asked. Targets are
   ;; ordered by attention, so a skipped tail is the least-looked-at material —
   ;; but it is still absent, and absence gets a number.
   :content/skipped-budget     content-skipped
   :entity/requested           entity-requested
   :entity/fetched             entity-fetched
   :entity/failed              entity-failed
   :entity/skipped-budget      entity-skipped
   :corpus/degraded?           (boolean (or (pos? (or content-failed 0))
                                            (pos? (or entity-failed 0))
                                            (pos? (or content-skipped 0))
                                            (pos? (or entity-skipped 0))))
   :content/license            (get-in content-license [:wikipedia :license])
   :entity/license             (get-in content-license [:wikidata :license])
   :corpus/note
   (str "Extracts are the lead paragraph the summary endpoint returns, not full "
        "article text: they are short, they are what a reader sees first, and "
        "storing whole articles would put a mirror of Wikipedia in a git "
        "checkout for no analytical gain. Share-alike applies to the extracts.")})

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
                  (:domain r)         (assoc :hayari/domain (:domain r))
                  ;; P31 says "television series" for a Japanese drama AND for a
                  ;; Korean one AND for anything else broadcast; measured
                  ;; 2026-08-10, 進撃の巨人 and 鬼滅の刃 are both "manga series"
                  ;; while ひよっこ and 愛の不時着 are both "television series".
                  ;; Genre is the axis that actually separates ドラマ from アニメ.
                  (seq (:genres r))   (assoc :hayari/genres (:genres r))
                  (seq (:genre-qids r)) (assoc :hayari/genre-qids (:genre-qids r))
                  ;; A person's P31 is always Q5. Occupation is the only thing
                  ;; that distinguishes an actor from a politician from an
                  ;; athlete, and people were the largest class every day.
                  (seq (:occupations r)) (assoc :hayari/occupations (:occupations r))
                  (seq (:occupation-qids r)) (assoc :hayari/occupation-qids (:occupation-qids r))
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
