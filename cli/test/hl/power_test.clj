(ns hl.power-test
  (:require [clojure.test :refer [deftest is]]
            [hl.config-test :refer [host]]
            [hl.power :as power]))

(deftest packet-format
  (let [packet (mapv #(bit-and % 255) (power/magic-packet (:mac host)))
        mac [170 187 204 221 238 255]]
    (is (= 102 (count packet)))
    (is (= (repeat 6 255) (take 6 packet)))
    (is (= (repeat 16 mac) (partition 6 (drop 6 packet))))
    (is (= packet (mapv #(bit-and % 255) (power/magic-packet "aa-bb-cc-dd-ee-ff")))))
  (is (thrown? clojure.lang.ExceptionInfo (power/magic-packet "invalid"))))

(deftest send-boundary
  (let [sent (atom nil)]
    (with-redefs [power/send-packet! (fn [packet address port]
                                      (reset! sent [(alength packet) address port]))]
      (is (= 0 (binding [*out* (java.io.StringWriter.)] (power/on! host))))
      (is (= [102 (:broadcast host) 9] @sent)))))
