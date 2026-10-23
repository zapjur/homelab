(ns hl.ssh
  (:require [babashka.process :as process]))

(defn arguments [operation host]
  (case operation
    :ssh ["ssh" "--" host]
    :off ["ssh" "-t" "--" host "sudo" "systemctl" "poweroff"]
    :status ["ssh" "-T" "-o" "BatchMode=yes" "-o" "ConnectTimeout=5"
             "-o" "ConnectionAttempts=1" "-o" "StrictHostKeyChecking=yes"
             "-o" "ServerAliveInterval=5" "-o" "ServerAliveCountMax=1"
             "--" host "true"]))

(defn run-process! [args interactive?]
  (:exit @(process/process args
                          (if interactive?
                            {:inherit true :shutdown process/destroy-tree}
                            {:in "" :out :string :err :string
                             :shutdown process/destroy-tree}))))

(defn execute! [operation host]
  (let [exit (run-process! (arguments operation (:ssh-host host)) (not= :status operation))]
    (when (= :status operation)
      (println (if (zero? exit)
                 "SSH is available."
                 "SSH is unavailable; power state is unknown. Check connectivity, authentication, and the trusted host key.")))
    exit))
