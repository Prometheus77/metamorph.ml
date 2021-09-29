(ns scicloj.metamorph.to-disk-perf
  (:require [clojure.test :refer [deftest is]]
            [scicloj.metamorph.core :as morph]
            [scicloj.metamorph.ml :as ml]
            [scicloj.metamorph.ml.gridsearch :as gs]
            [scicloj.metamorph.ml.loss :as loss]
            [scicloj.ml.smile.classification]
            [tech.v3.dataset.metamorph :as ds-mm]
            [tech.v3.dataset :as ds]
            [tech.v3.dataset.column-filters :as cf]
            [tech.v3.dataset.modelling :as ds-mod]
            [scicloj.metamorph.ml.gridsearch :as gs]
            [tablecloth.api :as tc]
            [scicloj.ml.smile.classification]
            [fastmath.stats :as stats]
            [taoensso.nippy :as nippy]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.util.UUID))
  


(defn keys-in
    "Returns a sequence of all key paths in a given map using DFS walk."
    [m]
    (letfn [(children [node]
              (let [v (get-in m node)]
                (if (map? v)
                  (map (fn [x] (conj node x)) (keys v))
                  [])))
            (branch? [node] (-> (children node) seq boolean))]
      (->> (keys m)
           (map vector)
           (mapcat #(tree-seq branch? children %)))))

(defn find-model-data [m]
  (->>
   (keys-in m)
   (filter #(= :model-data (last %)))))

(defn nippy-handler [result]
  (def result result)
  (let [freezable-result
        (ml/multi-dissoc-in result
                            [
                             [:pipe-fn]
                             [:metric-fn]])

        small-result
        (ml/multi-dissoc-in result
                            (concat ml/default-result-dissoc-in-seq
                                    (find-model-data result)
                                    [
                                     [:pipe-fn]
                                     [:metric-fn]]))
        uuid (UUID/randomUUID)]
    (nippy/freeze-to-file (str "/tmp/" uuid "-small.nippy") small-result)))

(def  ds (tc/dataset "https://raw.githubusercontent.com/techascent/tech.ml/master/test/data/iris.csv" {:key-fn keyword}))
(defn make-pipe-fn [options]

  (morph/pipeline
   (fn [ctx]
     (assoc ctx :pipe-options options))
   (ds-mm/set-inference-target :species)
   (ds-mm/categorical->number cf/categorical)
   (ml/model options)))

(def gs-options
  {:trees (gs/categorical [10 50 100 500])
   :split-rule (gs/categorical [:gini :entropy])
   :model-type :smile.classification/random-forest})

(def pipe-fn-seq
  (->>
   (gs/sobol-gridsearch gs-options)
   (take 100)
   (map make-pipe-fn)))

(def train-split-seq (tc/split->seq ds))


(def results
  (ml/evaluate-pipelines pipe-fn-seq train-split-seq  loss/classification-accuracy :accuracy
                         {:map-fn :pmap
                          :evaluation-handler-fn nippy-handler
                          :result-dissoc-in-seq ml/result-dissoc-in-seq--remove-ctxs
                          :return-best-pipeline-only true
                          :return-best-crossvalidation-only true}))

(clojure.pprint/pprint results)


(comment
  (->>
   (file-seq (io/file "/tmp"))
   (filter #(str/ends-with? (.getPath %) ".nippy"))
   (map str)
   (map nippy/thaw-from-file)
   (map #(hash-map :pipe-options (get-in % [:fit-ctx :pipe-options])
                   :metric (get-in % [:train-transform :metric])))
   (group-by :pipe-options)
   (map (fn [[k v]]
          (def k k)
          (def v v)
          (hash-map
           :mean (stats/mean (map :metric v))
           :opts k)))
   (tc/dataset)
   println)


   ;;        ;; (def results results)
   ;;        (map keys results)))


  (first results)
  (count (first results))

  ;; (map () keys)







  :ok)
