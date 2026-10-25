(ns hl.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [hl.config :as config]))

(def host {:ssh-host "example-server" :mac "AA:BB:CC:DD:EE:FF"
           :broadcast "192.0.2.255" :wol-port 9 :boot-timeout-seconds 300})
(def valid-config {:hosts {:default host}})

(deftest validation
  (is (= valid-config (config/validate valid-config)))
  (is (= 300 (get-in (config/validate {:hosts {:default (dissoc host :boot-timeout-seconds)}})
                     [:hosts :default :boot-timeout-seconds])))
  (is (= 600 (get-in (config/validate {:hosts {:default (assoc host :boot-timeout-seconds 600)}})
                     [:hosts :default :boot-timeout-seconds])))
  (is (= 9 (get-in (config/validate {:hosts {:default (dissoc host :wol-port)}})
                   [:hosts :default :wol-port])))
  (doseq [field [:ssh-host :mac :broadcast]]
    (testing (str "missing " field)
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/validate {:hosts {:default (dissoc host field)}})))))
  (doseq [[field values] {:ssh-host [nil 1 "" "-oProxyCommand=bad" "host;bad" "host\ncommand"]
                         :mac [nil 1 "GG:BB:CC:DD:EE:FF" "AA-BB:CC:DD:EE:FF"]
                         :broadcast [nil 1 "example.com" "256.1.2.3" "0.0.0.0" "224.0.0.1" "127.0.0.1"]
                         :wol-port [nil "9" 0 -1 65536 1.5]
                         :boot-timeout-seconds [nil "300" 0 -1 1.5 true]}
          value values]
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate {:hosts {:default (assoc host field value)}}))))
  (doseq [value [nil {} {:hosts []} {:hosts {:default "bad"}}]]
    (is (thrown? clojure.lang.ExceptionInfo (config/validate value)))))

(deftest file-loading
  (let [file (java.io.File/createTempFile "hl-config-" ".edn")]
    (try
      (spit file (pr-str valid-config))
      (is (= valid-config (config/load-config (str file))))
      (doseq [content ["{" "{:hosts #unknown 1}" "{} {}" ""]]
        (spit file content)
        (is (thrown? clojure.lang.ExceptionInfo (config/load-config (str file)))))
      (finally (.delete file)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                          (config/load-config (str file))))))

(deftest optional-tailscale-config
  (is (= "tailnet-server" (:tailscale-ssh-host (config/validate-host
                                             (assoc host :tailscale-ssh-host "tailnet-server")))))
  (doseq [invalid [nil "" "-bad" "bad name" "bad;name" 42]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/validate-host (assoc host :tailscale-ssh-host invalid))))))
