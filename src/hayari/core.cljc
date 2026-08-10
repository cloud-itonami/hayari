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

;; ---------------------------------------------------------------------------
;; Content kind
;; ---------------------------------------------------------------------------

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
  [{:keys [countries-requested countries-with-data countries-no-data
           titles-seen qid-resolved qid-unresolved
           kind-classified kind-unclassified era-dated era-undated
           qid-batch-failed claim-batch-failed]}]
  {:countries/requested   countries-requested
   :countries/with-data   countries-with-data
   :countries/no-data     (vec countries-no-data)
   :titles/seen           titles-seen
   :qid/resolved          qid-resolved
   :qid/unresolved        qid-unresolved
   ;; A title inside a failed batch is NOT the same fact as a title that has no
   ;; Wikidata item, and collapsing the two is how a throttled run reports full
   ;; coverage. Measured 2026-08-10: throttling silently cut resolution to
   ;; 1/874 while every other count still looked healthy.
   :qid/batch-failed      (or qid-batch-failed 0)
   :claim/batch-failed    (or claim-batch-failed 0)
   :enrichment/degraded?  (boolean (or (pos? (or qid-batch-failed 0))
                                       (pos? (or claim-batch-failed 0))))
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
                  (:work-era r)       (assoc :hayari/work-era (:work-era r))
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
