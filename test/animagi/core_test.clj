(ns animagi.core-test
  (:require [animagi.core :refer :all]
            [clojure.test :refer :all]))

(defonce es-comp {:number-of-shards 1})

(def mysettings
   '{:analysis {:analyzer {:analyzer2 {:type "custom", :tokenizer
   "keyword", :filter ["asciifolding" "lowercase"]}, :analyzer1 {:type
   "custom", :tokenizer "classic", :filter ["asciifolding"
   "lowercase"]}}}})


(def mymapping
  '{:mytype {:properties {:name {:type "string" :analyzer "analyzer1"
 :fields {:field1 {:type "string" :doc_values true :index
 "not_analyzed" :include_in_all false}}} :author_email {:type "string"
 :index "not_analyzed" :fields {:autocomplete {:type "completion"
 :max_input_length 15 :analyzer "analyzer2" :preserve_separators false
 :context {:topic {:type "category" :path :topic}}}}}}}})


(defn once-fixture
  [f]
  (try
    (alter-var-root #'es-comp build-and-start-elasticsearch)
    (f)
    (finally
      (alter-var-root #'es-comp stop-elasticsearch))))


(use-fixtures :once once-fixture)

(deftest test-reset-index
  (reset-index es-comp
               "myindex"
               {:settings mysettings
                :mapping mymapping})
  (refresh-index es-comp)
  (is (= 0
         (:count (count-docs es-comp
                             "myindex"
                             "mytype")))))


(deftest test-search
  (reset-index es-comp
               "myindex"
               {:settings mysettings
                :mapping mymapping})

  (insert-bulk es-comp
               "myindex"
               "mytype"
               (repeat 100 {:topic "demo"
                            :author_email "abcd@gmail.com"
                            :name "Michael Jackson"}))

  (insert-bulk es-comp
               "myindex"
               "mytype"
               (repeat 100 {:topic "demo"
                            :author_email "abcd@gmail.com"
                            :name "Janet Jackson"}))

  (refresh-index es-comp)


  (is (= [10 200]
         ((juxt (comp count #(get-in % [:hits :hits]))
                #(get-in % [:hits :total]))
          (search es-comp
                  "myindex"
                  "mytype"
                  :query {:filtered {:filter {:term {:topic "demo"}}}}
                  :fields [:author_email]
                  :size 10
                  :from 10
                  :search_type "query_and_fetch"))))

  (is (= [10 200]
         ((juxt (comp count #(get-in % [:hits :hits]))
                #(get-in % [:hits :total]))
          (search es-comp
                  "myindex"
                  "mytype"
                  :query {:filtered {:filter {:term {:topic "demo"}}}}
                  :fields [:author_email]
                  :size 10
                  :from 10
                  :search_type "dfs_query_then_fetch"))))


  (-> (search es-comp
              "myindex"
              "mytype"
              :query {:match {:name "michael jackson"}}
              :fields [:author_email]
              :size 10
              :from 10
              :search_type "count"
              :aggs {:fld-suggestions
                     {:terms
                      {:field :name.field1
                       :size 100
                       :order {:ranking "desc"}}
                      :aggs {:ranking {:max {:script "_score"}}}}})
      (get-in [:aggregations :fld-suggestions :buckets])
      (#(= (set (map :key %)) #{"Michael Jackson" "Janet Jackson"}))
      is)


  (is (= 200 (get-in
              (search es-comp
                      "myindex"
                      "mytype"
                      :query {:match {:name "michael jackson"}}
                      :fields [:author_email]
                      :size 10
                      :from 10
                      :search_type :scan
                      :size 1000
                      :scroll "5m")
              [:hits :total])))


  (is (= 200
         (count
          (scroll-seq es-comp
                      (search es-comp
                              "myindex"
                              "mytype"
                              :query {:match_all {}}
                              :search_type "query_then_fetch"
                              :size 10
                              :scroll "10m"))))))
