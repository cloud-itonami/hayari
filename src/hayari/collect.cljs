#!/usr/bin/env nbb
(ns hayari.collect
  "hayari 流行 — the EFFECTS layer. Probes the world, hands observations to the
  pure core (`hayari.core`), writes the datom plane.

  Everything that touches the network, the clock or the filesystem lives here;
  every judgement lives in the core. Run:

    nbb --classpath src src/hayari/collect.cljs [--date YYYY-MM-DD] [--top N]
                                                [--countries JP,US,BR] [--out PATH]

  Sources, all public and unauthenticated (probed 2026-08-10):

    1. Wikimedia Analytics  top-per-country pageviews   -> who looked at what, per country
    2. MediaWiki  action=query&prop=pageprops           -> article title -> Wikidata QID
    3. Wikidata   action=wbgetentities&props=claims     -> P31 kind, P577 date, P495 origin

  Wikidata's SPARQL endpoint is deliberately NOT used: it answered 502 when
  probed, and an observatory that silently stops collecting when a flaky
  endpoint is down is the failure mode `manifest/observatories.edn` was created
  to catch."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [nbb.classpath :as cp]))

;; This script must run as plain `nbb src/hayari/collect.cljs`, with no
;; --classpath and from any working directory, because that is how
;; `manifest/observatories.edn`'s runner invokes it: it builds the command as
;; ["nbb" main & args], and nbb requires --classpath BEFORE the script path, so
;; there is no argument the registry could pass to fix it. Measured 2026-08-10:
;; the first registered run died with "Could not find namespace: hayari.core".
;;
;; Both paths below are derived from *file* rather than the process cwd. The
;; registry's own preamble warns about exactly this: shionome and mitooshi were
;; unrunnable because their defaults resolved against the caller's directory.
(def ^:private src-dir (path/dirname (path/dirname *file*)))
(def ^:private repo-root (path/dirname src-dir))
(cp/add-classpath src-dir)

(require '[hayari.core :as core])

(def user-agent
  "Wikimedia requires a UA that identifies the client and a way to reach its
   operator; anonymous clients are rate-limited or blocked."
  "hayari-observatory/0.1 (+https://github.com/cloud-itonami/hayari)")

(def ^:private rest-concurrency
  "wikimedia.org's REST analytics service tolerates modest parallelism."
  4)

(def ^:private api-concurrency
  "api.php is the shared MediaWiki cluster. A 249-country sweep at concurrency 4
   was throttled hard enough that QID resolution fell from 48/50 to 1/874
   (measured 2026-08-10), so this side of the collection stays deliberately
   slower than it could be."
  2)

(def ^:private request-timeout-ms 20000)

(def ^:private default-budget-ms
  "Wall-clock budget for the whole collection, kept under the observatory
   registry's 600s per-actor timeout.

   Measured 2026-08-10: the same 249-country sweep took 1m35s, 3m47s, and then
   overran 10 minutes — the spread is Wikimedia's throttling, not our work. Being
   killed at the timeout is the worst outcome available: exit 124, no file
   written, and a registry line reading `Δbytes=0` that looks identical to a
   collector that never worked at all.

   So the deadline is ours, not the runner's. When it passes, remaining work is
   skipped, counted, and reported as degraded — a partial observation that says
   how partial it is beats no observation."
  480000)

(defonce ^:private deadline (atom nil))

(defn- past-deadline? []
  (and @deadline (> (js/Date.now) @deadline)))

;; ---------------------------------------------------------------------------
;; HTTP
;; ---------------------------------------------------------------------------

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- retry-after-ms [r attempt]
  (let [h (some-> (.-headers r) (.get "retry-after"))
        s (when h (js/parseInt h 10))]
    (if (and s (not (js/isNaN s)))
      (* 1000 (min s 30))
      ;; 1.5s, 4.5s, 13.5s
      (* 1500 (js/Math.pow 3 attempt)))))

(defn fetch-json
  "GET `url` as JSON. Returns a promise of {:ok data} / {:error {...}}.

  Three behaviours here are load-bearing:

  - **A timeout.** `js/fetch` has none by default, so a throttling host that
    stalls the connection instead of refusing it hangs the collector forever.
    That is not hypothetical — it hung a run on 2026-08-10.
  - **429 is retried, with Retry-After honoured.** Rate limiting is the expected
    response to a 249-country sweep, not an exceptional one.
  - **A 404 is not an error.** It is the pageview API saying this country has no
    above-threshold data for this day, which is a coverage fact the caller
    records by name.

  Errors are returned, never thrown, so one bad host cannot end the run."
  ([url] (fetch-json url 0))
  ([url attempt]
   (-> (js/fetch url #js {:headers #js {"User-Agent" user-agent
                                        "Accept" "application/json"}
                          :signal (js/AbortSignal.timeout request-timeout-ms)})
       (.then (fn [r]
                (let [status (.-status r)]
                  (cond
                    (.-ok r) (.then (.json r) (fn [d] {:ok (js->clj d)}))
                    (= 404 status) (js/Promise.resolve
                                     {:error {:reason :no-data :status 404 :url url}})
                    (and (< attempt 3) (or (= 429 status) (<= 500 status)))
                    (.then (sleep (retry-after-ms r attempt))
                           (fn [_] (fetch-json url (inc attempt))))
                    :else (js/Promise.resolve
                            {:error {:reason :http :status status :url url}})))))
       (.catch (fn [e]
                 (if (< attempt 3)
                   (.then (sleep (* 1500 (js/Math.pow 3 attempt)))
                          (fn [_] (fetch-json url (inc attempt))))
                   (js/Promise.resolve
                     {:error {:reason :network :message (str e) :url url}})))))))

