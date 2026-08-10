(ns hayari.xmile
  "hayari 流行 — the observations as an OASIS XMILE 1.0 stock-and-flow model.

  Attention is a stock. It arrives, it drains, and the rate it drains at is the
  thing worth knowing about a work: `Spider-Man` and a national holiday do not
  decay alike. That is a system-dynamics question, so it is expressed in the
  interchange format system dynamics already has rather than in a bespoke
  formula sheet.

  **The simulator is not implemented here.** `kotoba-lang/org-oasis-open-xmile`
  owns the equation language, the validator and the Euler/RK4 integrator, and
  `dynamics.xmile`'s docstring names re-implementing it as the anti-pattern.
  This namespace only builds models and hands them over.

  The estimation is not here either — `hayari.core/estimate-decay` is pure
  arithmetic with no dependency on the XMILE library, so the fit can be tested
  without a sibling checkout on the classpath.

  Requires `xmile.model` / `xmile.execute`; see `hayari.simulate` for how the
  classpath is resolved."
  (:require [hayari.core :as core]
            [xmile.model :as m]
            [xmile.execute :as ex]))

(def ^:private stock-name "Attention")
(def ^:private flow-name  "Decay")
(def ^:private rate-name  "decay_rate")

(defn ->decay-model
  "A one-stock XMILE model of a single work's attention decaying at `lambda`.

     Attention(0) = v0
     Decay        = Attention · decay_rate
     d/dt Attention = −Decay

  `days` is the span to simulate. `dt` defaults to 0.25 with RK4 because the
  fit is a CONTINUOUS rate: Euler at dt=1 would advance the stock by a factor
  of (1−λ) per step while the fitted curve falls by e^(−λ), and the two differ
  by about 4% at λ=0.3 — an integration artefact that would otherwise be read
  as model error."
  ([{:keys [name v0 lambda days]}] (->decay-model {:name name :v0 v0 :lambda lambda :days days} {}))
  ([{:keys [name v0 lambda days]} {:keys [dt method]}]
   (-> (m/model (or name "hayari-attention"))
       (m/set-sim-specs (m/sim-specs 0 (double days)
                                     {:xmile/dt (or dt 0.25)
                                      :xmile/method (or method :rk4)
                                      :xmile/time-units "days"}))
       (m/add-variable (m/aux rate-name (str lambda)))
       (m/add-variable (m/stock stock-name (str v0)
                                {:xmile/outflows #{flow-name}
                                 ;; Attention cannot go negative; without this a
                                 ;; long horizon walks the stock below zero and
                                 ;; the model starts describing something that
                                 ;; does not exist.
                                 :xmile/non-negative? true}))
       (m/add-variable (m/flow flow-name (str stock-name " * " rate-name))))))

(defn simulate
  "Run `model` and return {:times [...] :attention [...]}."
  [model]
  (let [r (ex/run model)]
    {:times     (vec (:xmile/times r))
     :attention (vec (get-in r [:xmile/series stock-name]))}))

(defn- at-day
  "Simulated stock value at each integer day, for comparison with observations
  taken once a day."
  [{:keys [times attention]} day-offsets]
  (let [ix (into {} (map vector (map double times) attention))]
    (mapv (fn [d] (get ix (double d))) day-offsets)))

(defn fit-work
  "Estimate a work's decay, build the model, run it, and score the fit.

  Returns {:ok {...}} carrying `:lambda`, `:half-life`, `:r2`, `:mape` and the
  observed/simulated series, or the estimator's error unchanged. `:mape` is
  in-sample: it says the curve reproduces the days it was fitted on, which is
  weaker than a forecast claim and is labelled so nobody reads it as one."
  [{:keys [label points kind]}]
  (let [est (core/estimate-decay points)]
    (if-let [e (:error est)]
      {:error e}
      (let [{:keys [lambda v0 n r2 half-life]} (:ok est)
            offsets (range n)
            model   (->decay-model {:name (or label "work") :v0 v0
                                    :lambda lambda :days (dec n)})
            sim     (simulate model)
            sim-at  (at-day sim offsets)
            obs     (mapv :views (filter #(pos? (or (:views %) 0)) points))
            err     (core/mape obs sim-at)]
        {:ok {:label      label
              :kind       kind
              :lambda     lambda
              :half-life  half-life
              :r2         r2
              :n          n
              :mape       (:ok err)
              :mape-scope :in-sample
              :observed   obs
              :simulated  sim-at
              :model      model}}))))
