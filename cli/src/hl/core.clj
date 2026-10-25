(ns hl.core
  (:require [hl.config :as config]
            [hl.power :as power]
            [hl.ssh :as ssh]))

(def version "0.1.0")

(def usage
  (str "Usage: hl <command> [options]

Commands:
  on          Send a Wake-on-LAN packet and wait for its TCP port
  --no-wait   Return immediately after sending the packet
  off         Request graceful shutdown over SSH with interactive sudo
  ssh         Open an interactive OpenSSH session
  status      Check the SSH TCP port without authenticating (not power state)
  --tailscale Use the optional Tailscale SSH alias with ssh/off/status
  help        Show this help (--help is equivalent)
  --version   Print the CLI version

Boot wait: :boot-timeout-seconds in host configuration (default: " config/default-boot-timeout-seconds " seconds).
Configuration: ~/.config/hl/config.edn
SSH authentication and keys are managed by OpenSSH and ~/.ssh/config."))

(defn valid-arguments? [args]
  (contains? #{[] ["help"] ["--help"] ["--version"]
               ["on"] ["on" "--no-wait"]
               ["ssh"] ["ssh" "--tailscale"]
               ["off"] ["off" "--tailscale"]
               ["status"] ["status" "--tailscale"]}
             (vec args)))

(defn run [args]
  (try
    (cond
      (empty? args) (do (println usage) 0)
      (not (valid-arguments? args))
      (do (binding [*out* *err*]
            (println "Invalid or unknown arguments:" (pr-str (vec args)))
            (println usage))
          2)
      :else
      (let [tailscale? (= "--tailscale" (second args))]
        (case (first args)
          ("help" "--help") (do (println usage) 0)
          "--version" (do (println version) 0)
          "on" (let [host (config/default-host)]
                 (power/on! host)
                 (if (= "--no-wait" (second args))
                   0
                   (ssh/wait-until-available! host)))
          "off" (ssh/execute! :off (config/default-host) tailscale?)
          "ssh" (ssh/execute! :ssh (config/default-host) tailscale?)
          "status" (ssh/execute! :status (config/default-host) tailscale?))))
    (catch java.io.IOException e
      (binding [*out* *err*] (println "hl:" (.getMessage e)))
      1)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println "hl:" (.getMessage e)))
      1)))
