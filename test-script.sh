#!/bin/sh

export CONDUCTOR_API_URL=http://127.0.0.1:8001/api/v1/namespaces/default/services/conductor-server:api/proxy/api/
lein repl <<EOF
(use 'netflix-conductor.core)
(define-task [{:name :foo :inputKeys [:x] :outputKeys [:y]} {:name :bar :inputKeys [:x] :outputKeys [:y]}])
(defn foo [{:keys [x] :as inp}] (prn inp) {:y (inc x)})
(defn bar [{:keys [x] :as inp}] (prn inp) {:y (dec x)})
(run-workers [#'foo #'bar] 2)
(define-workflow {:name :my-lovely-workflow :schemaVersion 2 :version 1 :tasks [{:type :FORK_JOIN :forkTasks [[{:name :foo :inputParameters {:x "\${workflow.input.a}" :foo {:a 1}}}] [{:name :bar :inputParameters {:x "\${workflow.input.b}"}}]]} {:type :JOIN}] :outputParameters {:q "\${task_0_0_0.output.y}" :p "\${task_0_1_0.output.y}"}})
(trigger-workflow :my-lovely-workflow 1 {:a 5 :b 8})
(Thread/sleep 10000)
EOF

# curl -X POST --header 'Content-Type: application/json' --header 'Accept: text/plain' -d '{"a": 1, "b": 2}' 'http://localhost:8080/api/workflow/my-lovely-workflow?version=1&correlationId=foo'
# curl -X DELETE --header 'Accept: application/json' 'http://localhost:8001/api/v1/namespaces/default/services/conductor-server:api/proxy/api/metadata/workflow/my-lovely-workflow/1'