(defn- pmap-limited
  "Run `f` over `xs` with at most `limit` in flight. Order is preserved."
  [limit f xs]
  (let [items (vec xs)
        out   (atom (vec (repeat (count items) nil)))
        idx   (atom 0)]
    (letfn [(worker []
              (let [i @idx]
                (if (>= i (count items))
                  (js/Promise.resolve nil)
                  (do (swap! idx inc)
                      (.then (f (nth items i))
                             (fn [r] (swap! out assoc i r) (worker)))))))]
      (if (zero? (count items))
        (js/Promise.resolve [])
        (-> (js/Promise.all (clj->js (vec (repeatedly (min limit (count items)) worker))))
            (.then (fn [_] @out)))))))

;; ---------------------------------------------------------------------------
;; 1. Per-country attention
;; ---------------------------------------------------------------------------

(defn- pageviews-url [iso2 [y m d]]
  (str "https://wikimedia.org/api/rest_v1/metrics/pageviews/top-per-country/"
       iso2 "/all-access/" y "/" m "/" d))

(defn fetch-country
  "Top articles for one country-day, already share-normalised and truncated.

  The API field is `views_ceil` — Wikimedia rounds per-country counts up to a
  bucket for privacy. It is renamed to :views here and the rounding is stated in
  the README rather than being quietly presented as an exact count."
  [iso2 ymd top-n]
  (if (past-deadline?)
    (js/Promise.resolve {:country iso2 :skipped true})
    (-> (fetch-json (pageviews-url iso2 ymd))
      (.then (fn [res]
               (if-let [err (:error res)]
                 {:country iso2 :error err}
                 (let [arts (get-in (:ok res) ["items" 0 "articles"])
                       rows (mapv (fn [a] {:country iso2
                                           :article (get a "article")
                                           :project (get a "project")
                                           :rank    (get a "rank")
                                           :views   (get a "views_ceil")})
                                  arts)
                       shared (:ok (core/attention-shares rows))]
                   {:country iso2
                    :seen    (count rows)
                    :rows    (vec (take top-n shared))})))))))

;; ---------------------------------------------------------------------------
;; 2. Title -> Wikidata QID
;; ---------------------------------------------------------------------------

(defn- api-host [project]
  ;; "ja.wikipedia" -> "ja.wikipedia.org"
  (if (str/ends-with? project ".org") project (str project ".org")))

