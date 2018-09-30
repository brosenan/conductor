(ns conductor.core-test
  (:require [clojure.test :refer :all]
            [conductor.core :as conduct]
            [conductor.lk :as conduct-lk]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]
            [lambdakube.testing :as lkt]))


;; # Introduction

;; [Conductor](https://netflix.github.io/conductor/) is an open-source
;; platform for running batch jobs. It allows its users to define
;; _workflows_ -- directed asyclic graphs (DAGs) of _tasks_, which are
;; then executed by _workers_. Conductor orchestrates this execution,
;; by providing workers with tasks, according to the ordering defined
;; by the dependency graph.

;; This library provides two things. The `conductor.core` namespace
;; provides a lightweight Clojure client library for Conductor, while
;; the `conductor.lk` namespace provides a
;; [lambda-kube](https://github.com/brosenan/lambda-kube) module and
;; additional functions, for deploying Conductor on Kubernetes, along
;; with workers.

;; ## The Example

;; In this document we describe its usage through an example. We
;; deploy a [Redis](https://redis.io/) database, and use a workflow to
;; update keys on it.
(defn test-module-part-1 [$]
  (-> $
      (lk/rule ::redis []
               (fn []
                 (-> (lk/pod :redis {:role :database})
                     (lk/add-container :redis "redis:5.0-rc")
                     (lk/deployment 1)
                     (lk/expose-cluster-ip :redis (lk/port :redis :redis 6379 6379)))))))

;; The workflow we use consists of five _stages_, consisting of ten
;; tasks running in parallel in each of them. Each task updates a
;; different key in the database, appending the stage number to its
;; original value. For example, task 1 in stage 0 will append the
;; digit 0 to the key `t1`. Task 7 on stage 3 will append the digit 3
;; to the key `t7`, etc. Eventually, we want to see that all keys hold
;; the value `01234`, indicating that the tasks were executed at the
;; correct order.

;; ![A screenshot of the Conductor UI displaying progress on the workflow in this example](conductor-ui-graph.png)


;; ## How This Example is Written

;; The code in this example consists of two parts. One is a set of
;; module functions (e.g., `test-module-part-1` above), defining an
;; example system, consisting of a Conductor deployment, an example
;; worker deployment defining the test task, a test pod for running
;; the workflow and checking the results, and of-course the above
;; Redis database.

;; The worker deployment and the test pod are what lambda-kube refers
;; to as [Clojure
;; nanoservices](https://github.com/brosenan/lambda-kube/blob/master/util.md#clojure-nanoservices),
;; that is, their Clojure code is embedded (as s-expressions) in their
;; definitions. For didactic reasons, we build this code outside the
;; module, by adding one s-expression at a time to an atom.
(def worker-code (atom []))
(def test-code (atom []))
(defn worker-expr [& exprs]
  (doseq [expr exprs]
    (swap! worker-code conj expr)))
(defn test-expr [& exprs]
  (doseq [expr exprs]
    (swap! test-code conj expr)))

;; Then, within the module, we will take the code from `@worker-code`
;; and `@test-code`.

;; # The Worker Nanoservice

;; The worker uses [Carmine](https://github.com/ptaoussanis/carmine)
;; to interact with Redis. At this point, dependency on
;; `brosenan/conductor` needs to be made explicitly. We hope to change
;; that in future releases.
(def worker-deps '[[org.clojure/clojure "1.9.0"]
                   [com.taoensso/carmine "2.19.0"]
                   [brosenan/conductor "0.1.0"]])

(worker-expr
 '(ns main
    (:require [taoensso.carmine :as car])))

;; Like other nanoservices, it is defined within the `main`
;; namespace. We can make any Clojure definitions, such as defining
;; the database connection. This definition is based on the
;; `redis-host` and `redis-port` constants, which we will provide
;; through dependency injection.
(worker-expr
 '(def server1-conn {:pool {} :spec {:uri (str "redis://" redis-host ":" redis-port "/")}}))

;; The `defworker` form is identical to `defn`, but marks the function
;; being defined as a task. The function always takes one argument, a
;; map containing the input parameters, and returns a map of output
;; parameters. The library assumes that the input parameters are
;; deconstructed using Clojure's `{:keys [...]}` construct.

;; We define two worker functions. The first, `initialize-key` takes a
;; `:key` parameter, and initializes it to an empty string in the
;; database.
(worker-expr
 '(defworker initialize-key [{:keys [key]}]
    (println "Initializing key" key)
    (car/wcar server1-conn (car/set key ""))
    ;; Return value must be a map.
    {}))

;; The second worker function, `append-to-key`, takes `:key`, the name
;; of the key to mutate on Redis, and `:value`, the value to append to
;; this key. We use a non-atomic append (read the old value, append,
;; and write back) deliberately, to test that synchronization is
;; guaranteed by the workflow. The worker function returns the updated
;; value as its `:updated` output parameter. Output parameters, as
;; well as other [Conductor task
;; metadata](https://netflix.github.io/conductor/metadata/), are
;; derived from meta attributes.
(worker-expr
 '(defworker append-to-key
    {:outputKeys [:updated]}
    [{:keys [key value]}]
    (let [orig (car/wcar server1-conn (car/get key))
          updated (str orig value)]
      (println "Updating key" key "from" orig "to" updated)
      (car/wcar server1-conn (car/set key updated))
      {:updated updated})))

;; Turning this code into a Kubernetes deployment involves the use of
;; the `clj-worker-deployment` function (from `conductor.lk`). We
;; define this deployment in a module, with dependencies on the
;; Conductor server and the Redis database.
(defn test-module-part-2 [$]
  (-> $
      (lk/rule :test-worker-depl [::conduct-lk/server ::redis]
               (fn [server redis]
                 (conduct-lk/clj-worker-deployment
                  server
                  :my-worker
                  {:role :worker}
                  worker-deps
                  @worker-code
                  :num-pods 1
                  :num-threads 3
                  :constants {:redis-host (:hostname redis)
                              :redis-port (-> redis :ports :redis)})))))


;; # The Test

;; The test uses this library to interact with Conductor, and Carmine
;; to check the final result on Redis.
(def test-deps '[[org.clojure/clojure "1.9.0"]
                 [com.taoensso/carmine "2.19.0"]
                 [brosenan/conductor "0.1.0"]])

(test-expr
 '(ns main-test
    (:require [conductor.core :as con]
              [taoensso.carmine :as car]
              [midje.sweet :refer :all])))

;; ## The Workflow

;; Our workflow consists of six _stages_, each consisting of ten
;; _tasks_. A stage is a fork/join pair, performing a number of tasks
;; in parallel, and then waiting for all of them to complete. The
;; first stage consists of initializing all keys to empty strings, and
;; the rest of the stages append the digits `0` through `4` to to
;; these keys.
(test-expr
 '(def test-workflow
    {:name :test-workflow
     :version 1
     :schemaVersion 2
     :tasks (apply concat
                   [{:type :FORK_JOIN
                     :forkTasks (for [i (range 10)]
                                  [{:name :initialize-key
                                    :inputParameters {:key (str "t" i)}}])}
                    {:type :JOIN}]
                   (for [stage (range 5)]
                     [{:type :FORK_JOIN
                       :forkTasks (for [i (range 10)]
                                    [{:name :append-to-key
                                      :inputParameters {:key (str "t" i)
                                                        :value stage}}])}
                      {:type :JOIN}]))}))

