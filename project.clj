(defproject brosenan/conductor "0.2.2-SNAPSHOT"
  :description "A lambda-kube recipe for installing Netflix Conductor"
  :url "https://github.com/brosenan/conductor"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [brosenan/lambdakube "0.9.2"]
                 [com.netflix.conductor/conductor-client "1.12.4-rc2"
                  :exclusions [com.google.guava/guava]]
                 [org.slf4j/slf4j-simple "1.7.25"]
                 [org.clojure/data.json "0.2.6"]
                 [clj-http "3.9.1"]
                 [com.taoensso/carmine "2.19.0"]]
  :plugins [[lein-auto "0.1.3"]]
  :main conductor.lk
  :deploy-repositories [["releases" :clojars]])

