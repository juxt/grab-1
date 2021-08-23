;; Copyright © 2021, JUXT LTD.

(ns juxt.grab.schema-test
  (:require
   [clojure.test :refer [deftest is are testing]]
   [juxt.grab.alpha.schema :refer [compile-schema] :as s]
   [juxt.grab.alpha.parser :refer [parse]]))

(defn expected-errors [{::s/keys [errors]} regexes]
  (assert errors)
  (is
   (= (count errors) (count regexes))
   "Count of errors doesn't equal expected count")
  (doall
   (map
    (fn [error regex]
      (is (:error error))
      (when regex
        (is (re-matches regex (:error error)))))
    errors regexes)))

;; https://spec.graphql.org/June2018/#sec-Schema

;; "All types within a GraphQL schema must have unique names."

(deftest duplicate-type-names-test
  (-> "type Query { name: String } type Query { name: String }"
      parse
      compile-schema
      (expected-errors [#"Duplicates found"])))

;; "No provided type may have a name which conflicts with any built in types
;; (including Scalar and Introspection types)."

(deftest type-conflicts-test
  (-> "type String { length: Int }"
      parse
      compile-schema
      (expected-errors [#"Conflicts with built-in types"])))

;; "All directives within a GraphQL schema must have unique names."

(deftest directive-conflicts-test
  (-> "directive @foo on FIELD directive @foo on OBJECT"
      parse
      compile-schema
      (expected-errors [#"Duplicate directives found"])))

;; "All types and directives defined within a schema must not have a name which
;; begins with '__' (two underscores), as this is used exclusively by GraphQL’s
;; introspection system."

(deftest reserved-names-test
  (-> "type __foo { length: Int }"
      parse
      compile-schema
      (expected-errors [#"A type or directive cannot be defined with a name that begins with two underscores"])))
