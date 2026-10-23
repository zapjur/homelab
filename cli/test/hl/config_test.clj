(ns hl.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [hl.config :as config]))

(def host {:ssh-host "example-server" :mac "AA:BB:CC:DD:EE:FF"
           :broadcast "192.0.2.255" :wol-port 9})
(def valid-config {:hosts {:default host}})

(deftest validation
  (is (= valid-config (config/validate valid-config)))
  (is (= 9 (get-in (config/validate {:hosts {:default (dissoc host :wol-port)}})
                   [:hosts :default :wol-port])))
  (doseq [field [:ssh-host :mac :broadcast]]
    (testing (str "missing " field)
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/validate {:hosts {:default (dissoc host field)}})))))
  (doseq [[field values] {:ssh-host [nil 1 "" "-oProxyCommand=bad" "host;bad" "host\ncommand"]
                         :mac [nil 1 "GG:BB:CC:DD:EE:FF" "AA-BB:CC:DD:EE:FF"]
                         :broadcast [nil 1 "example.com" "256.1.2.3" "0.0.0.0" "224.0.0.1" "127.0.0.1"]
                         :wol-port [nil "9" 0 -1 65536 1.5]}
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
