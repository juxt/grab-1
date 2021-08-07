;; Copyright © 2021, JUXT LTD.
(ns juxt.grab.schema-test
  (:require
   [clojure.test :refer [deftest is]]
   [juxt.reap.alpha.graphql :as reap]
   [juxt.grab.alpha.parser :as parser]
   [juxt.grab.alpha.schema :as schema]
   [clojure.java.io :as io]))

(def example-56 "
type Person @crux(query: \"{:find [e] :where [[?e :name][?e :picture ?p][?p :size $size]]}\") {
  name: String
  picture(size: Int): Url}")

(deftest parse-example-56-test
  (is (= [#::reap{:type "ObjectTypeDefinition",
                  :name "Person",
                  :directives
                  {"crux"
                   {"query"
                    "{:find [e] :where [[?e :name][?e :picture ?p][?p :size $size]]}"}},
                  :fields
                  [#::reap{:name "name", :type "String"}
                   #::reap{:name "picture", :args [#::reap{:name "size", :type "Int"}], :type "Url"}]}]
         (parser/parse-graphql example-56))))

(deftest schema-test
  (let [schema (-> (slurp (io/resource "juxt/grab/test-schema.graphql"))
                   parser/parse-graphql
                   schema/parse-tree->schema)]
    (is (= "Query" (get-in schema [::schema/root-operation-type-names :query])))))

(deftest get-root-query-type-test
  (let [type (-> (slurp (io/resource "juxt/grab/test-schema.graphql"))
                 parser/parse-graphql
                 schema/parse-tree->schema
                 schema/get-root-query-type)]
    (is (= "Query" (::schema/name type)))
    (is (= :object (::schema/kind type)))
    (is (= 1 (count (::schema/fields type))))
    (let [[_ user-field] (first (::schema/fields type))]
      (is (= "user" (::schema/name user-field)))
      (is (= "Person" (::schema/type user-field))))))

;; TODO: Write schema tests

#_(-> "type Root { users: [User] } type User { name: String }"
          parser/parse-graphql
          schema/parse-tree->schema)

#_(-> "type Root { user: [[User]!]! } type User { name: String }"
          parser/parse-graphql
          schema/parse-tree->schema)

#_(-> "type User { name: String }"
    parser/parse-graphql
    )
