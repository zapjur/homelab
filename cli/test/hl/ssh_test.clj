(ns hl.ssh-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.process :as process]
            [hl.config-test :refer [host]]
            [hl.ssh :as ssh]))

(deftest command-arguments
  (is (= ["ssh" "--" "example-server"] (ssh/arguments :ssh (:ssh-host host))))
  (is (= ["ssh" "-t" "--" "example-server" "sudo" "systemctl" "poweroff"]
         (ssh/arguments :off (:ssh-host host))))
  (is (= ["ssh" "-G" "--" "example-server"]
         (ssh/arguments :config (:ssh-host host)))))

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
  (doseq [operation [:ssh :off] exit [0 42 255]]
    (with-redefs [ssh/run-process! (fn [args interactive?]
                                    (is (= (ssh/arguments operation (:ssh-host host)) args))
                                    (is (= true interactive?))
                                    exit)]
      (let [output (java.io.StringWriter.)]
        (is (= exit (binding [*out* output] (ssh/execute! operation host))))))))

(def endpoint {:hostname "server.example.invalid" :port 2222})

(deftest endpoint-resolution
  (is (= endpoint (ssh/parse-endpoint "hostname server.example.invalid\nport 2222\nuser example\n")))
  (is (= {:hostname "::1" :port 22} (ssh/parse-endpoint "hostname ::1\nport 22\n")))
  (doseq [output ["" "hostname host\nport zero" "hostname host\nport 0"
                  "hostname host\nport 65536" "hostname host\nport 22\nproxyjump jump"
                  "hostname host\nport 22\nproxycommand nc %h %p"]]
    (is (thrown? clojure.lang.ExceptionInfo (ssh/parse-endpoint output))))
  (doseq [exit [0 255]]
    (with-redefs [process/process (fn [args options]
                                  (is (= ["ssh" "-G" "--" "example-server"] args))
                                  (is (= "" (:in options)))
                                  (deliver (promise) {:exit exit :out "hostname server.example.invalid\nport 2222\n"}))]
      (if (zero? exit)
        (is (= endpoint (ssh/endpoint! host)))
        (is (thrown? clojure.lang.ExceptionInfo (ssh/endpoint! host)))))))

(deftest native-tcp-probe
  (is (= ["nc" "-z" "-v" "-w" "5" "--" "server.example.invalid" "2222"]
         (ssh/probe-arguments endpoint 5000)))
  (is (= ["nc" "-z" "-v" "-w" "1" "-6" "--" "server.example.invalid" "2222"]
         (ssh/probe-arguments (assoc endpoint :address-family "inet6") 100)))
  (is (= "-4" (nth (ssh/probe-arguments (assoc endpoint :address-family "inet") 5000) 5)))
  (is (= (assoc endpoint :address-family "inet6")
         (ssh/parse-endpoint "hostname server.example.invalid\nport 2222\naddressfamily inet6\n")))
  (doseq [[exit error] [[0 ""] [1 "Connection refused"] [1 "Name not found"]]]
    (with-redefs [ssh/capture-process! (fn [args timeout-ms]
                                        (is (= (ssh/probe-arguments endpoint 5000) args))
                                        (is (= 5000 timeout-ms))
                                        {:exit exit :err error})]
      (is (= {:available? (zero? exit) :error (when-not (zero? exit) error)}
             (ssh/tcp-probe! endpoint 5000))))))

(deftest bounded-native-process
  (let [child (promise) killed? (atom false)]
    (with-redefs [process/process (fn [_ options]
                                  (is (= "" (:in options)))
                                  (is (= :string (:err options)))
                                  child)
                  process/destroy-tree (fn [p]
                                         (reset! killed? true)
                                         (deliver p {:exit 143}))]
      (is (= {:exit 1 :err "Connection probe timed out."}
             (ssh/capture-process! ["unused"] 1)))
      (is @killed?))))

(deftest unauthenticated-status
  (doseq [available? [true false]]
    (with-redefs [ssh/endpoint! (fn [h] (is (= host h)) endpoint)
                  ssh/tcp-probe! (fn [destination timeout-ms]
                                       (is (= endpoint destination))
                                       (is (= 5000 timeout-ms))
                                       {:available? available? :error (when-not available? "Connection refused")})
                  ssh/run-process! (fn [& _] (throw (Exception. "Must not authenticate")))]
      (let [output (java.io.StringWriter.) errors (java.io.StringWriter.)]
        (is (= (if available? 0 1)
               (binding [*out* output *err* errors] (ssh/execute! :status host))))
        (is (re-find #"server.example.invalid:2222" (str output)))
        (is (re-find (if available? #"TCP port is reachable" #"power state is unknown") (str output)))
        (when-not available? (is (re-find #"Connection refused" (str errors))))))))

(deftest wait-for-ssh
  (doseq [timeout-seconds [nil 1 8 600]
          succeed-at [1 3 nil]]
    (let [wait-host (cond-> (dissoc host :boot-timeout-seconds)
                      timeout-seconds (assoc :boot-timeout-seconds timeout-seconds))
          budget-ms (* 1000 (or timeout-seconds 300))
          ;; An 8-second budget allows two full probes; shorter budgets may
          ;; expire before a requested third success.
          success? (and succeed-at (or (= 1 succeed-at) (> budget-ms 14000)))
          clock (atom 0) attempts (atom 0)
          output (java.io.StringWriter.) errors (java.io.StringWriter.)]
      (with-redefs [ssh/monotonic-ms #(deref clock)
                    ssh/pause! #(swap! clock + %)
                    ssh/endpoint! (fn [h] (is (= wait-host h)) endpoint)
                    ssh/tcp-probe!
                    (fn [destination timeout-ms]
                      (is (= endpoint destination))
                      (is (<= 1 timeout-ms 5000))
                      (is (<= (+ @clock timeout-ms) budget-ms))
                      (swap! clock + timeout-ms)
                      {:available? (= succeed-at (swap! attempts inc)) :error "Connection refused"})]
        (is (= (if success? 0 1)
               (binding [*out* output *err* errors]
                 (ssh/wait-until-available! wait-host))))
        (is (re-find (re-pattern (str "Waiting up to " (or timeout-seconds 300) " seconds")) (str output)))
        (if success?
          (do (is (= succeed-at @attempts))
              (is (re-find #"TCP port is reachable" (str output))))
          (do (is (= budget-ms @clock))
              (is (re-find #"Timed out.*power state is unknown" (str errors)))
              (is (re-find #"Connection refused" (str errors)))))))))

(deftest select-transport
  (is (= host (ssh/selected-host host false)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing :tailscale-ssh-host"
                        (ssh/selected-host host true)))
  (let [with-tail (assoc host :tailscale-ssh-host "example-tailnet")]
    (is (= "example-tailnet" (:ssh-host (ssh/selected-host with-tail true))))
    (doseq [operation [:ssh :off]]
      (with-redefs [ssh/run-process! (fn [args interactive?]
                                      (is (= (ssh/arguments operation "example-tailnet") args))
                                      (is interactive?)
                                      0)]
        (is (= 0 (ssh/execute! operation with-tail true)))))
    (with-redefs [ssh/endpoint! (fn [h]
                                 (is (= "example-tailnet" (:ssh-host h)))
                                 endpoint)
                  ssh/tcp-probe! (fn [_ _] {:available? true})]
      (is (= 0 (binding [*out* (java.io.StringWriter.)]
                 (ssh/execute! :status with-tail true)))))))
