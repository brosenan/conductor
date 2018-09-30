(ns conductor.lk
  (:require [lambdakube.core :as lk]
            [lambdakube.util :as lku]
            [clojure.java.io :as io]
            [conductor.core :as conduct])
  (:gen-class))


(defn conductor-base-url [server]
  (str "http://" (:hostname server) ":" (-> server :ports :api) "/api/"))

(defn module [$]
  (let [common-labels {:app :conductor}]
    (-> $
        (lk/rule ::elasticsearch []
                 (fn []
                   (-> (lk/pod :elasticsearch (merge common-labels
                                                     {:role :elasticsearth}))
                       (lk/add-container :es "elasticsearch:2.4")
                       (lk/deployment 1)
                       (lk/expose-cluster-ip :es (comp
                                                  (lk/port :es :es1 9200 9200)
                                                  (lk/port :es :es2 9300 9300))))))
        (lk/rule ::dynomite []
                 (fn []
                   (-> (lk/pod :dynomite (merge common-labels
                                                {:rule :dynomite}))
                       (lk/add-container :dyno "v1r3n/dynomite")
                       (lk/deployment 1)
                       (lk/expose-cluster-ip :dyno1 (comp
                                                     (lk/port :dyno :d1 8101 8101)
                                                     (lk/port :dyno :d2 8102 8102)
                                                     (lk/port :dyno :d3 22122 22122)
                                                     (lk/port :dyno :d4 22222 22222))))))
        (lk/rule ::server [::elasticsearch ::dynomite]
                 (fn [es dyno]
                   (-> (lk/pod :conductor-server (merge common-labels
                                                        {:role :server}))
                       (lk/add-container :conductor "brosenan/conductor-server")
                       (lk/deployment 1)
                       (lk/expose-cluster-ip :conductor-server-svc (lk/port :conductor :api 8080 8080)))))
        (lk/rule ::ui [::server]
                 (fn [server]
                   (-> (lk/pod :conductor-ui (merge common-labels
                                                        {:role :ui}))
                       (lk/add-container :conductor "brosenan/conductor-ui")
                       (lk/update-container :conductor lk/add-env {:WF_SERVER (conductor-base-url server)})
                       (lk/deployment 1)
                       (lk/expose-cluster-ip :conductor-ui (lk/port :conductor :web 5000 5000))))))))

(defn add-client-envs [cont server]
  (-> cont
      (lk/add-env {:CONDUCTOR_API_URL (conductor-base-url server)})))

(defn clj-worker-deployment [server name labels deps code & {:keys [num-pods num-threads constants]
                                                             :or {num-pods 1
                                                                  num-threads 2
                                                                  constants {}}}]
  (let [workers (vec (for [[defworker worker] code
                           :when (= defworker 'defworker)]
                       `(var ~worker)))
        code (for [[defworker & args] code]
               (if (= defworker 'defworker)
                 (cons 'defn args)
                 ;; else
                 (cons defworker args)))
        code (concat code `[(require 'conductor.core)
                            (conductor.core/define-tasks ~workers)
                            (conductor.core/run-workers ~workers ~num-threads)])]
    (-> (lk/pod name labels)
        (lku/add-clj-container name deps constants code)
        (lk/update-container name add-client-envs server)
        (lku/wait-for-service-port server :api)
        (lk/deployment num-pods))))

(defn -main []
  (-> (lk/injector)
      (module)
      (lk/standard-descs)
      (lk/get-deployable {})
      (lk/to-yaml)
      (lk/kube-apply (io/file "conductor.yaml"))))
