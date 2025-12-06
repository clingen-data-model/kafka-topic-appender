(ns appender
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event :as event]
            [genegraph.framework.env :as env]
            [io.pedestal.http :as http]
            [io.pedestal.log :as log])
  (:gen-class))

(def admin-env
  (if (or (System/getenv "DX_JAAS_CONFIG_DEV")
          (System/getenv "DX_JAAS_CONFIG")) ; prevent this in cloud deployments
    {:platform "prod"
     :dataexchange-genegraph (System/getenv "DX_JAAS_CONFIG")
     :local-data-path "data/"}
    {}))

(def local-env
  (case (or (:platform admin-env) (System/getenv "GENEGRAPH_PLATFORM"))
    "prod" (assoc (env/build-environment "974091131481" ["dataexchange-genegraph"])
                  :kafka-user "User:2592237"
                  :kafka-consumer-group "genegraph-appender-2")
    {:dataexchange-genegraph (System/getenv "DX_JAAS_CONFIG")}))

(def env
  (merge local-env admin-env))

(def data-exchange
  {:type :kafka-cluster
   :kafka-user (:kafka-user env)
   :common-config {"ssl.endpoint.identification.algorithm" "https"
                   "sasl.mechanism" "PLAIN"
                   "request.timeout.ms" "20000"
                   "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                   "retry.backoff.ms" "500"
                   "security.protocol" "SASL_SSL"
                   "sasl.jaas.config" (:dataexchange-genegraph env)}
   :consumer-config {"key.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"
                     "value.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"}
   :producer-config {"key.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"
                     "value.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"}})


(def append-gene-validity-raw
  {:name ::append-gene-validity-raw
   :enter (fn [e]
            (event/publish
             e
             (assoc
              (select-keys e [::event/data ::event/key ::event/value ::event/timestamp])
              ::event/data (::event/value e)
              ::event/topic :gene-validity-complete)))})

(def append-gene-validity-legacy
  {:name ::append-gene-validity-raw
   :enter (fn [e]
            (event/publish
             e
             (assoc
              (select-keys e [::event/data ::event/key ::event/value ::event/timestamp])
              ::event/data (::event/value e)
              ::event/topic :gene-validity-legacy-complete)))})

(def gene-validity-raw-topic
  {:name :gene-validity-raw
   :serialization :json
   :kafka-cluster :data-exchange
   :kafka-topic "gene_validity_raw"})

(def gene-validity-legacy-topic
  {:name :gene-validity-legacy
   :serialization :json
   :kafka-cluster :data-exchange
   :kafka-topic "gene_validity"})

(def gene-validity-legacy-complete-topic
  {:name :gene-validity-legacy-complete
   :serialization :json
   :kafka-topic "gene_validity_legacy_all"
   :kafka-cluster :data-exchange
   :kafka-topic-config {}})

(def gene-validity-complete-topic
  {:name :gene-validity-complete
   :kafka-cluster :data-exchange
   :serialization :json
   :buffer-size 5
   :kafka-topic "gene_validity_all"
   :kafka-topic-config {}})

(def gv-ready-server
  {:gene-validity-server
   {:type :http-server
    :name :gv-ready-server
    ::http/host "0.0.0.0"
    ::http/allowed-origins {:allowed-origins (constantly true)
                            :creds true}
    ::http/routes
    [["/ready"
      :get (fn [_] {:status 200 :body "server is ready"})
      :route-name ::readiness]
     ["/live"
      :get (fn [_] {:status 200 :body "server is live"})
      :route-name ::liveness]]
    ::http/type :jetty
    ::http/port 8888
    ::http/join? false
    ::http/secure-headers nil}})

(def gv-appender-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange data-exchange}
   :topics {:gene-validity-raw
            (assoc gene-validity-raw-topic
                   :type :kafka-consumer-group-topic
                   :kafka-consumer-group (:kafka-consumer-group env))
            :gene-validity-complete
            (assoc gene-validity-complete-topic
                   :type :kafka-producer-topic
                   :serialization nil) ; don't re-serialize str as json
            :gene-validity-legacy
            (assoc gene-validity-legacy-topic
                   :type :kafka-consumer-group-topic
                   :kafka-consumer-group (:kafka-consumer-group env))
            :gene-validity-legacy-complete
            (assoc gene-validity-legacy-complete-topic
                   :type :kafka-producer-topic
                   :serialization nil)} ; don't re-serialize str as json
   :processors {:gene-validity-appender
                {:name :gene-validity-appender
                 :type :processor
                 :subscribe :gene-validity-raw
                 :kafka-cluster :data-exchange
                 :kafka-transactional-id "gv-appender-raw"
                 :interceptors [append-gene-validity-raw]}
                :gene-validity-legacy-appender
                {:name :gene-validity-legacy-appender
                 :type :processor
                 :subscribe :gene-validity-legacy
                 :kafka-cluster :data-exchange
                 :kafka-transactional-id "gv-appender-legacy"
                 :interceptors [append-gene-validity-legacy]}}
   :http-servers gv-ready-server})


(defn -main [& args]
  (log/info :fn ::-main
            :msg "starting kafka-topic-appender")
  (let [app (p/init gv-appender-def)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (log/info :fn ::-main
                                           :msg "stopping kafka-topic-appender")
                                 (p/stop app))))
    (p/start app)))
