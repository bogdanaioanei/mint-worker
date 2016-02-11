(ns org.zalando.stups.mint.worker.job.sync-client-test
  (:require [clojure.test :refer :all]
            [clj-time.core :as time]
            [org.zalando.stups.mint.worker.job.common :as c]
            [org.zalando.stups.mint.worker.test-helpers :refer [test-tokens
                                                                test-config
                                                                call-info
                                                                sequentially
                                                                throwing
                                                                third
                                                                track]]
            [org.zalando.stups.mint.worker.job.sync-client :refer [sync-client]]
            [org.zalando.stups.mint.worker.external.services :as services]
            [org.zalando.stups.mint.worker.external.storage :as storage]
            [org.zalando.stups.mint.worker.external.s3 :as s3])
  (:import (com.amazonaws.services.s3.model PutObjectResult)))

(def test-app
  {:id "kio"
   :client_id "kio-client"
   :username "stups_kio"
   :s3_buckets ["bucket-one" "bucket-two"]})

(def test-response
  {:client_id "kio-client2"
   :txid "123"
   :client_secret "lolz"})

; it should skip when last rotation was <= 1 month ago
(deftest should-skip-when-rotation-was-recently
  (let [recently (c/format-date-time (time/minus (time/now)
                                                 (time/weeks 3)))
        test-app (assoc test-app :last_client_rotation recently)
        calls (atom {})]
    (with-redefs [services/generate-new-client (track calls :gen)]
      (sync-client test-app
                   test-config
                   test-tokens)
      (is (= 0 (count (:gen @calls)))))))

; it should not skip when last rotation was > 1 month ago
(deftest should-not-skip-when-rotation-was-not-recently
  (let [past (c/format-date-time (time/minus (time/now)
                                             (time/weeks 5)))
        test-app (assoc test-app :last_client_rotation past)
        calls (atom {})]
    (with-redefs [services/generate-new-client (comp
                                                 (constantly test-response)
                                                 (track calls :generate))
                  s3/save-client (constantly (PutObjectResult.))
                  services/commit-client (track calls :commit)
                  storage/update-status (track calls :update)]
      (sync-client test-app
                   test-config
                   test-tokens)
      ; 2 => one for primary, one for secondary
      (is (= 2 (count (:generate @calls))))
      (is (= (:service-user-url test-config)
             (-> (:generate @calls)
                 (call-info 0)
                 :url)))
      (is (= (:shadow-service-user-url test-config)
             (-> (:generate @calls)
                 (call-info 1)
                 :url)))
      ; call to secondary must contain client_id, client_secret and txid
      (is (= (:client_id test-response)
             (-> (:generate @calls)
                 (call-info 1)
                 :args
                 (nth 1)
                 :client_id)))
      (is (= (:client_secret test-response)
             (-> (:generate @calls)
                 (call-info 1)
                 :args
                 (nth 1)
                 :client_secret)))
      (is (= (:txid test-response)
             (-> (:generate @calls)
                 (call-info 1)
                 :args
                 (nth 1)
                 :txid)))
      (is (= 2 (count (:commit @calls))))
      (is (= (:service-user-url test-config)
             (-> (:commit @calls)
                 (call-info 0)
                 :url)))
      (is (= (:shadow-service-user-url test-config)
             (-> (:commit @calls)
                 (call-info 1)
                 :url)))
      (is (= 1 (count (:update @calls))))
      (let [args (first (:commit @calls))]
        ; signature: storage-url username transaction-id
        (is (= (second args)
               (:username test-app)))
        (is (= (third args)
               (:txid test-response)))))))

; it should not skip when last rotation was never
(deftest should-not-skip-when-never-rotated
  (let [calls (atom {})]
    (with-redefs [services/generate-new-client (constantly test-response)
                  s3/save-client (constantly (PutObjectResult.))
                  services/commit-client (track calls :commit)
                  storage/update-status (track calls :update)]
      (sync-client test-app
                   test-config
                   test-tokens)
      ; 2 => one for primary, one for secondary
      (is (= 2 (count (:commit @calls))))
      (is (= 1 (count (:update @calls)))))))

; it should commit password only after successful write to all buckets
(deftest should-not-commit-if-s3-write-failed
  (let [calls (atom {})]
    (with-redefs [services/generate-new-client (constantly test-response)
                  s3/save-client (sequentially (PutObjectResult.) (s3/S3Exception "bad s3" {}))
                  services/commit-client (track calls :commit)
                  storage/update-status (track calls :update)]
      (try
        (sync-client test-app
                     test-config
                     test-tokens)
        (is false)
        (catch Exception error
          (is (:type (ex-data error))
              "S3Exception")))
      (is (= 0 (count (:commit @calls))))
      (is (= 0 (count (:update @calls)))))))

; it should not handle errors
(deftest do-not-handle-errors
  (with-redefs [services/generate-new-client (throwing "ups")]
    (is (thrown? Exception (sync-client test-app
                                        test-config
                                        test-tokens)))))
