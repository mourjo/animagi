(ns animagi.core
  (:require [animagi.components :as ac]
            [cheshire.core :as cc]
            [clojure.tools.logging :as ctl]
            [com.stuartsierra.component :as component])
  (:import java.util.UUID
           org.elasticsearch.action.admin.indices.create.CreateIndexRequest
           org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse
           [org.elasticsearch.action.bulk BulkItemResponse BulkResponse]
           [org.elasticsearch.action.search SearchRequestBuilder SearchType]
           org.elasticsearch.client.Client
           org.elasticsearch.common.settings.ImmutableSettings))

(defn build-and-start-elasticsearch
  [{:keys [build-only? number-of-shards http-enabled? http-port]
    :or {number-of-shards 1
         http-port "9200-9300"}
    :as opts}]
  (let [refined-opts (if (and (string? http-port) http-enabled?)
                       opts
                       (dissoc opts :http-enabled? :http-port))]
    (if build-only?
      (ac/map->ElasticSearch refined-opts)
      (component/start (ac/map->ElasticSearch refined-opts)))))


(defn start-elasticsearch
  [es]
  (component/start es))


(defn stop-elasticsearch
  [es]
  (component/stop es))


(defn create-index
  [es index-name {:keys [settings mapping]}]
  (assert (<= (count mapping) 1)
          "Currently only one type per index is supported.")
  (let [index-settings (.. (ImmutableSettings/settingsBuilder)
                           (loadFromSource (cc/generate-string settings))
                           build)
        index-request (CreateIndexRequest. index-name index-settings)
        [es-type es-mapping] (first mapping)]
    (.. ^Client (:es-client es)
        admin
        indices
        (create index-request)
        actionGet)
    (when (seq es-mapping)
      (.. ^Client (:es-client es)
          admin
          indices
          (preparePutMapping (into-array String [index-name]))
          (setType (name es-type))
          (setSource (cc/generate-string es-mapping))
          get))))


(defn delete-index
  [es index-name]
  (.. ^Client (:es-client es)
      admin
      indices
      (prepareDelete (into-array String [(name index-name)]))
      execute
      get))


(defn reset-index
  [es index-name {:keys [settings mapping] :as index-args}]
  (try
    (delete-index es index-name)
    (catch Exception e))
  (create-index es index-name index-args))


(defn insert-doc
  [es es-index es-type doc & {:keys [id]}]
  (if (seq id)
    (.. ^Client (:es-client es)
        (prepareIndex es-index es-type)
        (setSource (cc/generate-string doc))
        (setId id)
        execute
        actionGet)
    (.. ^Client (:es-client es)
        (prepareIndex es-index es-type)
        (setSource (cc/generate-string doc))
        execute
        actionGet)))


(defn insert-bulk
  ([es es-index es-type docs]
   (insert-bulk es
                es-index
                es-type
                (repeatedly (count docs) #(str (UUID/randomUUID)))
                docs))
  ([es es-index es-type ids docs]
   (let [client ^Client (:es-client es)
         bulk-request-builder (.prepareBulk client)]
     (dotimes [i (min (count ids) (count docs))]
       (.add bulk-request-builder
             (.. client
                 (prepareIndex es-index es-type (str (nth ids i)))
                 (setSource (cc/generate-string (nth docs i))))))
     (let [^BulkResponse bulk-response (.. bulk-request-builder
                                           execute
                                           actionGet)]
       (if (.hasFailures bulk-response)
         (ctl/error "Failure in bulk indexing."
                    (for [^BulkItemResponse bulk-item-response (.getItems ^BulkResponse bulk-response)]
                      (.. bulk-item-response
                          getFailure
                          toString)))
         bulk-response)))))


(defn search
  [es es-index es-type & {:keys [query fields sort size from search_type aggs aggregations scroll]
                          :or {query {:match_all {}}
                               search_type "query_then_fetch"
                               fields [:_source]
                               from 0
                               size 25}
                          :as search-params}]
  (let [updated-search-params (dissoc search-params
                                      :search_type
                                      :scroll)
        search-request-builder (.. ^Client (:es-client es)
                                   (prepareSearch (into-array String [es-index]))
                                   (setTypes (into-array String [es-type]))
                                   (setSearchType (SearchType/fromString (name search_type)))
                                   (setSource (cc/generate-string updated-search-params)))]
    (when (or (seq scroll) (= "scan" (name search_type)))
      (.setScroll ^SearchRequestBuilder search-request-builder ^String scroll))
    (-> (.. search-request-builder
            execute
            actionGet
            toString)
        (cc/parse-string keyword))))


(defn scroll-results
  [es scroll-id & {:keys [scroll]
                   :or {scroll "1m"}}]
  (-> (.. ^Client (:es-client es)
          (prepareSearchScroll ^String scroll-id)
          (setScroll ^String scroll)
          execute
          actionGet
          toString)
      (cc/parse-string keyword)))


(defn scroll-seq
  ([es prev-resp {:keys [search_type scroll]
                  :or {scroll "1m"}}]
   (let [hits (get-in prev-resp [:hits :hits])
         scroll-id (:_scroll_id prev-resp)]
     (if (or (seq hits) (= search_type "scan"))
       (concat hits (lazy-seq (scroll-seq es (scroll-results es scroll-id :scroll "1m"))))
       hits)))
  ([es prev-resp]
   (scroll-seq es prev-resp nil)))


(defn scan-and-scroll
  [es es-index es-type & {:keys [query fields size search_type scroll]
                          :or {query {:match_all {}}
                               search_type "query_then_fetch"
                               size 25
                               scroll "1m"}
                          :as search-params}]
  (scroll-seq es
              (apply search
                     es
                     es-index
                     es-type
                     (apply concat (seq search-params)))))


(defn index-exists?
  [es index-name]
  (let [response (.. ^Client (:es-client es)
                     admin
                     indices
                     (prepareExists (into-array String [index-name]))
                     execute
                     actionGet)]
    (.isExists ^IndicesExistsResponse response)))


(defn count-docs
  [es es-index es-type & {:keys [query]
                          :or {query {:match_all {}}}}]
  (let [search-res (search es
                           es-index
                           es-type
                           :query query
                           :search_type "count")]
    (->> (select-keys search-res [:_shards])
         (merge {:count (get-in search-res [:hits :total])}))))


(defn refresh-index
  ([es es-index]
   (.. ^Client (:es-client es)
       admin
       indices
       (prepareRefresh (into-array String [es-index]))
       get))
  ([es]
   (.. ^Client (:es-client es)
       admin
       indices
       (prepareRefresh (into-array String []))
       get)))

