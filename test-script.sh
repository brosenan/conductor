#!/bin/sh

export CONDUCTOR_API_URL=http://127.0.0.1:8001/api/v1/namespaces/default/services/conductor-server:api/proxy/api/
lein repl <<EOF
(use 'netflix-conductor.core)
(define-task [{:name :foo :inputKeys [] :outputKeys []} {:name :bar :inputKeys [] :outputKeys []}])
(define-workflow {:name :my-lovely-workflow :version 7 :tasks [{:name :f1 :taskReferenceName :f1 :type :FORK_JOIN :forkTasks [[{:name :foo :taskReferenceName :foo}] [{:name :bar :taskReferenceName :bar}]]}]})
(defn foo [{:keys [x]}] {:y (inc x)})
(run-workers [#'foo] 2)
(Thread/sleep 1000
EOF
