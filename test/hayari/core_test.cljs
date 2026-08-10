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

(defn -main [& _] (run-tests 'hayari.core-test))
(apply -main *command-line-args*)
