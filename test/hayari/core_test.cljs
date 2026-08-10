(ns hayari.core-test
  "Tests for the decision core. Every fixture below is a value that the live
  APIs actually returned on 2026-08-10 — the meta titles, the Arrietty claim
  shape with three P577 re-release dates, the uneven country coverage. Invented
  fixtures would have let the meta-article filter and the multi-date era rule
  pass while being wrong against the real feed."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [hayari.core :as core]))

(deftest meta-article-rejection
  (testing "navigation rows observed at rank 1 and 6 in the real JP response"
    (is (core/meta-article? "メインページ"))
    (is (core/meta-article? "特別:検索"))
    (is (core/meta-article? "Main_Page"))
    (is (core/meta-article? "Special:Search")))
  (testing "a real subject with a colon in its title survives"
    (is (not (core/meta-article? "機動戦士ガンダム: 閃光のハサウェイ")))
    (is (not (core/meta-article? "借りぐらしのアリエッティ")))))

(deftest shares-exclude-navigation
  (let [rows [{:article "メインページ" :views 521300}
              {:article "借りぐらしのアリエッティ" :views 60}
              {:article "佐藤佳奈" :views 40}]
        out  (:ok (core/attention-shares rows))]
    (testing "the main page is not in the denominator"
      (is (= 2 (count out)))
      (is (< (abs (- 0.6 (:share (first out)))) 1e-9))
      (is (< (abs (- 1.0 (reduce + (map :share out)))) 1e-9)))))

(deftest era-uses-first-publication
  (testing "Arrietty carries three P577 values; the cohort is the first"
    (let [times ["+2010-07-17T00:00:00Z" "+2011-06-02T00:00:00Z" "+2012-02-17T00:00:00Z"]]
      (is (= 2010 (:ok (core/publication-year times))))
      (is (= 2010 (:ok (core/work-era times))))))
  (testing "no date is a returned error, not a thrown one and not a guess"
    (let [r (core/work-era [])]
      (is (nil? (:ok r)))
      (is (= :no-publication-date (get-in r [:error :reason]))))))

(deftest kind-prefers-the-specific
  (let [kinds {"Q11424"    {:kind :film/feature :rank 25}
               "Q20650540" {:kind :anime/film   :rank 10}}]
    (testing "a work listed as both film and anime film is an anime film"
      (is (= :anime/film (:ok (core/classify-kind ["Q11424" "Q20650540"] kinds))))
      (is (= :anime/film (:ok (core/classify-kind ["Q20650540" "Q11424"] kinds)))
          "and the answer does not depend on P31 order"))
    (testing "an unmapped P31 is reported unclassified, never guessed"
      (let [r (core/classify-kind ["Q999999"] kinds)]
        (is (nil? (:ok r)))
        (is (= :unclassified (get-in r [:error :reason])))))))

(deftest lift-carries-its-denominator
  (let [rows [{:project "ja.wikipedia" :article "A" :country "JP" :share 0.9}
              {:project "ja.wikipedia" :article "A" :country "US" :share 0.1}
              {:project "en.wikipedia" :article "B" :country "US" :share 0.5}]
        out  (core/cross-country-lift rows)
        jp-a (first (filter #(and (= "A" (:article %)) (= "JP" (:country %))) out))
        us-b (first (filter #(= "B" (:article %)) out))]
    (testing "concentration in one country shows as lift > 1"
      (is (< 1.7 (:lift jp-a) 1.9))
      (is (= 2 (:observed-in jp-a))))
    (testing "a work seen in a single country reports observed-in 1, so a reader
              cannot mistake its lift of 1.0 for a cross-country finding"
      (is (= 1 (:observed-in us-b)))
      (is (< (abs (- 1.0 (:lift us-b))) 1e-9)))))

(deftest one-work-across-languages-is-one-work
  (testing "the real 2026-08-07 pair: en `The_Odyssey_(2026_film)` and es
            `La_Odisea_(película_de_2026)` share Q-identity and must not be
            counted as two works reaching 13 and 6 countries"
    (let [rows [{:project "en.wikipedia" :article "The_Odyssey_(2026_film)"
                 :qid "Q123" :country "US" :share 0.3}
                {:project "en.wikipedia" :article "The_Odyssey_(2026_film)"
                 :qid "Q123" :country "GB" :share 0.3}
                {:project "es.wikipedia" :article "La_Odisea_(película_de_2026)"
                 :qid "Q123" :country "ES" :share 0.3}]
          out (core/cross-country-lift rows)]
      (is (every? #(= 3 (:observed-in %)) out))))
  (testing "two language editions of one work inside ONE country count that
            country once, not twice"
    (let [rows [{:project "en.wikipedia" :article "X" :qid "Q9" :country "CA" :share 0.2}
                {:project "fr.wikipedia" :article "X-fr" :qid "Q9" :country "CA" :share 0.2}
                {:project "en.wikipedia" :article "X" :qid "Q9" :country "US" :share 0.4}]
          out (core/cross-country-lift rows)]
      (is (every? #(= 2 (:observed-in %)) out))))
  (testing "an unresolved row falls back to its own language and never merges
            with a differently-titled row"
    (is (not= (core/work-key {:project "en.wikipedia" :article "A"})
              (core/work-key {:project "es.wikipedia" :article "B"})))
    (is (= (core/work-key {:qid "Q1" :project "en.wikipedia" :article "A"})
           (core/work-key {:qid "Q1" :project "es.wikipedia" :article "B"})))))

(deftest coverage-declares-the-unmeasurable
  (let [cov (core/coverage-report {:countries-requested 249
                                   :countries-responded 100 :countries-with-rows 80
                                   :countries-no-data ["EG"]
                                   :titles-seen 10 :qid-resolved 8 :qid-unresolved 2
                                   :kind-classified 6 :kind-unclassified 4
                                   :era-dated 5 :era-undated 5})]
    (is (= 249 (:countries/requested cov)))
    (testing "responding and yielding an observation are separate counts —
              measured 2026-08-08, 101 countries answered 200 and only 66
              produced a single subject row"
      (is (= 100 (:countries/responded cov)))
      (is (= 80 (:countries/with-rows cov))))
    (testing "audience generation is never a number"
      (is (= :uncomputable-until-measured (:audience-generation cov)))
      (is (string? (:audience-generation/reason cov))))
    (testing "a clean run is not flagged degraded"
      (is (false? (:enrichment/degraded? cov)))
      (is (= 0 (:qid/batch-failed cov))))))

(deftest budget-cutoff-is-not-absence-of-data
  (testing "countries cut off by our own wall-clock budget are counted apart
            from countries the API had no data for — conflating them would turn
            our scheduling into a fact about the world"
    (let [cov (core/coverage-report {:countries-requested 249 :countries-responded 60 :countries-with-rows 45
                                     :countries-no-data ["EG"]
                                     :countries-skipped ["ZW" "ZM"]
                                     :titles-seen 500 :qid-resolved 100 :qid-unresolved 400
                                     :kind-classified 90 :kind-unclassified 410
                                     :era-dated 40 :era-undated 460
                                     :qid-skipped 300 :claim-skipped 0})]
      (is (= ["EG"] (:countries/no-data cov)))
      (is (= ["ZW" "ZM"] (:countries/skipped-budget cov)))
      (is (= 300 (:qid/skipped-budget cov)))
      (is (true? (:enrichment/degraded? cov))
          "a budget cutoff degrades the run even when nothing failed"))))

(deftest degraded-enrichment-is-visible
  (testing "titles lost to a throttled batch are counted apart from titles that
            genuinely have no Wikidata item — the 1/874 run on 2026-08-10 looked
            healthy on every other number"
    (let [cov (core/coverage-report {:countries-requested 249 :countries-responded 99 :countries-with-rows 60
                                     :countries-no-data [] :titles-seen 874
                                     :qid-resolved 1 :qid-unresolved 873
                                     :kind-classified 0 :kind-unclassified 874
                                     :era-dated 0 :era-undated 874
                                     :qid-batch-failed 850 :claim-batch-failed 0})]
      (is (true? (:enrichment/degraded? cov)))
      (is (= 850 (:qid/batch-failed cov))))))

(deftest datoms-lead-with-coverage
  (let [ds (core/->datoms {:observed-on "2026-08-08"
                           :rows [{:country "JP" :article "A" :project "ja.wikipedia"
                                   :rank 2 :views 100 :share 0.5 :kind :anime/film}]
                           :coverage {:countries/requested 249}
                           :source-urls ["https://example.invalid"]})]
    (is (= -1 (:db/id (first ds))) "entity -1 is the coverage report")
    (is (every? #(= "hayari" (:source/dataset %)) ds))
    (is (= :anime/film (:hayari/kind (second ds))))
    (testing "year and decade are both emitted, so a consumer that wants
              1-year granularity is not forced to re-derive it"
      (let [d (core/->datoms {:observed-on "2026-08-08"
                              :rows [{:country "JP" :article "A" :project "ja.wikipedia"
                                      :rank 1 :views 10 :share 1.0
                                      :work-year 2010 :work-era 2010}]
                              :coverage {} :source-urls []})]
        (is (= 2010 (:hayari/work-year (second d))))
        (is (= 2010 (:hayari/work-era (second d))))))
    (testing "absent enrichment yields an absent attribute, not a nil one"
      (is (not (contains? (second ds) :hayari/wikidata-qid))))))


(deftest namespace-and-p31-rejections
  (testing "P31 marks Wikimedia machinery that the title filter cannot see —
            `List of ...` and `X (disambiguation)` look like ordinary titles"
    (is (core/wikimedia-meta? ["Q13406463"]))
    (is (core/wikimedia-meta? ["Q11424" "Q4167410"]))
    (is (not (core/wikimedia-meta? ["Q11424"])))))

(deftest dates-fall-back-in-priority-order
  (testing "P577 wins when present"
    (is (= {:year 2010 :era 2010 :via :p577}
           (:ok (core/dated-by {:p577 ["+2010-01-01T00:00:00Z"]
                                :p571 ["+1963-01-01T00:00:00Z"]})))))
  (testing "a work with no publication date is still dated by inception, and
            records which property answered"
    (is (= {:year 1963 :era 1960 :via :p571}
           (:ok (core/dated-by {:p577 [] :p571 ["+1963-11-23T00:00:00Z"]})))))
  (testing "properties are tried in order, never pooled — pooling would date a
            2024 series by its 1963 franchise"
    (is (= 2024 (:year (:ok (core/dated-by {:p577 ["+2024-01-01T00:00:00Z"]
                                            :p571 ["+1963-01-01T00:00:00Z"]}))))))
  (testing "no date at all is an error, not a zero"
    (is (= :no-publication-date
           (get-in (core/dated-by {:p577 [] :p571 [] :p1191 [] :p580 []}) [:error :reason])))))

(deftest decay-fit-recovers-a-known-rate
  (testing "a clean exponential is recovered to the rate it was built with"
    (let [lam 0.35
          pts (mapv (fn [i] {:day (str "2026-08-0" (inc i))
                             :views (* 1000 (js/Math.exp (- (* lam i))))})
                    (range 6))
          r   (:ok (core/estimate-decay pts))]
      (is (< (abs (- lam (:lambda r))) 1e-6))
      (is (< (abs (- 1000 (:v0 r))) 1e-6))
      (is (< 0.999 (:r2 r)))
      (is (< (abs (- (/ (js/Math.log 2) lam) (:half-life r))) 1e-6))))
  (testing "attention that grew reports no half-life rather than a negative one"
    (let [pts (mapv (fn [i] {:day (str "2026-08-0" (inc i)) :views (* 100 (inc i))}) (range 4))
          r   (:ok (core/estimate-decay pts))]
      (is (neg? (:lambda r)))
      (is (nil? (:half-life r)))))
  (testing "two points are refused: a line through two points always fits, and
            r² = 1 would look like confidence it does not have"
    (is (= :too-few-points
           (get-in (core/estimate-decay [{:day "2026-08-01" :views 10}
                                         {:day "2026-08-02" :views 5}])
                   [:error :reason]))))
  (testing "a gap in collection stretches the x axis instead of compressing it"
    (let [tight (:ok (core/estimate-decay [{:day "2026-08-01" :views 1000}
                                           {:day "2026-08-02" :views 500}
                                           {:day "2026-08-03" :views 250}]))
          gapped (:ok (core/estimate-decay [{:day "2026-08-01" :views 1000}
                                            {:day "2026-08-03" :views 500}
                                            {:day "2026-08-05" :views 250}]))]
      (is (< (abs (- (:lambda tight) (* 2 (:lambda gapped)))) 1e-9)
          "same ratios over twice the elapsed time is half the rate"))))

(deftest series-groups-one-work-across-languages-and-days
  (let [ds [{:hayari/observed-on "2026-08-07" :hayari/country-iso2 "US"
             :hayari/project "en.wikipedia" :hayari/article "Odyssey"
             :hayari/wikidata-qid "Q1" :hayari/views 100 :hayari/kind :film/feature}
            {:hayari/observed-on "2026-08-07" :hayari/country-iso2 "ES"
             :hayari/project "es.wikipedia" :hayari/article "La_Odisea"
             :hayari/wikidata-qid "Q1" :hayari/views 50}
            {:hayari/observed-on "2026-08-08" :hayari/country-iso2 "US"
             :hayari/project "en.wikipedia" :hayari/article "Odyssey"
             :hayari/wikidata-qid "Q1" :hayari/views 90}]
        s  (core/work-series ds)]
    (testing "one series, days summed across countries and languages"
      (is (= 1 (count s)))
      (is (= [{:day "2026-08-07" :views 150} {:day "2026-08-08" :views 90}]
             (:points (first (vals s))))))
    (testing "restricting to one country changes the quantity, not the identity"
      (is (= [{:day "2026-08-07" :views 100} {:day "2026-08-08" :views 90}]
             (:points (first (vals (core/work-series ds {:country "US"})))))))))

(deftest history-survives-the-next-run
  (let [day1 [{:db/id -1 :hayari.coverage/observed-on "2026-08-07"}
              {:db/id -2 :hayari/observed-on "2026-08-07" :hayari/country-iso2 "JP"
               :hayari/project "ja.wikipedia" :hayari/article "A" :hayari/views 10}]
        day2 [{:db/id -1 :hayari.coverage/observed-on "2026-08-08"}
              {:db/id -2 :hayari/observed-on "2026-08-08" :hayari/country-iso2 "JP"
               :hayari/project "ja.wikipedia" :hayari/article "A" :hayari/views 20}]
        {:keys [coverage rows]} (core/merge-observations day1 day2)]
    (testing "a second run extends the history instead of erasing it"
      (is (= 2 (count coverage)))
      (is (= 2 (count rows)))
      (is (= ["2026-08-07" "2026-08-08"] (mapv :hayari/observed-on rows))))
    (testing "re-observing the same day replaces that day, never duplicates it"
      (let [again (core/merge-observations (concat coverage rows)
                                           [{:db/id -1 :hayari/observed-on "2026-08-08"
                                             :hayari/country-iso2 "JP" :hayari/project "ja.wikipedia"
                                             :hayari/article "A" :hayari/views 22}])]
        (is (= 2 (count (:rows again))))
        (is (= 22 (:hayari/views (second (:rows again)))))))
    (testing "renumbering yields unique ids"
      (let [ids (map :db/id (core/renumber (concat coverage rows)))]
        (is (= (count ids) (count (distinct ids))))))))

(deftest mape-skips-zero-denominators
  (is (= 0.0 (:ok (core/mape [10 20] [10 20]))))
  (is (< (abs (- 0.5 (:ok (core/mape [10] [15])))) 1e-9))
  (is (= :nothing-to-compare (get-in (core/mape [0 0] [1 2]) [:error :reason]))))

(deftest domain-rollup-covers-bare-and-namespaced-kinds
  (let [domains {"anime" :culture "person" :person "event" :event}]
    (testing "namespaced kinds roll up by namespace"
      (is (= :culture (:ok (core/domain-of :anime/film domains)))))
    (testing "bare kinds roll up by their own name — using `namespace` alone
              silently dropped :person, the largest measured class, out of the
              axis while everything around it looked fine"
      (is (= "person" (core/domain-key :person)))
      (is (= "anime" (core/domain-key :anime/film)))
      (is (= :person (:ok (core/domain-of :person domains)))))
    (testing "an unmapped kind is reported, never bucketed"
      (is (= :domain-unmapped (get-in (core/domain-of :chem/compound domains) [:error :reason]))))
    (testing "no kind is its own case, distinct from an unmapped one"
      (is (= :no-kind (get-in (core/domain-of nil domains) [:error :reason]))))))

(deftest labels-are-capped-and-order-preserving
  (let [labels {"Q1" "actor" "Q2" "singer" "Q3" "model" "Q4" "seiyū" "Q5" "tarento"}]
    (testing "at most four values survive; Wikidata routinely lists a dozen"
      (is (= 4 (count (:qids (core/labelled ["Q1" "Q2" "Q3" "Q4" "Q5"] labels))))))
    (testing "source order is kept — Wikidata's first value is the primary one"
      (is (= ["actor" "singer"] (:labels (core/labelled ["Q1" "Q2"] labels)))))
    (testing "a QID whose label was not fetched drops out of :labels but the
              QID list still reflects what was capped"
      (let [r (core/labelled ["Q1" "Q9"] labels)]
        (is (= ["Q1" "Q9"] (:qids r)))
        (is (= ["actor"] (:labels r)))))))

(deftest domain-series-keeps-the-unclassified-visible
  (let [ds [{:hayari/observed-on "2026-08-07" :hayari/domain :culture :hayari/views 100}
            {:hayari/observed-on "2026-08-07" :hayari/domain :culture :hayari/views 50}
            {:hayari/observed-on "2026-08-07" :hayari/views 10}
            {:hayari/observed-on "2026-08-08" :hayari/domain :culture :hayari/views 90}]
        s  (core/domain-series ds)]
    (testing "views are summed per domain per day"
      (is (= [{:day "2026-08-07" :views 150} {:day "2026-08-08" :views 90}]
             (:points (:culture s)))))
    (testing "rows with no domain become :unmapped rather than vanishing —
              an aggregate that silently omits what the table missed would
              understate the total it appears to describe"
      (is (contains? s :unmapped))
      (is (= [{:day "2026-08-07" :views 10}] (:points (:unmapped s)))))))

(deftest coverage-counts-the-new-axes
  (let [cov (core/coverage-report {:countries-requested 6 :countries-responded 6
                                   :countries-with-rows 6 :countries-no-data []
                                   :titles-seen 90 :qid-resolved 72 :qid-unresolved 18
                                   :kind-classified 64 :kind-unclassified 26
                                   :era-dated 24 :era-undated 66
                                   :domain-mapped 64 :domain-unmapped 0
                                   :genre-labelled 21 :occupation-labelled 35})]
    (is (= 64 (:domain/mapped cov)))
    (is (= 0 (:domain/unmapped cov)))
    (is (= 21 (:genre/labelled cov)))
    (is (= 35 (:occupation/labelled cov)))
    (testing "sparse genre/occupation coverage does not mark a run degraded —
              most articles are neither a work with a genre nor a person"
      (is (false? (:enrichment/degraded? cov))))))

(deftest wikimedia-internal-items-are-rejected
  (testing "found in the unclassified tail after the first pass: machinery that
            had been surviving as though it were a subject"
    (is (core/wikimedia-meta? ["Q17442446"]))
    (is (core/wikimedia-meta? ["Q104635718"]))
    (is (core/wikimedia-meta? ["Q15633587"]))))

(defn -main [& _] (run-tests 'hayari.core-test))
(apply -main *command-line-args*)
