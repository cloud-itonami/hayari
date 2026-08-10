(ns hayari.xmile-test
  "Integration test for the XMILE layer. Needs the library on the classpath:

    nbb --classpath src:test:../../kotoba-lang/org-oasis-open-xmile/src \\
        test/hayari/xmile_test.cljs

  Kept separate from core_test so the decision core stays testable with no
  sibling checkout — the estimation is arithmetic and should not need an
  engine to verify."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [hayari.core :as core]
            [hayari.xmile :as hx]
            [xmile.model :as m]))

(defn- decay-points
  "Daily views following exp(-lambda t) exactly."
  [lambda v0 n]
  (mapv (fn [i] {:day (str "2026-08-" (if (< (inc i) 10) (str "0" (inc i)) (inc i)))
                 :views (* v0 (js/Math.exp (- (* lambda i))))})
        (range n)))

(deftest model-is-a-valid-xmile-document
  (let [mod (hx/->decay-model {:name "probe" :v0 1000 :lambda 0.3 :days 5})]
    (testing "built through the library's constructors, not a hand-rolled map"
      (is (= #{"Attention" "Decay" "decay_rate"} (m/variable-names mod)))
      (is (= 1 (count (m/stocks mod))))
      (is (= 1 (count (m/flows mod)))))
    (testing "the stock is drained by the flow and cannot go negative"
      (let [s (m/lookup mod "Attention")]
        (is (= #{"Decay"} (:xmile/outflows s)))
        (is (true? (:xmile/non-negative? s)))))))

(deftest simulation-reproduces-the-fitted-curve
  (testing "RK4 at dt 0.25 tracks the continuous rate the fit reports, so the
            residual is the data's and not the integrator's"
    (let [lam 0.3
          mod (hx/->decay-model {:name "probe" :v0 1000 :lambda lam :days 4})
          {:keys [times attention]} (hx/simulate mod)
          at   (fn [d] (nth attention (.indexOf (clj->js (map double times)) (double d))))]
      (doseq [d [1 2 3 4]]
        (let [exact (* 1000 (js/Math.exp (- (* lam d))))]
          (is (< (/ (abs (- exact (at d))) exact) 0.001)
              (str "day " d " within 0.1% of the closed form")))))))

(deftest fit-round-trips-a-known-rate
  (testing "generate a curve at a known rate, fit it, simulate it, and the
            reported numbers agree with what it was built from"
    (let [r (:ok (hx/fit-work {:label "known" :points (decay-points 0.25 5000 6)}))]
      (is (< (abs (- 0.25 (:lambda r))) 1e-6))
      (is (< 0.999 (:r2 r)))
      (is (< (:mape r) 0.01))
      (is (= :in-sample (:mape-scope r)))
      (is (= 6 (:n r))))))

(deftest too-short-a-series-is-refused-not-fitted
  (testing "the XMILE layer passes the estimator's refusal through unchanged
            rather than producing a model nobody could justify"
    (let [r (hx/fit-work {:label "short" :points [{:day "2026-08-01" :views 10}
                                                  {:day "2026-08-02" :views 5}]})]
      (is (nil? (:ok r)))
      (is (= :too-few-points (get-in r [:error :reason]))))))

(deftest growth-is-modelled-as-negative-decay-not-clamped
  (testing "a work still gaining attention keeps its sign; forcing it positive
            would report decay for something that was rising"
    (let [r (:ok (hx/fit-work {:label "rising"
                               :points (decay-points -0.2 100 5)}))]
      (is (neg? (:lambda r)))
      (is (nil? (:half-life r))))))

(defn -main [& _] (run-tests 'hayari.xmile-test))
(apply -main *command-line-args*)
