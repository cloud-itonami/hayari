#!/usr/bin/env nbb
(ns hayari.tick
  "hayari 流行 — one small increment, then stop.

    nbb bin/tick.cljs [--clone PATH] [--budget-ms 700000] [--pin-every-ms 3600000]

  Designed to be run on a short timer (15 minutes). Each tick collects exactly
  ONE day that is not held yet, regenerates the committed summary, and commits
  only if that summary actually changed. No day to add means no commit — an
  empty commit would make the log say work happened when none did.

  ## Why backfill, and why it terminates

  Wikimedia's per-country dataset begins 2021-01-01 (measured 2026-08-10:
  2020-12-31 answers 404, 2021-01-01 answers 200). Every day from there to two
  days ago is re-fetchable at any time, so the loop walks BACKWARDS from the
  newest missing day and stops when it reaches the horizon. It is a finite job
  with a known end, not a daemon that runs forever.

  That horizon is also the answer to \"where does the raw history live\": it does
  not need to live anywhere. The observations are a cache of a public API, not
  an irreplaceable record. What is durable is data/hayari-summary.edn, which is
  tracked in git — and pre-2021 is not obtainable at all, from us or anyone.

  ## Why it works in its own clone

  The workspace forbids committing from a shared west checkout: another session
  switching branches there silently reverts uncommitted work. This tick owns a
  clone of its own and lands through it; the west checkout follows the pin."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def repo-url "git@github.com:cloud-itonami/hayari.git")
(def horizon   "2021-01-01")

(defn- sh
  "Run a command, returning {:ok out} / {:error {...}}. Never throws: a tick
  that dies on a transient git failure is a tick that stops the whole loop."
  [cmd args opts]
  (let [r (cp/spawnSync cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :timeout 900000} opts)))]
    (if (zero? (or (.-status r) 0))
      {:ok (str/trim (or (.-stdout r) ""))}
      {:error {:cmd (str cmd " " (str/join " " args))
               :status (.-status r)
               :out (str/trim (or (.-stdout r) ""))
               :err (str/trim (or (.-stderr r) ""))}})))

(defn- git [dir & args] (sh "git" (vec args) {:cwd dir}))

(defn- shift-date [date-str n]
  (let [[y m d] (map #(js/parseInt % 10) (str/split date-str #"-"))
        t (js/Date. (js/Date.UTC y (dec m) d))]
    (subs (.toISOString (js/Date. (+ (.getTime t) (* n 86400000)))) 0 10)))

(defn- newest-available []
  ;; Wikimedia publishes per-country aggregates with a lag; two days back is the
  ;; most recent date that was reliably present when probed.
  (subs (.toISOString (js/Date. (- (.getTime (js/Date.)) (* 2 86400000)))) 0 10))

(defn days-held
  "Days already in the committed summary."
  [clone]
  (let [f (path/join clone "data" "hayari-summary.edn")]
    (if-not (fs/existsSync f)
      #{}
      (->> (edn/read-string (fs/readFileSync f "utf8"))
           (keep :hayari.summary/observed-on)
           set))))

(defn next-day
  "The newest day at or before `newest` that is not held, or nil when the
  backfill has reached the horizon. Walking backwards means the freshest data
  arrives first and the long tail fills in behind it."
  [held newest]
  (loop [d newest]
    (cond
      (< (compare d horizon) 0) nil
      (contains? held d)        (recur (shift-date d -1))
      :else                     d)))

(defn- ensure-clone [clone]
  (if (fs/existsSync (path/join clone ".git"))
    (let [_ (git clone "fetch" "--quiet" "origin")
          r (git clone "reset" "--hard" "--quiet" "origin/main")]
      (if (:error r) r {:ok "synced"}))
    (do (fs/mkdirSync (path/dirname clone) #js {:recursive true})
        (sh "git" ["clone" "--quiet" repo-url clone] {}))))

(defn -main [& argv]
  (let [opts (loop [a (vec argv) m {}]
               (if (< (count a) 2) m
                   (recur (vec (drop 2 a))
                          (assoc m (keyword (str/replace (first a) #"^--" "")) (second a)))))
        clone  (or (:clone opts)
                   (path/join (or js/process.env.HOME "/tmp") ".gftd" "hayari-tick"))
        budget (or (:budget-ms opts) "700000")]
    (println (str "hayari tick " (subs (.toISOString (js/Date.)) 0 19) "Z"))
    (let [sync (ensure-clone clone)]
      (if (:error sync)
        (do (println (str "  clone/sync failed: " (pr-str (:error sync))))
            (set! (.-exitCode js/process) 1))
        (let [held (days-held clone)
              day  (next-day held (newest-available))]
          (println (str "  held " (count held) " day(s)"
                        (when (seq held) (str ", oldest " (apply min held)))))
          (if (nil? day)
            (println (str "  backfill complete to the " horizon
                          " horizon — nothing to add, so nothing is committed"))
            (do
              (println (str "  collecting " day))
              (let [r (sh "nbb" [(path/join clone "src" "hayari" "collect.cljs")
                                 "--date" day "--top" "25" "--budget-ms" budget
                                 "--root" clone]
                          {:cwd clone :stdio "inherit"})]
                (if (:error r)
                  (do (println (str "  collect failed: status " (:status (:error r))))
                      (set! (.-exitCode js/process) 1))
                  (let [changed (:ok (git clone "status" "--porcelain"
                                          "--" "data/hayari-summary.edn"))]
                    (if (str/blank? changed)
                      (println "  summary unchanged — no commit (an empty commit would
                                claim work that did not happen)")
                      (do
                        (git clone "add" "data/hayari-summary.edn")
                        (let [c (git clone "-c" "user.name=Jun Kawasaki"
                                     "-c" "user.email=jun@gftd.group"
                                     "commit" "-q" "-m"
                                     (str "data: observe " day
                                          "\n\nOne day added by bin/tick.cljs. "
                                          "Backfill walks backwards from the newest "
                                          "available day toward the 2021-01-01 horizon "
                                          "where Wikimedia's per-country dataset begins."))]
                          (if (:error c)
                            (println (str "  commit failed: " (pr-str (:error c))))
                            (let [_ (git clone "pull" "--rebase=false" "--ff-only" "--quiet" "origin" "main")
                                  p (git clone "push" "--quiet" "origin" "main")]
                              (if (:error p)
                                (println (str "  push failed (will retry next tick): "
                                              (:status (:error p))))
                                (println (str "  committed and pushed " day
                                              " — " (inc (count held)) " day(s) held"))))))))))))))))))

(apply -main *command-line-args*)
