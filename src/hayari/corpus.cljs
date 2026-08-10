#!/usr/bin/env nbb
(ns hayari.corpus
  "hayari 流行 — fetch the actual CONTENT and the ENTITY records behind the
  observations.

    nbb src/hayari/corpus.cljs [--content-limit 2000] [--entity-limit 4000]
                               [--budget-ms 900000] [--data PATH]

  The collector records that a country looked at something. This fetches what
  that something IS: the lead extract from Wikipedia, and the Wikidata entity
  with its labels and descriptions.

  Two things are deliberate and load-bearing.

  **Licence travels with every record.** Wikipedia prose is CC BY-SA 4.0 —
  attribution and share-alike — while Wikidata is CC0. Stamping that per record
  rather than once in a README is the difference between a corpus someone can
  lawfully reuse and a pile of text whose terms have to be reconstructed later.

  **Extracts, not whole articles.** The summary endpoint returns the lead
  paragraph; measured 2026-08-10, 167 characters for a feature film. Storing
  full article text would put a mirror of Wikipedia in a git checkout and buy
  no analytical power the extract does not already give.

  Targets are ordered by observed attention, so a run cut short by its budget
  has fetched what the world was actually looking at — and says how much it
  did not reach."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [nbb.classpath :as cp]))

(def ^:private src-dir   (path/dirname (path/dirname *file*)))
(def ^:private repo-root (path/dirname src-dir))
(cp/add-classpath src-dir)

(require '[hayari.core :as core])

(def user-agent "hayari-observatory/0.1 (+https://github.com/cloud-itonami/hayari)")

(def ^:private rest-concurrency 4)
(def ^:private api-concurrency 2)
(def ^:private request-timeout-ms 20000)
(def ^:private default-budget-ms 900000)

(defonce ^:private deadline (atom nil))
(defn- past-deadline? [] (and @deadline (> (js/Date.now) @deadline)))

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn fetch-json
  ([url] (fetch-json url 0))
  ([url attempt]
   (-> (js/fetch url #js {:headers #js {"User-Agent" user-agent
                                        "Accept" "application/json"}
                          :signal (js/AbortSignal.timeout request-timeout-ms)})
       (.then (fn [r]
                (let [status (.-status r)]
                  (cond
                    (.-ok r) (.then (.json r) (fn [d] {:ok (js->clj d)}))
                    ;; A 404 here means the title no longer resolves — renamed,
                    ;; merged or deleted since the day it was observed. That is
                    ;; a fact about the article, not a failure of the run.
                    (= 404 status) (js/Promise.resolve {:error {:reason :gone :status 404}})
                    (and (< attempt 3) (or (= 429 status) (<= 500 status)))
                    (.then (sleep (* 1500 (js/Math.pow 3 attempt)))
                           (fn [_] (fetch-json url (inc attempt))))
                    :else (js/Promise.resolve {:error {:reason :http :status status}})))))
       (.catch (fn [e]
                 (if (< attempt 3)
                   (.then (sleep (* 1500 (js/Math.pow 3 attempt)))
                          (fn [_] (fetch-json url (inc attempt))))
                   (js/Promise.resolve {:error {:reason :network :message (str e)}})))))))