;; Now we send the workflow to Conductor.
(test-expr
 '(con/define-workflow test-workflow))

;; ## Trigger the Workflow

;; The function `con/trigger-workflow` triggers a workflow, given its
;; name, version and parameters. Because both the test and the worker
;; deployment start at the same time, we have no guarantee that the
;; tasks defined by the worker deployment be defined before we trigger
;; the workflow. Having the workflow triggered first will cause it to
;; fail. Therefore, we define it from within a loop, in which when it
;; fails it is retried. Termination of this loop is evidence for
;; things working.
(test-expr
 '(def wf-id (loop []
               (let [id (try
                          (con/trigger-workflow :test-workflow 1 {})
                          (catch Exception e
                            (prn e)
                            (println "Error defining workflow. Waiting...")
                            (Thread/sleep 1000)
                            (println "Retrying...")
                            nil))]
                 (if (nil? id)
                   (recur)
                   ;; else
                   id)))))

;; Now we enter a loop, waiting for the workflow to get out of the
;; `RUNNING` state.
(test-expr
 '(while (= (con/workflow-status wf-id) "RUNNING")
    (Thread/sleep 100)))

;; Finally, we can check that it is `COMPLETED` (and not `FAILED`).
(test-expr
 '(fact
   (con/workflow-status wf-id) => "COMPLETED"))

;; If everything worked correctly, the Redis database should have keys
;; `t0` through `t9`, each containing the value `01234`.
(test-expr
 '(def server1-conn {:pool {} :spec {:uri (str "redis://" redis-host ":" redis-port "/")}})
 '(fact
   (doseq [i (range 10)]
     (car/wcar server1-conn (car/get (str "t" i))) => "01234")))

;; The test pod depends on the Redis database and the Conductor
;; server.
(defn test-module-part-3 [$]
  (-> $
      (lkt/test :integ
                {}
                [::conduct-lk/server ::redis]
                (fn [server redis]
                  (-> (lk/pod :test {})
                      (lku/add-midje-container
                       :test
                       test-deps
                       {:redis-host (:hostname redis)
                        :redis-port (-> redis :ports :redis)}
                       @test-code)
                      (lku/wait-for-service-port redis :redis)
                      (lku/wait-for-service-port server :api)
                      ;; Provide the client library the coordinates to
                      ;; the Conductor server.
                      (lk/update-container :test conduct-lk/add-client-envs server))))))


(deftest integ-test
  (testing "Putting it all together"
    (let [$ (-> (lk/injector)
                (test-module-part-1)
                (test-module-part-2)
                (test-module-part-3)
                (conduct-lk/module)
                (lk/standard-descs))]
      (is (= (lkt/kube-tests $ "conductor") "")))))
