#!/usr/bin/env nbb
(ns hayari.tick
  "hayari 流行 — one small increment, then stop.

    nbb bin/tick.cljs [--clone PATH] [--budget-ms 700000]
                      [--root SUPERPROJECT] [--pin-lag 4] [--pin false]

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

  ## What a tick publishes

  A commit here changes nothing anyone can query. The workspace query plane
  reads data/hayari-summary.edn out of the WEST CHECKOUT's working tree, and
  that tree follows the pin in manifest/west.yml. So a tick that pushed also
  advances the pin and runs `west update`, batching the pin by --pin-lag
  commits: the data is daily, so moving it four times an hour buys no freshness
  and only writes commits into a repository other sessions are reading.

  ## Why it works in its own clone

  The workspace forbids committing from a shared west checkout: another session
  switching branches there silently reverts uncommitted work. This tick owns a
  clone of its own and lands through it; the west checkout follows the pin."
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [nbb.classpath :as classpath]))

;; The tick lives in bin/, the decision core in src/. Same *file*-relative
;; resolution the other entry points use, for the same reason: this runs from a
;; timer, in a clone, from no particular working directory.
(classpath/add-classpath (path/join (path/dirname (path/dirname *file*)) "src"))

(require '[hayari.core :as core])

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


;; ---------------------------------------------------------------------------
;; The loop reports on itself
;; ---------------------------------------------------------------------------

(defn- summary-path [clone] (path/join clone "data" "hayari-summary.edn"))

(defn- read-summary [clone]
  (let [f (summary-path clone)]
    (if (fs/existsSync f) (edn/read-string (fs/readFileSync f "utf8")) [])))

(defn record-health!
  "Rewrite the health entity inside the committed summary and report it.

  Written into the summary rather than a file of its own so the query plane
  needs no second loader, and so a stale heartbeat is visible to anyone already
  querying hayari instead of only to whoever thinks to look for a health file.

  Returns true when the file changed."
  [clone {:keys [outcome day detail]}]
  (let [ents  (read-summary clone)
        prior (first (filter :hayari.tick/last-run-at ents))
        rest* (remove :hayari.tick/last-run-at ents)
        held  (count (distinct (keep :hayari.summary/observed-on ents)))
        h     (core/tick-health {:at (subs (.toISOString (js/Date.)) 0 19)
                              :outcome outcome :day day :detail detail
                              :days-held held :prior prior})
        body  (str ";; hayari 流行 — committed summary. GENERATED by src/hayari/collect.cljs\n"
                   ";; and bin/tick.cljs. One entity per (country, day), the era coverage, and\n"
                   ";; the loop's own vital signs. Accumulates: a day once observed survives\n"
                   ";; the next machine to run the collector.\n"
                   "[\n" (str/join "\n" (map pr-str (cons h rest*))) "\n]\n")]
    (fs/writeFileSync (summary-path clone) body)
    (println (str "  health: " outcome
                  " · consecutive-failures " (:hayari.tick/consecutive-failures h)
                  (when-let [r (:hayari.tick/recovered-from-failures h)]
                    (str " (recovered from " r ")"))))
    true))

;; ---------------------------------------------------------------------------
;; Publishing: commit -> pin -> checkout -> query plane
;; ---------------------------------------------------------------------------
;;
;; A commit in this repo changes nothing that anyone can query. The workspace
;; query plane (manifest/edn-query.cljs) reads data/hayari-summary.edn out of
;; the WEST CHECKOUT's working tree, and that tree follows the pin in
;; manifest/west.yml. So three more things have to happen after a push, or the
;; loop quietly accumulates days nobody can see.

(def default-superproject
  (path/join (or js/process.env.HOME "/tmp") "github" "com-junkawasaki"))

(defn pinned-sha
  "The hayari revision manifest/west.yml currently pins."
  [root]
  (let [f (path/join root "manifest" "west.yml")]
    (when (fs/existsSync f)
      (let [lines (str/split-lines (fs/readFileSync f "utf8"))]
        (loop [ls lines]
          (cond
            (empty? ls) nil
            (re-find #"^    - name: hayari\s*$" (first ls))
            (some #(second (re-find #"^      revision:\s*([0-9a-f]{40})" %)) (take 4 (rest ls)))
            :else (recur (rest ls))))))))

(defn- commits-behind
  "How many hayari commits the pin is behind origin/main, or nil if unknown."
  [clone pin]
  (when pin
    (let [r (git clone "rev-list" "--count" (str pin "..origin/main"))]
      (when-let [n (:ok r)] (js/parseInt n 10)))))

(defn advance-pin!
  "Advance the west pin and materialise the checkout, batching by `lag`.

  Batching is deliberate. The data is daily, so a pin that moves four times an
  hour buys no freshness — it only writes ~2,000 superproject commits over the
  backfill, in a repository other sessions are working in. Waiting for `lag`
  commits gives the same data with a quarter of the noise, and `flush?` (set
  when the backfill has nothing left to collect) makes sure the tail is not left
  behind at the end."
  [root clone lag flush?]
  (let [head (:ok (git clone "rev-parse" "origin/main"))
        pin  (pinned-sha root)]
    (cond
      (not (fs/existsSync (path/join root "manifest" "west.yml")))
      (println (str "  pin: no superproject at " root " — skipped"))

      (nil? pin)   (println "  pin: no hayari entry in west.yml — skipped")
      (nil? head)  (println "  pin: cannot read origin/main — skipped")
      (= pin head) (println "  pin: already current")

      :else
      (let [behind (or (commits-behind clone pin) 0)]
        (if (and (< behind lag) (not flush?))
          (println (str "  pin: " behind " commit(s) behind, batching until " lag
                        " (data is daily; a pin that moves four times an hour "
                        "buys no freshness and writes commits other sessions read)"))
          (let [r (sh "nbb" [(path/join root "scripts" "west-pin-put.cljs") "hayari" head]
                      {:cwd root})]
            (if (:error r)
              (println (str "  pin: advance failed, next tick retries — "
                            (:status (:error r)) " " (:err (:error r))))
              ;; west-pin-put writes the new pin as a commit on GitHub, not into
              ;; the local tree. Without this pull, `west update` reads the STALE
              ;; local west.yml and checks out the previous pin — measured: it
              ;; reported success while leaving the plane three days behind.
              (let [_  (git root "fetch" "--quiet" "origin")
                    ff (git root "merge" "--ff-only" "--quiet" "origin/main")
                    u  (if (:error ff)
                         {:error {:err (str "superproject not fast-forwardable — "
                                            "someone's work is in the way; "
                                            "the pin moved but the tree did not")}}
                         (sh "west" ["update" "--fetch" "smart" "hayari"] {:cwd root}))]
                (if (:error u)
                  (println (str "  pin: advanced, but `west update` failed — the plane still "
                                "reads the old tree: " (:err (:error u))))
                  ;; west skips a project whose tree is dirty, and says so only in
                  ;; passing. Check the file, not the exit code.
                  (let [f (path/join root "orgs" "cloud-itonami" "hayari"
                                     "data" "hayari-summary.edn")
                        on-disk (when (fs/existsSync f)
                                  (count (distinct (keep :hayari.summary/observed-on
                                                         (edn/read-string
                                                           (fs/readFileSync f "utf8"))))))]
                    (println (str "  pin: advanced to " (subs head 0 8)
                                  " and checkout updated — "
                                  (or on-disk "?") " day(s) now visible to the query plane"))))))))))))

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
        budget (or (:budget-ms opts) "700000")
        root   (or (:root opts) default-superproject)
        lag    (js/parseInt (or (:pin-lag opts) "4") 10)
        pin?   (not= "false" (:pin opts))]
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
            (do (println (str "  backfill complete to the " horizon
                              " horizon — nothing to add, so nothing is committed"))
                (record-health! clone {:outcome :nothing-to-do})
                ;; Nothing left to collect, so publish whatever is still
                ;; unpinned rather than leaving the tail stranded.
                (when pin? (advance-pin! root clone lag true)))
            (do
              (println (str "  collecting " day))
              (let [r (sh "nbb" [(path/join clone "src" "hayari" "collect.cljs")
                                 "--date" day "--top" "25" "--budget-ms" budget
                                 "--root" clone]
                          {:cwd clone :stdio "inherit"})]
                (if (:error r)
                  ;; A failure is news, so it is committed. Before this, a broken
                  ;; tick left the summary simply not growing — indistinguishable
                  ;; from a quiet day.
                  (do (println (str "  collect failed: status " (:status (:error r))))
                      (record-health! clone {:outcome :collect-failed :day day
                                             :detail (str "status " (:status (:error r)))})
                      (git clone "add" "data/hayari-summary.edn")
                      (git clone "-c" "user.name=Jun Kawasaki" "-c" "user.email=jun@gftd.group"
                           "commit" "-q" "-m" (str "health: collect failed on " day))
                      (git clone "pull" "--rebase=false" "--ff-only" "--quiet" "origin" "main")
                      (git clone "push" "--quiet" "origin" "main")
                      (set! (.-exitCode js/process) 1))
                  ;; Name the day's most-viewed works before deciding whether
                  ;; anything changed, so the entity index lands in the SAME
                  ;; commit as the day that introduced those works. A follow-up
                  ;; commit would leave the summary briefly pointing at QIDs the
                  ;; plane cannot resolve.
                  (let [_ (sh "nbb" [(path/join clone "src" "hayari" "corpus.cljs")
                                     "--only" "top-entities"]
                              {:cwd clone :stdio "inherit"})
                        _ (record-health! clone {:outcome :added-day :day day})
                        changed (:ok (git clone "status" "--porcelain"
                                          "--" "data/hayari-summary.edn"
                                          "data/hayari-top-entities.edn"))]
                    (if (str/blank? changed)
                      (println "  summary unchanged — no commit (an empty commit would
                                claim work that did not happen)")
                      (do
                        (git clone "add" "data/hayari-summary.edn"
                             "data/hayari-top-entities.edn")
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
                                (do (println (str "  push failed (will retry next tick): "
                                                  (:status (:error p))))
                                    (record-health! clone {:outcome :push-failed :day day
                                                           :detail (str "status " (:status (:error p)))}))
                                (do
                                  (println (str "  committed and pushed " day
                                                " — " (inc (count held)) " day(s) held"))
                                  (when pin? (advance-pin! root clone lag false)))))))))))))))))))

(apply -main *command-line-args*)
