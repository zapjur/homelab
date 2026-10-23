(ns hl.core
  (:require [hl.config :as config]
            [hl.power :as power]
            [hl.ssh :as ssh]))

(def version "0.1.0")

(def usage
  "Usage: hl <command>

Commands:
  on          Send a Wake-on-LAN packet (does not wait for boot)
  off         Request graceful shutdown over SSH with interactive sudo
  ssh         Open an interactive OpenSSH session
  status      Check non-interactive SSH availability (not power state)
  help        Show this help (--help is equivalent)
  --version   Print the CLI version

Configuration: ~/.config/hl/config.edn
SSH authentication and keys are managed by OpenSSH and ~/.ssh/config.")

(defn run [args]
  (try
    (cond
      (empty? args) (do (println usage) 0)
      (not= 1 (count args)) (do (binding [*out* *err*] (println "Expected exactly one command.\n" usage)) 2)
      :else
      (case (first args)
        ("help" "--help") (do (println usage) 0)
        "--version" (do (println version) 0)
        "on" (power/on! (config/default-host))
        "off" (ssh/execute! :off (config/default-host))
        "ssh" (ssh/execute! :ssh (config/default-host))
        "status" (ssh/execute! :status (config/default-host))
        (do (binding [*out* *err*] (println "Unknown command:" (first args)) (println usage)) 2)))
    (catch java.io.IOException e
      (binding [*out* *err*] (println "hl:" (.getMessage e)))
      1)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println "hl:" (.getMessage e)))
      1)))