(defn fetch-qids
  "Resolve up to 50 titles on one project to Wikidata QIDs.

  MediaWiki may normalise a requested title (underscores, case); the
  `normalized` table is followed so a normalised response still maps back to the
  title the pageview API gave us, instead of dropping the row."
  [project titles]
  (if (past-deadline?)
    (js/Promise.resolve {:failed 0 :skipped (count titles)})
    (let [url (str "https://" (api-host project) "/w/api.php"
                 "?action=query&prop=pageprops&ppprop=wikibase_item&format=json&formatversion=2"
                 "&titles=" (js/encodeURIComponent (str/join "|" titles)))]
    (-> (fetch-json url)
        (.then (fn [res]
                 ;; A failed batch returns {:failed n}, never an empty map. An
                 ;; empty map would be indistinguishable from "these 50 titles
                 ;; genuinely have no Wikidata item", and the run would report
                 ;; full coverage of a partial collection.
                 (if-let [err (:error res)]
                   {:failed (count titles) :error err}
                   (let [q     (get-in (:ok res) ["query"])
                         norm  (into {} (map (fn [n] [(get n "to") (get n "from")])
                                             (get q "normalized")))
                         pages (get q "pages")]
                     {:failed 0
                      ;; MediaWiki reports each page's namespace number, and
                      ;; ns 0 is the article namespace. Taking it beats matching
                      ;; title prefixes, which was quietly language-biased: the
                      ;; prefix list caught "Special:Search" and let through
                      ;; 특수:검색, 特殊:近期變更 and Specialis:Quaerere, all of
                      ;; which reached the decay fits as though they were works.
                      :ns
                      (into {}
                            (map (fn [p]
                                   (let [title (get p "title")]
                                     [[project (get norm title title)] (get p "ns")]))
                                 pages))
                      :qids
                      (into {}
                            (keep (fn [p]
                                    (let [title (get p "title")
                                          orig  (get norm title title)
                                          qid   (get-in p ["pageprops" "wikibase_item"])]
                                      (when qid [[project orig] qid])))
                                  pages))}))))))))

;; ---------------------------------------------------------------------------
;; 3. QID -> claims
;; ---------------------------------------------------------------------------

