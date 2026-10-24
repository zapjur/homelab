(ns hl.test-runner
  (:require [clojure.test :as test]
            hl.config-test hl.power-test hl.ssh-test hl.core-test))

(defn -main []
  (let [{:keys [fail error]} (test/run-tests 'hl.config-test 'hl.power-test
                                          'hl.ssh-test 'hl.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