(defn- pmap-limited [limit f xs]
  (let [items (vec xs) out (atom (vec (repeat (count items) nil))) idx (atom 0)]
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
;; Content
;; ---------------------------------------------------------------------------

(defn- api-host [project]
  (if (str/ends-with? project ".org") project (str project ".org")))

(defn fetch-summary
  "Lead extract and page metadata for one (project, article)."
  [{:keys [project article qid]} retrieved-on]
  (if (past-deadline?)
    (js/Promise.resolve {:skipped true})
    (let [url (str "https://" (api-host project) "/api/rest_v1/page/summary/"
                   (js/encodeURIComponent article))]
      (-> (fetch-json url)
          (.then (fn [res]
                   (if (:error res)
                     {:failed 1 :error (:error res)}
                     (let [d (:ok res)
                           lic (:wikipedia core/content-license)]
                       {:record
                        (cond-> {:source/dataset            "hayari"
                                 :hayari.content/project    project
                                 :hayari.content/article    article
                                 :hayari.content/lang       (get d "lang")
                                 :hayari.content/type       (get d "type")
                                 :hayari.content/extract    (get d "extract")
                                 :hayari.content/extract-chars (count (or (get d "extract") ""))
                                 :hayari.content/retrieved-on retrieved-on
                                 :hayari.content/license     (:license lic)
                                 :hayari.content/license-url (:license-url lic)
                                 :hayari.content/attribution
                                 (str (:attribution lic) ", " (api-host project))}
                          (get d "description")
                          (assoc :hayari.content/description (get d "description"))
                          (or qid (get d "wikibase_item"))
                          (assoc :hayari.content/wikidata-qid (or qid (get d "wikibase_item")))
                          (get-in d ["content_urls" "desktop" "page"])
                          (assoc :hayari.content/canonical-url
                                 (get-in d ["content_urls" "desktop" "page"]))
                          ;; Thumbnail URL only. The image itself is a separate
                          ;; licence per file and often not CC BY-SA; storing the
                          ;; bytes would import that problem into this corpus.
                          (get-in d ["thumbnail" "source"])
                          (assoc :hayari.content/thumbnail-url (get-in d ["thumbnail" "source"]))
                          (get d "revision")
                          (assoc :hayari.content/revision (str (get d "revision")))
                          (get d "timestamp")
                          (assoc :hayari.content/revised-at (get d "timestamp")))}))))))))

;; ---------------------------------------------------------------------------
;; Entities
;; ---------------------------------------------------------------------------

(defn fetch-entities
  "Labels, descriptions and sitelink count for up to 50 QIDs.

  `languages` is filtered: unfiltered labels for one entity measured 7,011
  bytes against 438 filtered, and `props=claims` measured 109,873 — the claims
  this observatory needs (P31/P136/P106/P577/P495) are already extracted during
  collection, so re-fetching them here would be 250x the payload for data we
  hold.

  Sitelinks are fetched and then REDUCED TO A COUNT. The count is a useful
  notability signal; the 300-language list is 5 KB per entity of data nothing
  here asks a question about."
  [qids retrieved-on]
  (if (past-deadline?)
    (js/Promise.resolve {:skipped (count qids) :records []})
    (let [url (str "https://www.wikidata.org/w/api.php"
                   "?action=wbgetentities&props=labels|descriptions|sitelinks"
                   "&languages=en|ja&format=json&ids=" (str/join "|" qids))
          lic (:wikidata core/content-license)]
      (-> (fetch-json url)
          (.then (fn [res]
                   (if (:error res)
                     {:failed (count qids) :records []}
                     {:failed 0
                      :records
                      (vec (for [[qid ent] (get (:ok res) "entities")]
                             (cond-> {:source/dataset             "hayari"
                                      :hayari.entity/qid          qid
                                      :hayari.entity/sitelink-count (count (get ent "sitelinks"))
                                      :hayari.entity/retrieved-on retrieved-on
                                      :hayari.entity/license      (:license lic)
                                      :hayari.entity/license-url  (:license-url lic)
                                      :hayari.entity/attribution  (:attribution lic)
                                      :hayari.entity/source-url   (str "https://www.wikidata.org/wiki/" qid)}
                               (get-in ent ["labels" "en" "value"])
                               (assoc :hayari.entity/label-en (get-in ent ["labels" "en" "value"]))
                               (get-in ent ["labels" "ja" "value"])
                               (assoc :hayari.entity/label-ja (get-in ent ["labels" "ja" "value"]))
                               (get-in ent ["descriptions" "en" "value"])
                               (assoc :hayari.entity/description-en
                                      (get-in ent ["descriptions" "en" "value"]))
                               (get-in ent ["descriptions" "ja" "value"])
                               (assoc :hayari.entity/description-ja
                                      (get-in ent ["descriptions" "ja" "value"])))))})))))))

;; ---------------------------------------------------------------------------

(defn- parse-args [argv]
  (loop [a (vec argv) m {}]
    (if (< (count a) 2) m
        (recur (vec (drop 2 a))
               (assoc m (keyword (str/replace (first a) #"^--" "")) (second a))))))

(defn- read-edn [p] (edn/read-string (fs/readFileSync p "utf8")))

(defn- merge-by [k prior fresh]
  (vec (sort-by #(str (get % k)) (vals (merge (into {} (map (juxt k identity) prior))
                                              (into {} (map (juxt k identity) fresh)))))))

(defn top-entities!
  "Fetch Wikidata records for the works the committed summary names as each
  country-day's most-viewed, and commit THOSE.

  Scope chosen by measurement: 618 country-days name only 209 distinct works,
  because many countries look at the same thing on the same day. At ~550 bytes
  an entity that is ~115 KB — small enough to track, and it makes every row the
  query plane can see self-describing instead of a bare QID.

  Entities only, never the extracts. Wikidata is CC0; Wikipedia prose is
  CC BY-SA, and committing the extracts here would hand every downstream reader
  of this repo a share-alike obligation they did not ask for. The extracts are
  still fetched by the full corpus run — they just stay out of the tracked,
  plane-visible slice."
  [{:keys [summary-path out]}]
  (if-not (fs/existsSync summary-path)
    (do (println (str "hayari top-entities: no summary at " summary-path))
        (set! (.-exitCode js/process) 1))
    (let [summary (read-edn summary-path)
          want    (vec (distinct (keep :hayari.summary/top-qid summary)))
          prior   (if (fs/existsSync out) (read-edn out) [])
          have    (set (keep :hayari.entity/qid prior))
          missing (vec (remove have want))
          today   (subs (.toISOString (js/Date.)) 0 10)]
      (println (str "hayari top-entities: " (count want) " named · "
                    (count have) " held · " (count missing) " to fetch"))
      (if (empty? missing)
        (println "  nothing new — no write")
        (-> (pmap-limited api-concurrency #(fetch-entities % today)
                          (partition-all 50 missing))
            (.then (fn [res]
                     (let [fresh  (vec (mapcat :records res))
                           failed (reduce + 0 (keep :failed res))
                           merged (merge-by :hayari.entity/qid prior fresh)]
                       (fs/writeFileSync
                         out
                         (str ";; hayari 流行 — the works each country-day looked at most.\n"
                              ";; GENERATED by src/hayari/corpus.cljs --only top-entities.\n"
                              ";; CC0-1.0 (Wikidata contributors). Entities only: Wikipedia prose is\n"
                              ";; CC BY-SA and committing it here would impose share-alike on every\n"
                              ";; reader of this repo. Join on :hayari.summary/top-qid.\n"
                              "[\n" (str/join "\n" (map pr-str merged)) "\n]\n"))
                       (println (str "  fetched " (count fresh) " · failed " failed
                                     " · " (count merged) " held → " out)))))
            (.catch (fn [e] (js/console.error "top-entities failed:" (str e))
                      (set! (.-exitCode js/process) 1)))))))) 

(defn -main [& argv]
  (let [opts    (parse-args argv)
        data    (or (:data opts) (path/join repo-root "data" "hayari.datoms.edn"))
        c-out   (or (:content-out opts) (path/join repo-root "data" "hayari-content.edn"))
        e-out   (or (:entity-out opts) (path/join repo-root "data" "hayari-entities.edn"))
        c-limit (js/parseInt (or (:content-limit opts) "2000") 10)
        e-limit (js/parseInt (or (:entity-limit opts) "4000") 10)
        budget  (js/parseInt (or (:budget-ms opts) (str default-budget-ms)) 10)
        today   (subs (.toISOString (js/Date.)) 0 10)]
    (if (= "top-entities" (:only opts))
      (do (reset! deadline (+ (js/Date.now) budget))
          (top-entities!
            {:summary-path (or (:summary opts) (path/join repo-root "data" "hayari-summary.edn"))
             :out (or (:top-entities-out opts)
                      (path/join repo-root "data" "hayari-top-entities.edn"))}))
      (if-not (fs/existsSync data)
      (do (println (str "hayari corpus: no observations at " data " — run collect first"))
          (set! (.-exitCode js/process) 1))
      (let [datoms   (read-edn data)
            c-all    (core/content-targets datoms)
            e-all    (core/entity-targets datoms)
            c-todo   (vec (take c-limit c-all))
            e-todo   (vec (take e-limit e-all))
            c-prior  (if (fs/existsSync c-out) (read-edn c-out) [])
            e-prior  (if (fs/existsSync e-out) (read-edn e-out) [])]
        (reset! deadline (+ (js/Date.now) budget))
        (println (str "hayari corpus: " (count c-todo) "/" (count c-all) " articles · "
                      (count e-todo) "/" (count e-all) " entities · budget "
                      (quot budget 1000) "s"))
        (-> (pmap-limited rest-concurrency #(fetch-summary % today) c-todo)
            (.then (fn [c-res]
                     (.then (pmap-limited api-concurrency
                                          #(fetch-entities % today)
                                          (partition-all 50 (map :qid e-todo)))
                            (fn [e-res] [c-res e-res]))))
            (.then
              (fn [[c-res e-res]]
                (let [c-new  (vec (keep :record c-res))
                      c-fail (reduce + 0 (keep :failed c-res))
                      c-skip (count (filter :skipped c-res))
                      e-new  (vec (mapcat :records e-res))
                      e-fail (reduce + 0 (keep :failed e-res))
                      e-skip (reduce + 0 (keep :skipped e-res))
                      cov (core/corpus-coverage
                            {:content-requested (count c-todo) :content-fetched (count c-new)
                             :content-failed c-fail :content-skipped c-skip
                             :entity-requested (count e-todo) :entity-fetched (count e-new)
                             :entity-failed e-fail :entity-skipped e-skip
                             :retrieved-on today})
                      content (merge-by :hayari.content/canonical-url c-prior c-new)
                      entities (merge-by :hayari.entity/qid e-prior e-new)]
                  (fs/mkdirSync (path/dirname c-out) #js {:recursive true})
                  (fs/writeFileSync
                    c-out
                    (str ";; hayari 流行 — article extracts. GENERATED by src/hayari/corpus.cljs.\n"
                         ";; TEXT IS CC BY-SA 4.0 (Wikipedia contributors). Attribution and\n"
                         ";; share-alike apply to anything derived from these extracts; the terms\n"
                         ";; are also stamped on every record so they survive being excerpted.\n"
                         ";; Lead extracts only — never full article text.\n"
                         "[\n" (str/join "\n" (map pr-str content)) "\n]\n"))
                  (fs/writeFileSync
                    e-out
                    (str ";; hayari 流行 — Wikidata entity records. GENERATED by src/hayari/corpus.cljs.\n"
                         ";; CC0-1.0 (Wikidata contributors). Labels/descriptions in en+ja and a\n"
                         ";; sitelink COUNT; claims are not re-fetched here because collect.cljs\n"
                         ";; already extracts the ones this observatory asks questions about.\n"
                         "[\n" (str/join "\n" (map pr-str entities)) "\n]\n"))
                  (println (str "  content: " (count c-new) " fetched · " c-fail " failed · "
                                c-skip " skipped → " (count content) " held"))
                  (println (str "  entity:  " (count e-new) " fetched · " e-fail " failed · "
                                e-skip " skipped → " (count entities) " held"))
                  (when (:corpus/degraded? cov)
                    (println "  DEGRADED: the corpus is partial and the counts above say by how much"))
                  (println (str "  licences: content " (:content/license cov)
                                " (attribution + share-alike) · entity " (:entity/license cov)))
                  (println (str "  wrote " c-out " / " e-out)))))
            (.catch (fn [e]
                      (js/console.error "hayari corpus failed:" (str e))
                      (set! (.-exitCode js/process) 1)))))))))

(apply -main *command-line-args*)
