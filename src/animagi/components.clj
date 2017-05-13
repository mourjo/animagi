(ns animagi.components
  (:require [clojure.tools.logging :as ctl]
            [com.stuartsierra.component :as component])
  (:import java.io.File
           org.apache.commons.io.FileUtils
           org.elasticsearch.common.logging.ESLoggerFactory
           [org.elasticsearch.node Node NodeBuilder]))

(defonce ^String never "12000s")

(defrecord ElasticSearch [es-node es-client
                          number-of-shards ^String http-port]
  component/Lifecycle

  (start [this]
    (ctl/info "Starting ES")
    (let [local-es-dir (str (System/getProperty "user.dir") "/target/es")
          node-builder (.. (NodeBuilder/nodeBuilder)
                           (local true)
                           (clusterName "hogwarts")
                           (data true)
                           (loadConfigSettings false))
          _ (.setLevel (ESLoggerFactory/getRootLogger) "ERROR")
          node-settings (.settings node-builder)
          _ (doto node-settings
              (.put "node.name" "elderwand")
              (.put "node.master" "true")
              (.put "node.data" "true")
              (.put "index.number_of_shards" (str (or number-of-shards 1)))
              (.put "index.number_of_replicas" "0")
              (.put "index.store.throttle.type" "none")
              (.put "path.data" local-es-dir)
              (.put "index.queries.cache.everything" true)
              (.put "indices.queries.cache.size" "50%")
              (.put "index.refresh_interval" "0.001s")
              (.put "path.logs" local-es-dir)
              (.put "path.plugins" local-es-dir)
              (.put "path.conf" local-es-dir)
              (.put "script.inline" "on")
              (.put "index.translog.sync_interval" never)
              (.put "index.translog.durability" "async")
              (.put "cluster.info.update.interval" never)
              (.put "bootstrap.mlockall" true)
              (.put "http.enabled" (boolean http-port))
              (.put "index.store.type" "memory")
              (.put "network.host" "127.0.0.1")
              (.put "discovery.zen.ping.multicast.enabled" false)
              (.put "gateway.type" "none")
              (.put "client.transport.sniff" false)
              (.put "script.indexed" "on"))
          _ (when (boolean http-port)
              (.put node-settings "http.port" http-port))
          node (.node node-builder)]
      (assoc this
             :http-port (.. node settings (get "http.port"))
             :es-node (.start node)
             :es-client (.client node))))

  (stop [this]
    (ctl/info "Stopping ES")
    (.close ^Node es-node)
    (-> "user.dir"
        System/getProperty
        (str "/target/es")
        File.
        FileUtils/deleteQuietly)
    (assoc this
           :es-node nil
           :es-client nil)))

