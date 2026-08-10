#!/usr/bin/env nbb
(ns hayari.simulate
  "hayari 流行 — fit and simulate attention decay from the accumulated
  observations, using the OASIS XMILE engine in
  `kotoba-lang/org-oasis-open-xmile`.

    nbb src/hayari/simulate.cljs [--min-days 3] [--country JP] [--top 10]
                                 [--by work|domain] [--data PATH] [--out PATH]
                                 [--xmile-src PATH]

  Needs at least three days in `data/hayari.datoms.edn`; collect them with
  `collect.cljs --days N`. A single day cannot show change, and change is the
  entire subject of a stock-and-flow model.

  Like `hayari.collect`, this runs with no --classpath and from any directory.

  The classpath is set and the namespaces required at TOP LEVEL, before -main
  exists. nbb resolves an alias when it analyses the form that uses it, so a
  `require` inside a function body leaves the alias unresolved in that very
  function — which is how this file first failed."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [nbb.classpath :as cp]))

(def ^:private src-dir   (path/dirname (path/dirname *file*)))
(def ^:private repo-root (path/dirname src-dir))

(defn- parse-args [argv]
  (loop [a (vec argv) m {}]
    (if (< (count a) 2)
      m
      (recur (vec (drop 2 a))
             (assoc m (keyword (str/replace (first a) #"^--" "")) (second a))))))

(def ^:private opts (parse-args *command-line-args*))

(def ^:private xmile-src
  "Where the XMILE library's sources are.

  The west manifest lays every project out at orgs/<org>/<repo>, so from
  orgs/cloud-itonami/hayari the library sits at a fixed relative offset. That
  is an assumption about the workspace, not about the filesystem, so it is
  checked and reported rather than allowed to surface as 'Could not find
  namespace' — the failure this repo already hit once."
  (or (:xmile-src opts)
      (path/join repo-root ".." ".." "kotoba-lang" "org-oasis-open-xmile" "src")))

(when-not (fs/existsSync xmile-src)
  (println (str "hayari simulate: XMILE library not found at " xmile-src "\n"
                "  west update --fetch smart org-oasis-open-xmile\n"
                "  (or pass --xmile-src <path>)"))
  (set! (.-exitCode js/process) 1))

(cp/add-classpath src-dir)
(when (fs/existsSync xmile-src) (cp/add-classpath xmile-src))

(require '[hayari.core :as hc])
(require '[hayari.xmile :as hx])

(defn -main [& _]
  (let [data     (or (:data opts) (path/join repo-root "data" "hayari.datoms.edn"))
        out      (or (:out opts) (path/join repo-root "data" "hayari-xmile.edn"))
        min-days (js/parseInt (or (:min-days opts) "3") 10)
        top-n    (js/parseInt (or (:top opts) "10") 10)]
    (if-not (fs/existsSync data)
      (do (println (str "hayari simulate: no observations at " data
                        " — run collect with --days " min-days " first"))
          (set! (.-exitCode js/process) 1))
      (let [datoms (edn/read-string (fs/readFileSync data "utf8"))
            by     (or (:by opts) "work")
            ;; --by domain answers a different question from --by work: not
            ;; "how fast did this film fade" but "does a country's attention to
            ;; culture drain at a different rate than its attention to events".
            ;; Same model, different stock.
            series (case by
                     "domain" (hc/domain-series datoms)
                     (hc/work-series datoms (if-let [c (:country opts)] {:country c} {})))
            days   (count (distinct (keep :hayari/observed-on datoms)))
            fits   (->> (vals series)
                        (map hx/fit-work)
                        (keep :ok)
                        (sort-by (comp - :r2)))
            usable (filter #(<= min-days (:n %)) fits)]
        (println (str "hayari xmile [" by "]: " days " day(s) held · "
                      (count series) " series · "
                      (count usable) " fitted (>= " min-days " days)"))
        ;; The floor that actually decides is estimate-decay's three points, not
        ;; --min-days. Reporting only against --min-days let `--min-days 1` print
        ;; "0 fitted" with no explanation — a silent zero, which is the exact
        ;; shape of failure this repo exists to refuse.
        (when (< days 3)
          (println (str "  NOTE: only " days " day(s) collected. estimate-decay refuses "
                        "fewer than 3 points regardless of --min-days, so nothing is "
                        "fitted here — absent rather than guessed. Collect more with "
                        "`collect.cljs --days N`.")))
        (doseq [f (take top-n usable)]
          (println (str "  λ=" (.toFixed (:lambda f) 4)
                        "  half-life=" (if-let [h (:half-life f)] (str (.toFixed h 2) "d") "growing")
                        "  r²=" (.toFixed (:r2 f) 3)
                        "  MAPE=" (if-let [e (:mape f)] (str (.toFixed (* 100 e) 1) "%") "n/a")
                        "  n=" (:n f)
                        "  " (:label f)
                        (if (:kind f) (str " [" (:kind f) "]") ""))))
        (fs/writeFileSync
          out
          (str ";; hayari 流行 — XMILE attention-decay fits. GENERATED by src/hayari/simulate.cljs.\n"
               ";; lambda is a CONTINUOUS per-day rate: Decay = Attention * lambda.\n"
               ";; MAPE is IN-SAMPLE — it scores the days the curve was fitted on and is NOT a\n"
               ";; forecast claim. Simulated by kotoba-lang/org-oasis-open-xmile (RK4, dt 0.25).\n"
               (pr-str {:hayari.xmile/days-held days
                        :hayari.xmile/grouped-by (keyword by)
                        :hayari.xmile/series    (count series)
                        :hayari.xmile/fitted    (count usable)
                        :hayari.xmile/min-days  min-days
                        :hayari.xmile/fits      (mapv #(dissoc % :model) usable)})
               "\n"))
        (println (str "  wrote " out))))))

(-main)
