(defproject mourjo/animagi "0.1.0"
  :description "Embedded Elasticsearch node for testing"
  :url "https://github.com/mourjo/animagi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cheshire "5.5.0"]
                 [commons-io/commons-io "2.4"]
                 [net.java.dev.jna/jna "4.3.0"]
                 [slingshot "0.12.2" :exclusions [org.clojure/clojure]]
                 [clj-time "0.11.0"]
                 [org.codehaus.groovy/groovy-all "2.3.2"]
                 [com.stuartsierra/component "0.3.2"]
                 [org.slf4j/slf4j-log4j12 "1.7.21"]
                 [org.elasticsearch/elasticsearch "1.7.5"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.logging "0.3.1"]]}
             :uberjar {:aot :all}}
  :jvm-opts ^:replace ["-XX:MaxPermSize=512M" "-Xmx6g"]
  :repl-options {:init-ns animagi.core}
  :target-path "target/%s"
  :global-vars {*warn-on-reflection* true})
