;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.core.utils
  (:use
    [clojure.walk]
    [potemkin])
  (:require
    [clojure.tools.logging :as log]
    [flatland.useful.datatypes :as d]
    [clojure.string :as str])
  (:import
    [java.util.concurrent
     ThreadFactory]))

;;;

;; this removes the protocol callsite caching overhead
(defn log-error [e message]
  (log/error e message))

(defn log-warn [e message]
  (log/warn e message))

;;;

(defn num-cores []
  (.availableProcessors (Runtime/getRuntime)))

(defn ^ThreadFactory thread-factory [name-generator]
  (reify ThreadFactory
    (newThread [_ runnable]
      (let [name (name-generator)]
        (doto
          (Thread.
            (fn []
              (try
                (.run ^Runnable runnable)
                (catch Throwable e
                  (log-error e (str "error in thread '" name "'"))))))
          (.setName name)
          (.setDaemon true))))))

;;;

(defn- normalize-useful-names [x]
  (postwalk
    #(cond
       (not (symbol? %))
       %

       (re-find #"record\d+" (name %))
       (->> % name (drop 6) (apply str "record__") symbol)

       :else
       %)
    x))

(defmacro assoc-record [& args]
  (normalize-useful-names (macroexpand `(d/assoc-record ~@args))))

(defmacro make-record [& args]
  (normalize-useful-names (macroexpand `(d/make-record ~@args))))

;;;

(definterface IChannelMarker)

(definterface+ IEnqueue
  (enqueue [_ msg]))

(definterface+ IError
  (error [_ err force?]))

(defprotocol+ IDescribed
  (description [_]))

(defn in-transaction? []
  (clojure.lang.LockingTransaction/isRunning))

(defn result-seq [s]
  (with-meta (seq s) {:lamina/result-seq true}))

(defn retry-exception? [x]
  (= "clojure.lang.LockingTransaction$RetryEx" (.getName ^Class (class x))))

;;;

(defmacro enable-unchecked-math []
  (let [{:keys [major minor]} *clojure-version*]
    (when-not (and (= 1 major) (= 2 minor))
      `(set! *unchecked-math* true))))

;;;

(defn predicate-operator [predicate]
  (with-meta
    (fn [x]
      (if (predicate x)
        x
        :lamina/false))
    {::predicate predicate}))

(defn operator-predicate [f]
  (->> f meta ::predicate))

;;;

;; These functions are adapted from Mark McGranaghan's clj-stacktrace, which
;; is released under the MIT license and therefore amenable to this sort of
;; copy/pastery.

(defn clojure-ns
  "Returns the clojure namespace name implied by the bytecode instance name."
  [instance-name]
  (str/replace
    (or (get (re-find #"([^$]+)\$" instance-name) 1)
      (get (re-find #"(.+)\.[^.]+$" instance-name) 1))
    #"_" "-"))

(def clojure-fn-subs
  [[#"^[^$]*\$"     ""]
   [#"\$.*"         ""]
   [#"@[0-9a-f]*$"  ""]
   [#"__\d+.*"      ""]
   [#"_QMARK_"     "?"]
   [#"_BANG_"      "!"]
   [#"_PLUS_"      "+"]
   [#"_GT_"        ">"]
   [#"_LT_"        "<"]
   [#"_EQ_"        "="]
   [#"_STAR_"      "*"]
   [#"_SLASH_"     "/"]
   [#"_"           "-"]])

(defn clojure-anon-fn?
  "Returns true if the bytecode instance name implies an anonymous inner fn."
  [instance-name]
  (boolean (re-find #"\$.*\$" instance-name)))

(defn clojure-fn
  "Returns the clojure function name implied by the bytecode instance name."
  [instance-name]
  (reduce
   (fn [base-name [pattern sub]] (str/replace base-name pattern sub))
   instance-name
   clojure-fn-subs))

;;; end clj-stacktrace

(defn fn-instance? [x]
  (boolean (re-matches #"^[^$]*\$[^@]*@[0-9a-f]*$" (str x))))

(defn describe-fn [x]
  (cond
    (map? x)
    (str "{ ... }")

    (set? x)
    (str "#{ ... }")

    (vector? x)
    (str "[ ... ]")
    
    (not (fn-instance? x))
    (pr-str x)

    :else
    (let [f (or (operator-predicate x) x)
          s (str f)
          ns (clojure-ns s)
          f (clojure-fn s)
          anon? (clojure-anon-fn? s)]
      (str
        (when-not (= "clojure.core" ns) (str ns "/"))
        f
        (when anon? "[fn]")))))
