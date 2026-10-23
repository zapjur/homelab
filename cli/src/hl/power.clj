(ns hl.power
  (:require [clojure.string :as str]
            [hl.config :as config])
  (:import [java.net DatagramSocket DatagramPacket InetAddress]))

(defn magic-packet [mac]
  (when-not (config/valid-mac? mac)
    (throw (ex-info "Invalid MAC address for Wake-on-LAN." {})))
  (let [octets (map #(Integer/parseInt % 16) (str/split mac #"[:-]"))]
    (byte-array (map unchecked-byte (concat (repeat 6 255) (apply concat (repeat 16 octets)))))))

(defn send-packet! [packet broadcast port]
  (with-open [socket (DatagramSocket.)]
    (.setBroadcast socket true)
    (.send socket (DatagramPacket. packet (alength packet)
                                   (InetAddress/getByName broadcast) port))))

(defn on! [host]
  (config/validate-host host)
  (send-packet! (magic-packet (:mac host)) (:broadcast host) (:wol-port host))
  (println "Wake-on-LAN packet sent; server boot has not been verified.")
  0)
