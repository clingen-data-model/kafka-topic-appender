(ns setup
  (:require [appender :as appender]
            [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event :as event]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.set :as set])
  (:import [java.time Instant OffsetDateTime Duration]
           [java.io PushbackReader]))


;; Step 1
;; Set up kafka topics, configure permissions

;; The kafka-admin/configure-kafka-for-app! function accepts an
;; (initialized) Genegraph app and creates the topics and necessary
;; permissions for those topics.

(comment

  (kafka-admin/configure-kafka-for-app!
   (p/init appender/gv-appender-def))
  
  )


;; Step 3.2
;; :gene-validity-legacy: old 'summary' format for gene validity data, needs to be seeded with some
;;                        early data that needed to be recovered due to a misconfiguration.
;; The data needed to seed this topic is stored in Google Cloud Storage
;; and must be downloaded and unzipped into a local directory.

;; Set this var for the path on your local system
(def gene-validity-legacy-path
  "/Users/tristan/data/genegraph-neo/neo4j-legacy-events")

;; This step has been flaky on occasion. Remember to verfiy that all historic curations have been
;; successfully written to the topic before moving on.

(def upload-gv-neo4j-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange appender/data-exchange}
   :topics {:gene-validity-legacy-complete
            (assoc appender/gene-validity-legacy-complete-topic
                   :type :kafka-producer-topic
                   :create-producer true)}})

(defn legacy-gv-edn->event [f]
  (with-open [pbr (PushbackReader. (io/reader f))]
    (let [{:keys [id score-string]} (:genegraph.sink.event/value (edn/read pbr))]
      {::event/data {:iri id
                     :scoreJson score-string}
       ::event/key id})))

(comment
  (def upload-gv-neo4j
    (p/init upload-gv-neo4j-def))
  (p/start upload-gv-neo4j)
  (p/stop upload-gv-neo4j)

  (->> gene-validity-legacy-path
       io/file
       file-seq
       (filter #(.isFile %))
       (run! #(p/publish (get-in upload-gv-neo4j
                                   [:topics  :gene-validity-legacy-complete])
                           (legacy-gv-edn->event %))))

  )


;; Step 3.3
;; :gene-validity-complete: raw gene validity data,
;; needs to be seeded with gene validity curations
;; made prior to release of the GCI
;; The data needed to seed this topic is stored in Google Cloud Storage

;; This is another potentially flaky one. Remember to verfiy that all historic curations have been
;; successfully written to the topic before moving on.

(def gv-setup-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange appender/data-exchange}
   :topics {:gene-validity-complete
            (assoc appender/gene-validity-complete-topic
                   :type :kafka-producer-topic
                   :create-producer true
                   :serialization nil)}}) ; just copying strings, do not serialize

(def sept-1-2020
  (-> (OffsetDateTime/parse "2020-09-01T00:00Z")
      .toInstant
      .toEpochMilli))

(defn prior-event->publish-fn [file]
  (with-open [pbr (PushbackReader. (io/reader file))]
    (-> (edn/read pbr)
        (set/rename-keys {:genegraph.sink.event/key ::event/key
                          :genegraph.sink.event/value ::event/data})
        (select-keys [::event/key ::event/data])
        (assoc ::event/timestamp sept-1-2020))))

(defn event-files [directory]
  (->> directory
       io/file
       file-seq
       (filter #(re-find #".edn" (.getName %)))))

(comment
  (def gv-setup (p/init gv-setup-def))
  (p/start gv-setup)
  (p/stop gv-setup)

  (run! #(p/publish (get-in gv-setup [:topics :gene-validity-complete])
                   (prior-event->publish-fn %))
        (concat
         (event-files "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-snapshot")
         (event-files "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-missing-data")))
  
  (count
   (concat
    (event-files "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-snapshot")
    (event-files "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-missing-data")))
  )
