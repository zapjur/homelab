(ns hl.ssh
  (:require [babashka.process :as process]
            [hl.config :as config]
            [clojure.string :as str]))

(defn arguments [operation host]
  (case operation
    :ssh ["ssh" "--" host]
    :off ["ssh" "-t" "--" host "sudo" "systemctl" "poweroff"]
    :config ["ssh" "-G" "--" host]))

(defn run-process! [args interactive?]
  (:exit @(process/process args
                          (if interactive?
                            {:inherit true :shutdown process/destroy-tree}
                            {:in "" :out :string :err :string
                             :shutdown process/destroy-tree}))))

(defn capture-process! [args timeout-ms]
  (let [child (process/process args {:in "" :out :string :err :string
                                     :shutdown process/destroy-tree})
        result (deref child timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do (process/destroy-tree child)
          @child
          {:exit 1 :err "Connection probe timed out."})
      result)))

(defn parse-endpoint [output]
  (let [settings (into {} (keep (fn [line]
                                (let [[key value] (str/split line #"\s+" 2)]
                                  (when value [key value])))
                              (str/split-lines output)))
        hostname (get settings "hostname")
        port (try (Long/parseLong (get settings "port" ""))
                  (catch NumberFormatException _ nil))]
    (when-not (and (seq hostname) port (<= 1 port 65535))
      (throw (ex-info "OpenSSH did not provide a valid hostname and port." {})))
    (when (some #(let [value (get settings %)]
                  (and value (not= "none" value)))
                ["proxycommand" "proxyjump"])
      (throw (ex-info "TCP status requires direct access to the SSH destination; ProxyCommand/ProxyJump is not supported." {})))
    (cond-> {:hostname hostname :port port}
      (#{"inet" "inet6"} (get settings "addressfamily"))
      (assoc :address-family (get settings "addressfamily")))))

(defn endpoint! [host]
  ;; -G evaluates local OpenSSH configuration without connecting or authenticating.
  (let [{:keys [exit out err]} (capture-process! (arguments :config (:ssh-host host)) 5000)]
    (when-not (zero? exit)
      (throw (ex-info (str "Cannot resolve the SSH destination using ssh -G: "
                           (if (str/blank? err) "check ~/.ssh/config" (str/trim err))) {})))
    (parse-endpoint out)))

(defn probe-arguments [{:keys [hostname port address-family]} timeout-ms]
  (into ["nc" "-z" "-v" "-w" (str (max 1 (long (Math/ceil (/ timeout-ms 1000.0)))))]
        (concat (case address-family "inet" ["-4"] "inet6" ["-6"] [])
                ["--" hostname (str port)])))

(defn tcp-probe! [endpoint timeout-ms]
  ;; Use the OS resolver and address fallback, like native OpenSSH, rather than
  ;; Java's single-address InetSocketAddress resolution.
  (let [{:keys [exit err]} (capture-process! (probe-arguments endpoint timeout-ms) timeout-ms)]
    {:available? (zero? exit)
     :error (when-not (zero? exit)
              (if (str/blank? err) "TCP connection failed." (str/trim err)))}))

(defn endpoint-label [{:keys [hostname port]}]
  (str hostname ":" port))

(defn monotonic-ms [] (quot (System/nanoTime) 1000000))
(defn pause! [milliseconds] (Thread/sleep milliseconds))

(defn wait-until-available! [host]
  (let [endpoint (endpoint! host)
        timeout-seconds (get host :boot-timeout-seconds config/default-boot-timeout-seconds)
        deadline (+' (monotonic-ms) (*' 1000 timeout-seconds))]
    (println (str "Waiting up to " timeout-seconds " seconds for " (endpoint-label endpoint) " (TCP; no authentication)..."))
    (loop [last-error nil]
      (let [remaining (- deadline (monotonic-ms))]
        (cond
          (not (pos? remaining))
          (do (binding [*out* *err*]
                (println (str "Timed out waiting for " (endpoint-label endpoint) "; power state is unknown."))
                (when last-error (println last-error)))
              1)

          :else
          (let [{:keys [available? error]} (tcp-probe! endpoint (min 5000 remaining))]
            (if available?
              (do (println "SSH TCP port is reachable (authentication and service health not checked).") 0)
              (do (pause! (max 0 (min 2000 (- deadline (monotonic-ms)))))
                  (recur error)))))))))

(defn execute! [operation host]
  (if (= :status operation)
    (let [endpoint (endpoint! host)
          {:keys [available? error]} (tcp-probe! endpoint 5000)]
      (println "TCP destination:" (endpoint-label endpoint))
      (println (if available?
                 "SSH TCP port is reachable (authentication and service health not checked)."
                 "SSH TCP port is unreachable; power state is unknown."))
      (when error (binding [*out* *err*] (println error)))
      (if available? 0 1))
    (run-process! (arguments operation (:ssh-host host)) true)))