(defn- claim-ids [claims prop]
  (keep #(get-in % ["mainsnak" "datavalue" "value" "id"]) (get claims prop)))

(defn- claim-times [claims prop]
  (keep #(get-in % ["mainsnak" "datavalue" "value" "time"]) (get claims prop)))

(defn fetch-labels
  "English labels for up to 50 QIDs. Returns {:failed n :labels {qid -> label}}.

  Genre and occupation are emitted as readable strings alongside their QIDs.
  Curating those two vocabularies by hand is not an option — there are
  thousands of occupations — and emitting bare QIDs would make the output
  unreadable without a second round trip for every consumer."
  [qids]
  (if (past-deadline?)
    (js/Promise.resolve {:failed 0 :skipped (count qids) :labels {}})
    (let [url (str "https://www.wikidata.org/w/api.php"
                   "?action=wbgetentities&props=labels&languages=en&format=json"
                   "&ids=" (str/join "|" qids))]
      (-> (fetch-json url)
          (.then (fn [res]
                   (if (:error res)
                     {:failed (count qids) :labels {}}
                     {:failed 0
                      :labels (into {}
                                    (keep (fn [[qid ent]]
                                            (when-let [l (get-in ent ["labels" "en" "value"])]
                                              [qid l]))
                                          (get (:ok res) "entities")))})))))))

(defn fetch-claims
  "P31 (kind), P136 (genre), P106 (occupation), P495 (origin) and the date
  properties for up to 50 QIDs.

  P571/P1191/P580 are fetched alongside P577 because most rows carried no
  publication date at all — 765 of 886 on 2026-08-08 — and a work with an
  inception or a first-performance date is dated, just not by P577."
  [qids]
  (if (past-deadline?)
    (js/Promise.resolve {:failed 0 :skipped (count qids)})
    (let [url (str "https://www.wikidata.org/w/api.php"
                 "?action=wbgetentities&props=claims&format=json"
                 "&ids=" (str/join "|" qids))]
    (-> (fetch-json url)
        (.then (fn [res]
                 (if-let [err (:error res)]
                   {:failed (count qids) :error err}
                   {:failed 0
                    :claims
                    (into {}
                          (map (fn [[qid ent]]
                                 (let [c (get ent "claims")]
                                   [qid {:p31   (vec (claim-ids c "P31"))
                                         :p577  (vec (claim-times c "P577"))
                                         :p571  (vec (claim-times c "P571"))
                                         :p1191 (vec (claim-times c "P1191"))
                                         :p580  (vec (claim-times c "P580"))
                                         :p136  (vec (claim-ids c "P136"))
                                         :p106  (vec (claim-ids c "P106"))
                                         :p495  (first (claim-ids c "P495"))}]))
                               (get (:ok res) "entities")))})))))))

;; ---------------------------------------------------------------------------
;; Orchestration
;; ---------------------------------------------------------------------------

(defn- parse-args [argv]
  (loop [a (vec argv) m {}]
    (if (< (count a) 2)
      m
      (recur (vec (drop 2 a))
             (assoc m (keyword (str/replace (first a) #"^--" "")) (second a))))))

(defn- ymd [date-str]
  (let [[y m d] (str/split date-str #"-")] [y m d]))

(defn- default-date []
  ;; Wikimedia publishes per-country aggregates with a lag; two days back is the
  ;; most recent date that was reliably present when probed.
  (let [t (js/Date. (- (.getTime (js/Date.)) (* 2 86400000)))]
    (subs (.toISOString t) 0 10)))

(defn- read-edn [p] (edn/read-string (fs/readFileSync p "utf8")))


(defn- shift-date
  "`date-str` moved by `n` days, as YYYY-MM-DD."
  [date-str n]
  (let [[y m d] (map #(js/parseInt % 10) (str/split date-str #"-"))
        t (js/Date. (js/Date.UTC y (dec m) d))]
    (subs (.toISOString (js/Date. (+ (.getTime t) (* n 86400000)))) 0 10)))

(defn- day-range
  "`days` consecutive dates ending at `date-str`, oldest first."
  [date-str days]
  (mapv #(shift-date date-str (- % (dec days))) (range days)))

;; ---------------------------------------------------------------------------
;; One day
;; ---------------------------------------------------------------------------

(defn collect-day
  "Observe one country-day across the roster. Returns a promise of the datoms
  for that day (coverage entity first)."
  [{:keys [date-str countries top-n regions kinds domains]}]
  (let [ymd* (ymd date-str)]
    (-> (pmap-limited rest-concurrency #(fetch-country % ymd* top-n) countries)
        (.then
          (fn [per-country]
            (let [ok-rows   (mapcat :rows (remove #(or (:error %) (:skipped %)) per-country))
                  no-data   (mapv :country (filter :error per-country))
                  skipped-c (mapv :country (filter :skipped per-country))
                  by-project (group-by :project ok-rows)
                  ;; One title may top many countries' rankings; resolve each
                  ;; (project, title) once, not once per country.
                  batches   (mapcat (fn [[proj rows]]
                                      (map (fn [titles] [proj (vec titles)])
                                           (partition-all 50 (distinct (map :article rows)))))
                                    by-project)]
              (println (str "  " date-str ": " (count ok-rows) " rows from "
                            (count (distinct (map :country ok-rows))) " countries · "
                            (count no-data) " no data"
                            (when (seq skipped-c)
                              (str " · " (count skipped-c) " SKIPPED (budget)"))))
              (-> (pmap-limited api-concurrency (fn [[proj titles]] (fetch-qids proj titles)) batches)
                  (.then (fn [results]
                           (let [title->qid  (apply merge {} (map :qids results))
                                 title->ns   (apply merge {} (map :ns results))
                                 qid-failed  (reduce + 0 (map :failed results))
                                 qid-skipped (reduce + 0 (keep :skipped results))
                                 qids        (vec (distinct (vals title->qid)))]
                             (-> (pmap-limited api-concurrency fetch-claims (partition-all 50 qids))
                                 (.then (fn [claim-results]
                                          (let [claims (apply merge {} (map :claims claim-results))
                                                ;; Only the QIDs we will actually
                                                ;; emit get a label lookup — the
                                                ;; per-row cap is applied first,
                                                ;; so a work with 20 genres costs
                                                ;; 4 labels, not 20.
                                                aux (vec (distinct
                                                           (mapcat (fn [c]
                                                                     (concat (take 4 (:p136 c))
                                                                             (take 4 (:p106 c))))
                                                                   (vals claims))))]
                                            (-> (pmap-limited api-concurrency fetch-labels
                                                              (partition-all 50 aux))
                                                (.then (fn [label-results]
                                                         {:rows ok-rows :no-data no-data
                                                          :skipped-c skipped-c
                                                          :title->qid title->qid :title->ns title->ns
                                                          :qid-failed qid-failed :qid-skipped qid-skipped
                                                          :claim-failed (reduce + 0 (map :failed claim-results))
                                                          :claim-skipped (reduce + 0 (keep :skipped claim-results))
                                                          :claims claims
                                                          :labels (apply merge {} (map :labels label-results))
                                                          :label-failed (reduce + 0 (map :failed label-results))}))))))))))
                  (.then
                    (fn [{:keys [rows no-data skipped-c title->qid title->ns claims labels
                                 qid-failed claim-failed qid-skipped claim-skipped label-failed]}]
                      (let [;; Two rejections the title filter cannot make.
                            ;; ns != 0 is MediaWiki's own answer to "is this an
                            ;; article", in every language at once. P31 catches
                            ;; list and disambiguation pages, which ARE ns 0 and
                            ;; do look like ordinary titles. Both are counted
                            ;; rather than allowed to vanish.
                            non-article? (fn [r]
                                           (when-let [n (get title->ns [(:project r) (:article r)])]
                                             (not= 0 n)))
                            wiki-meta?   (fn [r]
                                           (when-let [c (get claims (get title->qid [(:project r) (:article r)]))]
                                             (core/wikimedia-meta? (:p31 c))))
                            kept     (vec (remove #(or (non-article? %) (wiki-meta? %)) rows))
                            ns-n     (count (filter non-article? rows))
                            meta-n   (- (count rows) (count kept))
                            enriched
                            (mapv (fn [r]
                                    (let [qid  (get title->qid [(:project r) (:article r)])
                                          c    (get claims qid)
                                          reg  (:ok (core/region-of (:country r) regions))
                                          kind (when c (:ok (core/classify-kind (:p31 c) kinds)))
                                          dom  (:ok (core/domain-of kind domains))
                                          dt   (when c (:ok (core/dated-by c)))
                                          gen  (when c (core/labelled (:p136 c) labels))
                                          occ  (when c (core/labelled (:p106 c) labels))]
                                      (cond-> r
                                        reg  (merge (select-keys reg [:region-m49 :region-name
                                                                      :subregion-m49 :subregion-name]))
                                        qid  (assoc :qid qid)
                                        kind (assoc :kind kind)
                                        dom  (assoc :domain dom)
                                        dt   (assoc :work-year (:year dt)
                                                    :work-era  (:era dt)
                                                    :dated-via (:via dt))
                                        (seq (:labels gen)) (assoc :genres (:labels gen)
                                                                   :genre-qids (:qids gen))
                                        (seq (:labels occ)) (assoc :occupations (:labels occ)
                                                                   :occupation-qids (:qids occ))
                                        (:p495 c) (assoc :origin-qid (:p495 c)))))
                                  kept)
                            lifts   (core/cross-country-lift enriched)
                            lift-ix (into {} (map (fn [l] [[(:country l) (:project l) (:article l)] l]) lifts))
                            final   (mapv (fn [r]
                                            (let [l (get lift-ix [(:country r) (:project r) (:article r)])]
                                              (cond-> r
                                                l (assoc :lift (:lift l) :observed-in (:observed-in l)))))
                                          enriched)
                            cov (assoc
                                  (core/coverage-report
                                    {:countries-requested (count countries)
                                     :countries-responded (- (count countries)
                                                             (count no-data) (count skipped-c))
                                     :countries-with-rows (count (distinct (map :country final)))
                                     :countries-no-data   no-data
                                     :titles-seen         (count final)
                                     :qid-resolved        (count (filter :qid final))
                                     :qid-unresolved      (count (remove :qid final))
                                     :kind-classified     (count (filter :kind final))
                                     :kind-unclassified   (count (remove :kind final))
                                     :era-dated           (count (filter :work-era final))
                                     :era-undated         (count (remove :work-era final))
                                     :qid-batch-failed    qid-failed
                                     :claim-batch-failed  claim-failed
                                     :countries-skipped   skipped-c
                                     :qid-skipped         qid-skipped
                                     :claim-skipped       claim-skipped
                                     :label-batch-failed  label-failed
                                     :domain-mapped       (count (filter :domain final))
                                     :domain-unmapped     (count (filter #(and (:kind %) (not (:domain %))) final))
                                     :genre-labelled      (count (filter :genres final))
                                     :occupation-labelled (count (filter :occupations final))})
                                  :titles/non-article-dropped meta-n
                                  :titles/dropped-by-namespace ns-n
                                  :titles/dropped-by-p31 (- meta-n ns-n))]
                        (println (str "    qid " (count title->qid) "/" (count rows)
                                      " · kind " (:kind/classified cov)
                                      " · domain " (:domain/mapped cov)
                                      " · genre " (:genre/labelled cov)
                                      " · occ " (:occupation/labelled cov)
                                      " · era " (:era/dated cov)
                                      " · dropped " meta-n " (ns " ns-n ")"
                                      (when (:enrichment/degraded? cov) " · DEGRADED")))
                        (core/->datoms
                          {:observed-on date-str
                           :rows        final
                           :coverage    cov
                           :source-urls [(pageviews-url "<ISO2>" ymd*)
                                         "https://<project>.org/w/api.php?action=query&prop=pageprops"
                                         "https://www.wikidata.org/w/api.php?action=wbgetentities"]})))))))))))

;; ---------------------------------------------------------------------------
;; Entry
;; ---------------------------------------------------------------------------

(defn -main [& argv]
  (let [opts      (parse-args argv)
        root      (or (:root opts) repo-root)
        date-str  (or (:date opts) (default-date))
        days      (max 1 (js/parseInt (or (:days opts) "1") 10))
        top-n     (js/parseInt (or (:top opts) "25") 10)
        out-path  (or (:out opts) (path/join root "data" "hayari.datoms.edn"))
        ;; The committed half. data/hayari.datoms.edn is gitignored, so without
        ;; this the :source/dataset "hayari" on every row claims membership of a
        ;; query plane that has nothing to load.
        sum-path  (or (:summary-out opts) (path/join root "data" "hayari-summary.edn"))
        regions   (read-edn (path/join root "data" "m49-regions.edn"))
        kinds     (read-edn (path/join root "data" "kinds.edn"))
        domains   (read-edn (path/join root "data" "domains.edn"))
        countries (if-let [c (:countries opts)]
                    (str/split c #",")
                    (vec (keys regions)))
        budget-ms (js/parseInt (or (:budget-ms opts) (str default-budget-ms)) 10)
        ;; The per-year axis runs from 1900 by default. Works older than that
        ;; are not dropped — they are counted as :outside-range, so the choice
        ;; of floor stays visible instead of quietly deleting antiquity.
        era-from  (js/parseInt (or (:era-from opts) "1900") 10)
        era-to    (js/parseInt (or (:era-to opts)
                                   (subs (.toISOString (js/Date.)) 0 4)) 10)
        dates     (day-range date-str days)
        ;; Existing observations are read BEFORE anything is fetched, so a run
        ;; that dies partway cannot destroy the history it was going to extend.
        prior     (if (fs/existsSync out-path) (read-edn out-path) [])]
    (reset! deadline (+ (js/Date.now) budget-ms))
    (println (str "hayari: " (first dates) (when (< 1 days) (str " .. " (last dates)))
                  " · " (count countries) " countries · top " top-n
                  " · budget " (quot budget-ms 1000) "s"
                  " · prior " (count prior) " datoms"))
    (-> (reduce (fn [p d]
                  (.then p (fn [acc]
                             (.then (collect-day {:date-str d :countries countries :top-n top-n
                                                  :regions regions :kinds kinds :domains domains})
                                    (fn [ds] (into acc ds))))))
                (js/Promise.resolve [])
                dates)
        (.then
          (fn [fresh]
            (let [{:keys [coverage rows]} (core/merge-observations prior fresh)
                  ;; Computed over the ACCUMULATED set, not this run's rows: the
                  ;; per-year axis is a property of everything collected so far,
                  ;; and recomputing it per run is what keeps it true as history
                  ;; grows.
                  eras (core/year-coverage rows era-from era-to)
                  era-entity {:source/dataset "hayari"
                              :hayari.era-coverage/from (:from eras)
                              :hayari.era-coverage/to (:to eras)
                              :hayari.era-coverage/years-populated (:years-populated eras)
                              :hayari.era-coverage/years-empty (:years-empty eras)
                              :hayari.era-coverage/oldest-year (:oldest-year eras)
                              :hayari.era-coverage/newest-year (:newest-year eras)
                              :hayari.era-coverage/works-total (:works-total eras)
                              :hayari.era-coverage/outside-range (:outside-range eras)
                              :hayari.era-coverage/by-year (pr-str (:by-year eras))
                              :hayari.era-coverage/note
                              (str "Distinct works per single year of first publication. "
                                   "hayari measures TODAY's attention, which is mostly on "
                                   "recent things, so empty years are expected and are listed "
                                   "with zeros rather than omitted. Depth is the lever that "
                                   "reaches older work: measured 2026-08-08, JP at --top 400 "
                                   "yielded 34 pre-2000 works in one day where --top 25 "
                                   "yielded almost none.")}
                  all  (core/renumber (concat coverage [era-entity] rows))
                  days-held (sort (distinct (keep :hayari/observed-on all)))]
              (fs/mkdirSync (path/dirname out-path) #js {:recursive true})
              (fs/writeFileSync
                out-path
                (str ";; hayari 流行 — GENERATED by src/hayari/collect.cljs. Do not hand-edit.\n"
                     ";; Accumulates across runs: one coverage entity per observed day,\n"
                     ";; then the observations. Read the coverage entity for a day before\n"
                     ";; quoting any count from that day.\n"
                     ";; days held: " (count days-held)
                     (when (seq days-held) (str " (" (first days-held) " .. " (last days-held) ")")) "\n"
                     "[\n" (str/join "\n" (map pr-str all)) "\n]\n"))
              (println (str "  wrote " out-path " — " (count all) " datoms across "
                            (count days-held) " day(s): "
                            (str/join ", " days-held)))
              (let [;; Union with what is already committed. Re-deriving the
                    ;; summary from the raw alone destroyed five days of history
                    ;; on 2026-08-10: the raw is gitignored and the tick runs in
                    ;; a fresh clone.
                    prior-sum (if (fs/existsSync sum-path) (read-edn sum-path) [])
                    summary   (core/merge-summaries prior-sum (core/country-day-summary rows))
                    ec        (core/era-coverage-from-summary summary era-from era-to)
                    era-ent   {:source/dataset "hayari"
                               :hayari.era-coverage/from (:from ec)
                               :hayari.era-coverage/to (:to ec)
                               :hayari.era-coverage/years-populated (:years-populated ec)
                               :hayari.era-coverage/years-empty (:years-empty ec)
                               :hayari.era-coverage/oldest-year (:oldest-year ec)
                               :hayari.era-coverage/newest-year (:newest-year ec)
                               :hayari.era-coverage/country-days-by-year
                               (pr-str (:country-days-by-year ec))
                               :hayari.era-coverage/note
                               (str "Country-days in which a work first published in that year "
                                    "appeared, rebuilt from the committed summary so the axis does "
                                    "not shrink to whatever raw the current machine holds. NOT a "
                                    "catalogue count: a film seen in forty countries contributes "
                                    "forty country-days. :years-populated is exact either way. "
                                    "hayari measures today's attention, so empty years are "
                                    "expected and are listed with zeros.")}]
                (fs/writeFileSync
                  sum-path
                  (str ";; hayari 流行 — committed summary. GENERATED by src/hayari/collect.cljs.\n"
                       ";; One entity per (country, day), plus the era-coverage entity. This is the\n"
                       ";; part small enough to keep in git, and therefore the part the workspace\n"
                       ";; query plane (manifest/edn-query.cljs) can actually load — the raw\n"
                       ";; observations beside it are gitignored, so for five waves every row\n"
                       ";; carried :source/dataset \"hayari\" for a plane that had nothing to read.\n"
                       ";; :hayari.summary/country-iso2 joins to the first two characters of\n"
                       ";; :company/jurisdiction in the LEI plane (ISO 3166-1 alpha-2).\n"
                       "[\n"
                       (str/join "\n" (map pr-str (cons era-ent summary)))
                       "\n]\n"))
                (println (str "  summary " sum-path " — " (count summary) " country-days · "
                              "years [committed, all days]: " (:years-populated ec)
                              " populated / " (:years-empty ec) " empty"
                              (when-let [o (:oldest-year ec)] (str " · oldest " o)))))
              ;; Two era views exist and they measure different things, so the
              ;; line says which. This one is over the raw THIS machine holds
              ;; (distinct works); the summary file carries the committed view
              ;; over every day ever observed (country-days).
              (println (str "  years " era-from "-" era-to " [this machine's raw]: "
                            (:years-populated eras) " populated / "
                            (:years-empty eras) " empty · "
                            (:works-total eras) " distinct works"
                            (when-let [o (:oldest-year eras)] (str " · oldest " o))
                            (when (pos? (:outside-range eras))
                              (str " · " (:outside-range eras) " dated outside the range"))))
              (println "  audience-generation: :uncomputable-until-measured"))))
        (.catch (fn [e]
                  (js/console.error "hayari collect failed:" (str e))
                  (set! (.-exitCode js/process) 1))))))

;; nbb exposes the script's own arguments here; slicing process.argv by a fixed
;; offset breaks depending on how nbb was invoked.
(apply -main *command-line-args*)
