# Animagi

Embedded Elasticsearch node for testing. If you are using
`Elasticsearch 1.7.x` in production, via the REST API, this might help
you run tests faster (and minimal test setup) by redirecting the REST
calls to an embedded Elasticsearch node in the same JVM process.

### Naming
In the Harry Potter universe, `animagi` are witches or wizards with
the rare ability to _transform_ into a particular animal at will. Here's
an attempt to _transform_ some costly HTTP calls and elaborate test
setup to faster in-memory implementations.

For the wizards out there, this has indeed been authorized by the
Ministry of Magic.

## Installation

Add `[mourjo/animagi "0.1.0"]` to your `project.clj` and redefine the
calls to ES.

## Examples

Here is an example to mock some of the functions provided
by [elastisch](https://github.com/clojurewerkz/elastisch) if you are
using its REST APIs in production.

```clojure
(ns mcgonagall
  (:require [animagi.core :as ac]
            [clojurewerkz.elastisch.rest.bulk :as erb]
            [clojurewerkz.elastisch.rest.document :as erd]
            [clojurewerkz.elastisch.rest.index :as esi]))

(defn delete-index
  [es _ index-name]
  (ac/delete-index es index-name))

(defn create-index
  [es _ index-name & {:keys [settings mappings]}]
  (ac/create-index es
                   index-name
                   {:settings settings
                    :mapping mappings}))

(defn index-exists?
  [es _ index-name]
  (ac/index-exists? es index-name))

(defn search
  [es _ index-name es-type & args]
  (apply ac/search
         es
         index-name
         es-type
         args))
```

A macro might come in handy to redef all at once...

```clojure
(defmacro with-embedded-es
  [& forms]
  `(let [es# (ac/build-and-start-elasticsearch {:number-of-shards 4})]
     (with-redefs [esi/delete (partial delete-index es#)
                   esi/create (partial create-index es#)
                   esi/exists? (partial index-exists? es#)
                   erd/search (partial search es#)]
       (try
         ~@forms
         (finally (ac/stop-elasticsearch es#))))))
```

## TODO
* Add more tests
* Support other APIs provided by Elasticsearch

## License

Copyright © 2017 Mourjo Sen

Distributed under the Eclipse Public License version 1.0.
