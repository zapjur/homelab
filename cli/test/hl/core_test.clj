(ns hl.core-test
  (:require [clojure.test :refer [deftest is]]
            [hl.core :as core]
            [hl.config :as config]
            [hl.config-test :refer [host]]
            [hl.power :as power]
            [hl.ssh :as ssh]))

(defn invoke [args]
  (let [out (java.io.StringWriter.) err (java.io.StringWriter.)]
    {:exit (binding [*out* out *err* err] (core/run args))
     :out (str out) :err (str err)}))

(deftest informational-commands
  (with-redefs [config/default-host #(throw (Exception. "Must not load configuration"))]
    (doseq [args [[] ["help"] ["--help"]]]
      (is (= 0 (:exit (invoke args))))
      (is (re-find #"Usage: hl" (:out (invoke args)))))
    (is (= (str core/version "\n") (:out (invoke ["--version"]))))
    (doseq [args [["unknown"] ["ssh" "extra"] ["--help" "extra"]]]
      (is (= 2 (:exit (invoke args))))) ))

(deftest dispatch
  (with-redefs [config/default-host (constantly host)
                power/on! (fn [h] (is (= host h)) 0)
                ssh/execute! (fn [operation h]
                               (is (= host h))
                               ({:ssh 23 :off 42 :status 255} operation))]
    (doseq [[command exit] [["on" 0] ["ssh" 23] ["off" 42] ["status" 255]]]
      (is (= exit (:exit (invoke [command])))))))

(deftest errors
  (doseq [command ["on" "off" "ssh" "status"]]
    (with-redefs [config/default-host #(throw (ex-info "Configuration file not found" {}))]
      (is (= 1 (:exit (invoke [command]))))
      (is (re-find #"Configuration file not found" (:err (invoke [command]))))))
  (with-redefs [config/default-host (constantly host)
                power/send-packet! (fn [& _] (throw (java.net.SocketException. "Network unavailable")))
                ssh/run-process! (fn [& _] (throw (java.io.IOException. "Cannot execute ssh")))]
    (is (= 1 (:exit (invoke ["on"]))))
    (is (= 1 (:exit (invoke ["ssh"]))))))
