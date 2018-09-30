(ns conductor.core-test
  (:require [clojure.test :refer :all]
            [conductor.core :refer :all]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]))


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
(defn worker-expr [expr]
  (swap! worker-code conj expr))
(defn test-expr [expr]
  (swap! test-code conj expr))

;; Then, within the module, we will take the code from `@worker-code`
;; and `@test-code`.

;; # The Worker Nanoservice

;; The worker uses [Carmine](https://github.com/ptaoussanis/carmine)
;; to interact with Redis. Also, at this point, dependency on
;; `brosenan/conductor` needs to be made explicitly. We hope to change
;; that in future releases.
(def worker-deps '[[org.clojure/clojure "1.9.0"]
                   [com.taoensso/carmine "2.19.0"]
                   [brosenan/conductor "0.1.0"]])



(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))
