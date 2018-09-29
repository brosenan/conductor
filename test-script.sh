#!/bin/sh

export CONDUCTOR_API_URL=http://127.0.0.1:8001/api/v1/namespaces/default/services/conductor-server:api/proxy/api/
lein repl <<EOF
(use 'conductor.core)
(defn foo {:outputKeys [:y]} [{:keys [x] :as inp}] (prn inp) {:y (inc x)})
(defn bar {:outputKeys [:z]} [{:keys [x] :as inp}] (prn inp) {:z (dec x)})
(define-tasks [#'foo #'bar])
(run-workers [#'foo #'bar] 2)
(define-workflow {:name :my-lovely-workflow :schemaVersion 2 :version 1 :tasks [{:type :FORK_JOIN :forkTasks [[{:name :foo :inputParameters {:x "\${workflow.input.a}"} :taskReferenceName :foo}] [{:name :bar :inputParameters {:x "\${workflow.input.b}"}}]]} {:type :JOIN}] :outputParameters {:q "\${foo.output.y}" :p "\${task0_1_0.output.z}"}})
(trigger-workflow :my-lovely-workflow 1 {:a 5 :b 8})
(Thread/sleep 10000)
EOF

# curl -X POST --header 'Content-Type: application/json' --header 'Accept: text/plain' -d '{"a": 1, "b": 2}' 'http://localhost:8080/api/workflow/my-lovely-workflow?version=1&correlationId=foo'
# curl -X DELETE --header 'Accept: application/json' 'http://localhost:8001/api/v1/namespaces/default/services/conductor-server:api/proxy/api/metadata/workflow/my-lovely-workflow/1'
