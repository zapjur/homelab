(ns hl.ssh-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.process :as process]
            [hl.config-test :refer [host]]
            [hl.ssh :as ssh]))

(deftest command-arguments
  (is (= ["ssh" "--" "example-server"] (ssh/arguments :ssh (:ssh-host host))))
  (is (= ["ssh" "-t" "--" "example-server" "sudo" "systemctl" "poweroff"]
         (ssh/arguments :off (:ssh-host host))))
  (let [args (ssh/arguments :status (:ssh-host host))]
    (doseq [option ["BatchMode=yes" "ConnectTimeout=5" "ConnectionAttempts=1"
                    "StrictHostKeyChecking=yes" "ServerAliveInterval=5" "ServerAliveCountMax=1"]]
      (is (some #{option} args)))
    (is (= ["--" "example-server" "true"] (take-last 3 args)))
    (is (some #{"-T"} args))))

(deftest process-results
  (doseq [exit [0 1 42 130 255]
          interactive? [true false]]
    (with-redefs [process/process
                  (fn [args options]
                    (is (= ["ssh" "--" "example-server"] args))
                    (if interactive?
                      (is (true? (:inherit options)))
                      (do (is (= "" (:in options)))
                          (is (= :string (:err options)))))
                    (is (= process/destroy-tree (:shutdown options)))
                    (delay {:exit exit}))]
      (is (= exit (ssh/run-process! ["ssh" "--" "example-server"] interactive?)))))
  (doseq [operation [:ssh :off :status] exit [0 42 255]]
    (with-redefs [ssh/run-process! (fn [args interactive?]
                                    (is (= (ssh/arguments operation (:ssh-host host)) args))
                                    (is (= (not= :status operation) interactive?))
                                    exit)]
      (let [output (java.io.StringWriter.)]
        (is (= exit (binding [*out* output] (ssh/execute! operation host))))
        (when (= :status operation)
          (is (re-find (if (zero? exit) #"SSH is available" #"power state is unknown")
                       (str output))))))))
