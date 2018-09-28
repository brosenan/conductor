(ns netflix-conductor.core
  (:require [clojure.data.json :as json]
            [clj-http.client :as http])
  (:import (com.netflix.conductor.client.task WorkflowTaskCoordinator
                                              WorkflowTaskCoordinator$Builder)
           (com.netflix.conductor.client.worker Worker)
           (com.netflix.conductor.client.http TaskClient)
           (com.netflix.conductor.common.metadata.tasks Task
                                                        TaskResult
                                                        TaskResult$Status)))

(defn root-uri []
  (let [res (System/getenv "CONDUCTOR_API_URL")]
    (when (nil? res)
      (throw (Exception. "Environment variable CONDUCTOR_API_URL not set. If deployed through lambda-kube, please use netflix-conductor.lk/add-client-envs to inject this variable.")))
    res))

(defn task-client []
  (let [client (TaskClient.)]
    (.setRootURI client (root-uri))
    client))

(defn wrap-worker [worker-fn]
  (let [task-def-name (-> worker-fn meta :name)]
    (when (nil? task-def-name)
      (throw (Exception. "A worker function must be given a :name meta-attribute. Make sure you use pass the variable and not the function itself.")))
    (reify Worker
      (getTaskDefName [this]
        (str task-def-name))
      (execute [this task]
        (let [inputs (into {} (.getInputData task))
              outputs (worker-fn inputs)
              result (TaskResult.)]
          (.setStatus result TaskResult$Status/COMPLETED)
          (let [output-data (.getOutputData result)]
            (doseq [[k v] outputs]
              (.put output-data k v)))
          result)))))

(defn coordinator [workers num-threads]
  (-> (WorkflowTaskCoordinator$Builder.)
      (.withTaskClient (task-client))
      (.withWorkers (map wrap-worker workers))
      (.withThreadCount num-threads)
      (.build)))

(defn run-workers [workers num-threads]
  (-> (coordinator workers num-threads)
      (.init)))

(defn define-workflow [workflow]
  (http/post (str (root-uri) "metadata/workflow")
             {:content-type :json
              :body (json/write-str workflow)
              :accept :json}))


(defn define-task [task]
  (http/post (str (root-uri) "metadata/taskdefs")
               {:content-type :json
                :body (json/write-str task)
                :accept :json}))
