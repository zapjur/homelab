(ns hl.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-boot-timeout-seconds 300)

(defn config-path []
  (str (io/file (System/getProperty "user.home") ".config" "hl" "config.edn")))

(defn valid-mac? [value]
  (boolean (and (string? value)
                (re-matches #"(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}|(?:[0-9a-f]{2}-){5}[0-9a-f]{2}" value))))

(defn valid-broadcast? [value]
  ;; Require an IPv4 literal: no DNS lookup during validation. The subnet's
  ;; actual broadcast address depends on its netmask, which we do not know.
  (boolean
   (and (string? value)
        (re-matches #"(?:[0-9]{1,3}\.){3}[0-9]{1,3}" value)
        (let [octets (mapv #(Integer/parseInt %) (str/split value #"\."))]
          (and (every? #(<= 0 % 255) octets)
               (or (= [255 255 255 255] octets)
                   (and (<= 1 (first octets) 223)
                        (not= 127 (first octets)))))))))

(defn validate-host [host]
  (when-not (map? host)
    (throw (ex-info "Expected a host map at [:hosts :default]." {})))
  (doseq [[field valid? description]
          [[:ssh-host #(and (string? %) (re-matches #"[a-zA-Z0-9][a-zA-Z0-9._@:-]*" %))
            "an SSH alias or destination starting with a letter or digit"]
           [:mac valid-mac? "six hexadecimal octets separated by colons or hyphens"]
           [:broadcast valid-broadcast? "an IPv4 broadcast destination"]
           [:wol-port #(and (integer? %) (<= 1 % 65535)) "an integer from 1 to 65535"]
           [:boot-timeout-seconds #(and (integer? %) (pos? %)) "a positive integer number of seconds"]]]
    (when-not (valid? (get host field (when (= field :boot-timeout-seconds)
                                      default-boot-timeout-seconds)))
      (throw (ex-info (str "Invalid or missing " field "; expected " description ".") {:field field}))))
  host)

(defn validate [config]
  (when-not (and (map? config) (map? (:hosts config)) (contains? (:hosts config) :default))
    (throw (ex-info "Configuration requires [:hosts :default]." {})))
  (update config :hosts
          (fn [hosts]
            (into {} (map (fn [[name host]]
                            (when-not (map? host)
                              (throw (ex-info (str "Expected a host map for " name ".") {})))
                            [name (validate-host (merge {:wol-port 9
                                                        :boot-timeout-seconds default-boot-timeout-seconds}
                                                       host))]) hosts)))))

(defn load-config
  ([] (load-config (config-path)))
  ([path]
   (when-not (.isFile (io/file path))
     (throw (ex-info (str "Configuration file not found: " path
                          ". Create it using cli/resources/config.example.edn.") {})))
   (let [config (try
                  (with-open [reader (java.io.PushbackReader. (io/reader path))]
                    (let [value (edn/read {:eof ::eof} reader)]
                      (when-not (= ::eof (edn/read {:eof ::eof} reader))
                        (throw (ex-info "Expected exactly one EDN value." {})))
                      value))
                  (catch Exception e
                    (throw (ex-info (str "Cannot read EDN configuration at " path ": " (.getMessage e)) {} e))))]
     (validate config))))

(defn default-host []
  (get-in (load-config) [:hosts :default]))
