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

(defn fetch-claims
  "P31 / P577 / P495 for up to 50 QIDs."
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

(defn -main [& argv]
  (let [opts       (parse-args argv)
        root       (or (:root opts) repo-root)
        date-str   (or (:date opts) (default-date))
        top-n      (js/parseInt (or (:top opts) "25") 10)
        out-path   (or (:out opts) (path/join root "data" "hayari.datoms.edn"))
        regions    (read-edn (path/join root "data" "m49-regions.edn"))
        kinds      (read-edn (path/join root "data" "kinds.edn"))
        countries  (if-let [c (:countries opts)]
                     (str/split c #",")
                     (vec (keys regions)))
        budget-ms  (js/parseInt (or (:budget-ms opts) (str default-budget-ms)) 10)
        ymd*       (ymd date-str)]
    (reset! deadline (+ (js/Date.now) budget-ms))
    (println (str "hayari: " date-str " · " (count countries) " countries · top " top-n
                  " · budget " (quot budget-ms 1000) "s"))
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
              (println (str "  attention: " (count ok-rows) " rows from "
                            (count (distinct (map :country ok-rows))) " countries · "
                            (count no-data) " countries with no data"
                            (when (seq skipped-c)
                              (str " · " (count skipped-c) " countries SKIPPED (budget)"))))
              (-> (pmap-limited api-concurrency (fn [[proj titles]] (fetch-qids proj titles)) batches)
                  (.then (fn [results]
                           (let [title->qid   (apply merge {} (map :qids results))
                                 qid-failed   (reduce + 0 (map :failed results))
                                 qid-skipped  (reduce + 0 (keep :skipped results))
                                 qids         (vec (distinct (vals title->qid)))]
                             (println (str "  qid: " (count title->qid) "/" (count ok-rows) " resolved"
                                           (when (pos? qid-failed)
                                             (str " · " qid-failed " titles in FAILED batches"))
                                           (when (pos? qid-skipped)
                                             (str " · " qid-skipped " titles SKIPPED (budget)"))))
                             (-> (pmap-limited api-concurrency fetch-claims (partition-all 50 qids))
                                 (.then (fn [claim-results]
                                          {:rows        ok-rows
                                           :no-data     no-data
                                           :skipped-c   skipped-c
                                           :title->qid  title->qid
                                           :qid-failed  qid-failed
                                           :qid-skipped qid-skipped
                                           :claim-failed (reduce + 0 (map :failed claim-results))
                                           :claim-skipped (reduce + 0 (keep :skipped claim-results))
                                           :claims      (apply merge {} (map :claims claim-results))}))))))
                  (.then
                    (fn [{:keys [rows no-data skipped-c title->qid claims
                                 qid-failed claim-failed qid-skipped claim-skipped]}]
                      (let [enriched
                            (mapv (fn [r]
                                    (let [qid  (get title->qid [(:project r) (:article r)])
                                          c    (get claims qid)
                                          reg  (:ok (core/region-of (:country r) regions))
                                          kind (when c (:ok (core/classify-kind (:p31 c) kinds)))
                                          yr   (when c (:ok (core/publication-year (:p577 c))))
                                          era  (when c (:ok (core/work-era (:p577 c))))]
                                      (cond-> r
                                        reg  (merge (select-keys reg [:region-m49 :region-name
                                                                      :subregion-m49 :subregion-name]))
                                        qid  (assoc :qid qid)
                                        kind (assoc :kind kind)
                                        yr   (assoc :work-year yr)
                                        era  (assoc :work-era era)
                                        (:p495 c) (assoc :origin-qid (:p495 c)))))
                                  rows)
                            lifts   (core/cross-country-lift enriched)
                            lift-ix (into {} (map (fn [l] [[(:country l) (:project l) (:article l)] l]) lifts))
                            final   (mapv (fn [r]
                                            (let [l (get lift-ix [(:country r) (:project r) (:article r)])]
                                              (cond-> r
                                                l (assoc :lift (:lift l) :observed-in (:observed-in l)))))
                                          enriched)
                            cov (core/coverage-report
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
                                   :claim-skipped       claim-skipped})
                            datoms (core/->datoms
                                     {:observed-on date-str
                                      :rows        final
                                      :coverage    cov
                                      :source-urls [(pageviews-url "<ISO2>" ymd*)
                                                    "https://<project>.org/w/api.php?action=query&prop=pageprops"
                                                    "https://www.wikidata.org/w/api.php?action=wbgetentities"]})]
                        (fs/mkdirSync (path/dirname out-path) #js {:recursive true})
                        (fs/writeFileSync
                          out-path
                          (str ";; hayari 流行 — observed " date-str ". GENERATED by src/hayari/collect.cljs.\n"
                               ";; Entity -1 is the coverage report; read it before quoting any count.\n"
                               "[\n"
                               (str/join "\n" (map pr-str datoms))
                               "\n]\n"))
                        (println (str "  wrote " out-path " — " (count datoms) " datoms"))
                        (println (str "  kind: " (:kind/classified cov) " classified, "
                                      (:kind/unclassified cov) " unclassified"))
                        (println (str "  era:  " (:era/dated cov) " dated, "
                                      (:era/undated cov) " undated"))
                        (when (:enrichment/degraded? cov)
                          (println (str "  DEGRADED: failed batches "
                                        (:qid/batch-failed cov) " titles / "
                                        (:claim/batch-failed cov) " qids · budget-skipped "
                                        (count (:countries/skipped-budget cov)) " countries / "
                                        (:qid/skipped-budget cov) " titles / "
                                        (:claim/skipped-budget cov) " qids"
                                        " — the observation is partial and says so")))
                        (println "  audience-generation: :uncomputable-until-measured"))))))))
        (.catch (fn [e]
                  (js/console.error "hayari collect failed:" (str e))
                  (set! (.-exitCode js/process) 1))))))

;; nbb exposes the script's own arguments here; slicing process.argv by a fixed
;; offset breaks depending on how nbb was invoked.
(apply -main *command-line-args*)
