(ns conductor.core
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
        (let [inputs (into {} (for [[k v] (.getInputData task)]
                                [(keyword k) v]))
              outputs (worker-fn inputs)
              result (TaskResult.)]
          (.setStatus result TaskResult$Status/COMPLETED)
          (let [output-data (.getOutputData result)]
            (doseq [[k v] outputs]
              (.put output-data (name k) v)))
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

(declare add-unique-ids-to-tasks)

(defn add-unique-ids-to-task [task prefix curr prev]
  (let [task (assoc task :taskReferenceName (str prefix curr))]
    (case (:type task)
      :FORK_JOIN (update task :forkTasks #(map (fn [alt num]
                                                 (add-unique-ids-to-tasks alt (str prefix curr "_" num "_") 0 {}))
                                               % (range (count %))))
      :JOIN (assoc task :joinOn (for [alt (:forkTasks prev)]
                                  (-> alt last :taskReferenceName)))
      task)))

(defn add-unique-ids-to-tasks [tasks prefix curr prev]
  (if (empty? tasks)
    nil
    ;; else
    (let [task (add-unique-ids-to-task (first tasks) prefix curr prev)]
      (cons task (add-unique-ids-to-tasks (rest tasks) prefix (inc curr) task)))))

(defn add-unique-ids [workflow]
  (update workflow :tasks add-unique-ids-to-tasks "task" 0 {}))

(defn define-workflow [workflow]
  (http/post (str (root-uri) "metadata/workflow")
             {:content-type :json
              :body (json/write-str (add-unique-ids workflow))
              :accept :json}))


(defn define-task [task]
  (http/post (str (root-uri) "metadata/taskdefs")
               {:content-type :json
                :body (json/write-str task)
                :accept :json}))

(defn trigger-workflow [workflow version params]
  (http/post (str (root-uri) "workflow/" (name workflow))
             {:content-type :json
              :query-params {:version version}
              :body (json/write-str params)}))
