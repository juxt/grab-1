;; Copyright © 2021, JUXT LTD.

(ns juxt.grab.alpha.schema
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(alias 'g (create-ns 'juxt.grab.alpha.graphql))

(def ^{:doc "Are there multiple items in the collection? (i.e. more than one)"}
  multiple? (comp pos? dec count))

(defn duplicates-by [pred coll]
  (->> coll
       (group-by pred)
       (keep #(when (-> % second multiple?) (first %)))
       seq))

(defn check-unique-type-names
  "'All types within a GraphQL schema must have unique names. No two provided
  types may have the same name.' -- https://spec.graphql.org/June2018/#sec-Schema"
  [{::keys [document] :as acc}]
  (let [duplicates
        (->> document
             (filter #(= (::g/definition-type %) :type-definition))
             (duplicates-by ::g/name))]
    (cond-> acc
      duplicates
      (update
       ::errors conj
       {:error "All types within a GraphQL schema must have unique names."
        :duplicates duplicates}))))

(defn check-no-conflicts-with-built-in-types
  "'No provided type may have a name which conflicts
  with any built in types (including Scalar and Introspection
  types).' -- https://spec.graphql.org/June2018/#sec-Schema"
  [{::keys [document] :as acc}]
  (let [conflicts
        (seq
         (set/intersection
          (set (map ::g/name (filter #(= (::g/definition-type %) :type-definition) document)))
          (set #{"Int" "Float" "String" "Boolean" "ID"})))]
    (cond-> acc
      conflicts
      (update
       ::errors conj
       {:error "No provided type may have a name which conflicts with any built in types."
        :conflicts (set conflicts)}))))

(defn check-unique-directive-names
  "'All directives within a GraphQL schema must have unique names.' --
  https://spec.graphql.org/June2018/#sec-Schema"
  [{::keys [document] :as acc}]
  (let [duplicates
        (->> document
             (filter #(= (::g/definition-type %) :directive-definition))
             (duplicates-by ::g/name))]
    (cond-> acc
      duplicates
      (update
       ::errors conj
       {:error "All directives within a GraphQL schema must have unique names."
        :duplicates duplicates}))))

(defn check-reserved-names
  "'All types and directives defined within a schema must not have a name which
  begins with \"__\" (two underscores), as this is used exclusively by GraphQL’s
  introspection system.' -- https://spec.graphql.org/June2018/#sec-Schema"
  [{::keys [document] :as acc}]
  (let [reserved-clashes
        (seq
         (filter #(str/starts-with? % "__")
                 (map ::g/name (filter #(#{:type-definition :directive-definition} (::g/definition-type %)) document))))]
    (cond-> acc
      reserved-clashes
      (update
       ::errors conj
       {:error "All types and directives defined within a schema must not have a name which begins with '__' (two underscores), as this is used exclusively by GraphQL's introspection system."}))))

(defn unwrapped-type [typ]
  (cond
    (::g/list-type typ) (unwrapped-type (::g/list-type typ))
    (::g/non-null-type typ) (unwrapped-type (::g/non-null-type typ))
    :else typ))

(defn output-type? [typ]
  (#{:scalar :object :interface :union :enum} (::g/kind (unwrapped-type typ))))

(defn input-type? [typ]
  (#{:scalar :enum :input-object} (::g/kind (unwrapped-type typ))))

(defn check-field-argument-definition [{::keys [provided-types built-in-types] :as acc} arg-def tf]
  (let [type-name (some-> arg-def ::g/type-ref unwrapped-type ::g/name)
        typ (or (get provided-types type-name)
                (get built-in-types type-name))]
    (cond-> acc
      (str/starts-with? (::g/name arg-def) "__")
      (update ::errors conj
              {:error "A field argument must not have a name which begins with two underscores."
               :arg-name (::g/name arg-def)
               :field-name (::g/name tf)})

      (nil? typ)
      (update ::errors conj
              {:error "A field argument must accept a type that is known."
               :field-name (::g/name tf)
               :type typ
               :argument-definition arg-def})

      (and typ (not (input-type? typ)))
      (update ::errors conj
              {:error "A field argument must accept a type that is an input type."
               :field-name (::g/name tf)
               :argument-definition arg-def}))))

(defn check-field-argument-definitions [acc arg-defs tf]
  (reduce #(check-field-argument-definition %1 %2 tf) acc arg-defs))

(defn resolve-named-type-ref [{::keys [built-in-types provided-types] :as acc} type-ref]
  (assert type-ref)
  (assert (::g/name type-ref) (pr-str type-ref))
  (or
   (get built-in-types (::g/name type-ref))
   (get provided-types (::g/name type-ref))
   (throw (ex-info "Cannot resolve type-ref" {:type-ref type-ref}))))

(defn check-field-definition [acc tf]
  (let [type-ref (some-> tf ::g/type-ref unwrapped-type)
        typ (resolve-named-type-ref acc type-ref)
        arg-defs (::g/arguments-definition tf)]
    (cond-> acc
      (str/starts-with? (::g/name tf) "__")
      (update ::errors conj
              {:error "A field must not have a name which begins with two underscores."
               :field-name (::g/name tf)})

      (nil? typ)
      (update ::errors conj
              {:error "A field must return a type that is known."
               :field-name (::g/name tf)
               :field-type-name (::g/name type-ref)})

      (and typ (not (output-type? typ)))
      (update ::errors conj
              {:error "A field must return a type that is an output type."
               :field-name (::g/name tf)
               :type typ
               :tf tf})
      arg-defs
      (check-field-argument-definitions arg-defs tf))))

(defn check-duplicate-field-names [acc td]
  (let [duplicates (duplicates-by ::g/name (::g/field-definitions td))]
    (cond-> acc
      duplicates
      (update
       ::errors conj
       {:error (format "Each field must have a unique name within the '%s' Object type; no two fields may share the same name." (::g/name td))
        :duplicates (vec duplicates)}))))

(defn check-type-fields [acc {::g/keys [field-definitions] :as td}]
  (as-> acc %
    (check-duplicate-field-names % td)
    (reduce check-field-definition % field-definitions)))

(defn check-object-interfaces-exist [{::keys [provided-types] :as acc}
                                     {::g/keys [interfaces] :as td}]
  (reduce
   (fn [acc decl]
     (let [ifc (get provided-types decl)]
       (cond-> acc
         (nil? ifc) (update ::errors conj {:error "Interface is declared but not provided." :interface decl}))))
   acc
   ;; We already check that interfaces are distinct, but if they're not we don't
   ;; want to produce identical errors.
   (distinct interfaces)))

(defn check-sub-type-covariance [acc object-field-type-ref interface-field-type-ref]

  (let [oft (when (some-> object-field-type-ref ::g/name)
              (resolve-named-type-ref acc object-field-type-ref))
        ift (when (some-> interface-field-type-ref ::g/name)
              (resolve-named-type-ref acc interface-field-type-ref))]
    (cond
      ;; 4.1.1.1. An object field type is a valid sub‐type if it is equal to
      ;; (the same type as) the interface field type.
      (and
       (some-> object-field-type-ref ::g/name)
       (some-> interface-field-type-ref ::g/name)
       (= (resolve-named-type-ref acc object-field-type-ref)
          (resolve-named-type-ref acc interface-field-type-ref)))
      acc

      ;; 4.1.1.2. An object field type is a valid sub‐type if it is an Object
      ;; type and the interface field type is either an Interface type or a
      ;; Union type and the object field type is a possible type of the
      ;; interface field type.
      (and
       (some-> object-field-type-ref ::g/name)
       (some-> interface-field-type-ref ::g/name)
       (and
        (= (::g/kind oft) :object)
        (or
         (= (::g/kind ift) :interface)
         (= (::g/kind ift) :union))
        (contains? (set (::g/interfaces oft)) (::g/name ift))))
      acc

      ;; 4.1.1.3. An object field type is a valid sub‐type if it is a List type
      ;; and the interface field type is also a List type and the list‐item type
      ;; of the object field type is a valid sub‐type of the list‐item type of
      ;; the interface field type.
      (and
       (some-> object-field-type-ref ::g/list-type)
       (some-> interface-field-type-ref ::g/list-type))
      (check-sub-type-covariance
       acc
       (-> object-field-type-ref ::g/list-type)
       (-> interface-field-type-ref ::g/list-type))

      :else
      (update acc
              ::errors conj
              {:error "The object field must be of a type which is equal to or a sub‐type of the interface field (covariant)."
               :object-field-type-ref object-field-type-ref
               :interface-field-type-ref interface-field-type-ref})))

  ;;acc
  )


;; TODO: Put these in a separate function


;; An object field type is a valid sub‐type if it is equal to (the
;; same type as) the interface field type.

;; An object field type is a valid sub‐type if it is an Object type
;; and the interface field type is either an Interface type or a
;; Union type and the object field type is a possible type of the
;; interface field type.


(defn check-object-interface-fields
  [{::keys [provided-types] :as acc}
   {::g/keys [interfaces field-definitions] :as td}]

  (let [object-fields-by-name (group-by ::g/name field-definitions)
        interfaces (keep provided-types interfaces)
        interface-fields
        (for [i interfaces f (::g/field-definitions i)]
          (assoc f ::interface (::g/name i)))]

    (reduce
     (fn [acc interface-field]
       (let [field-name (::g/name interface-field)
             object-field (first (get object-fields-by-name field-name))]
         (if-not object-field
           ;; The object type must include a field of the same name for every field
           ;; defined in an interface.
           (update acc ::errors conj {:error "The object type must include a field of the same name for every field defined in an interface."
                                      :interface (::interface interface-field)
                                      :missing-field-name field-name})

           (check-sub-type-covariance acc (::g/type-ref object-field) (::g/type-ref interface-field)))))

     acc interface-fields)))

(defn check-object-interfaces [acc {::g/keys [interfaces] :as td}]
  (cond-> acc
    (not (apply distinct? interfaces))
    (update ::errors conj {:error "An object type may declare that it implements one or more unique interfaces. Interfaces declaration contains duplicates."
                           :type-definition td})
    interfaces (-> (check-object-interfaces-exist td)
                   (check-object-interface-fields td))))

(defn check-object-type [acc {::g/keys [field-definitions interfaces] :as td}]
  (cond-> acc
    ;; "1. An Object type must define one or more fields."
    (or
     (nil? field-definitions)
     (zero? (count field-definitions)))
    (update ::errors conj {:error "An Object type must define one or more fields"
                           :type-definition td})
    true (check-type-fields td)
    interfaces (check-object-interfaces td)))

;; See Type Validation sub-section of https://spec.graphql.org/June2018/#sec-Objects
(defn check-types
  [{::keys [document] :as acc}]
  (reduce
   (fn [acc td]
     (cond-> acc
       (= (::g/kind td) :object)
       (check-object-type td)))
   acc
   (filter #(= (::g/definition-type %) :type-definition) document)))

(defn provide-types
  "Creates the schema's 'provided-types' entry."
  [{::keys [document] :as acc}]
  (reduce
   (fn [acc {::g/keys [name] :as td}]
     (assoc-in
      acc [::provided-types name]
      (assoc td ::fields-by-name (into {} (map (juxt ::g/name identity) (::g/field-definitions td))))))
   acc
   (filter #(= (::g/definition-type %) :type-definition) document)))

(defn check-root-operation-type
  "Depends on validate-types."
  [acc]
  (let [query-root-op-type-name (get-in acc [::root-operation-type-names :query])
        query-root-op-type (get-in acc [::provided-types query-root-op-type-name])]
    (assert query-root-op-type-name)
    (cond
      (nil? query-root-op-type)
      (update acc
       ::errors conj
       {:error (format "The query root operation type must be provided: '%s'" query-root-op-type-name)})

      (not= :object (get query-root-op-type ::g/kind))
      (update acc
       ::errors conj
       {:error "The query root operation type must be an Object type"})

      :else acc)))

(defn check-schema-definition-count [{::keys [document] :as acc}]
  (when (pos? (dec (count (filter #(= (::g/definition-type %) :schema-definition) document))))
    (update acc ::errors conj {:error "A document must include at most one schema definition"})))

(defn process-schema-definition [{::keys [document] :as acc}]
  (let [schema-def (first (filter #(= (::g/definition-type %) :schema-definition) document))]
    (cond-> acc
      true
      (assoc
       ::root-operation-type-names
       (or
        (when schema-def (::g/operation-types schema-def))
        {:query "Query" :mutation "Mutation" :subscription "Subscription"}))
      (::g/directives schema-def)
      (assoc ::g/directives (::g/directives schema-def)))))

(defn compile-schema
  "Create a schema from the parsed document."
  [document]
  (reduce
   (fn [acc f]
     (or (f acc) acc))
   {::errors []
    ::document document
    ::provided-types
    {}
    ::built-in-types
    {"Int" {::g/name "Int"
            ::g/kind :scalar}
     "Float" {::g/name "Float"
              ::g/kind :scalar}
     "String" {::g/name "String"
               ::g/kind :scalar}
     "Boolean" {::g/name "Boolean"
                ::g/kind :scalar}
     "ID" {::g/name "ID"
           ::g/kind :scalar}}}
   [provide-types
    check-unique-type-names
    check-no-conflicts-with-built-in-types
    check-unique-directive-names
    check-reserved-names
    check-types
    process-schema-definition
    check-schema-definition-count
    check-root-operation-type]))

(defn process-schema-extension [schema {::g/keys [directives operation-types]}]
  (let [add-directives
        (fn [schema directives]
          (let [existing-directives
                (set/intersection
                 (some-> schema ::g/directives keys set)
                 (some-> directives keys set))]
            (if (seq existing-directives)
              (update
               schema ::errors conj
               {:error "Any directives provided must not already apply to the original Schema"
                :existing-directives existing-directives})
              (update schema ::g/directives merge directives))))
        add-operation-types
        (fn [schema operation-types]
          (let [duplicates
                (set/intersection
                 (some-> schema ::root-operation-type-names keys set)
                 (some-> operation-types keys set))]
            (if (seq duplicates)
              (update
               schema ::errors conj
               {:error "Schema extension attempting to add root operation types that already exist"
                :duplicates duplicates})
              (update schema ::root-operation-type-names merge operation-types))))]
    (cond-> schema
      directives (add-directives directives)
      operation-types (add-operation-types operation-types))))

(defn extend-schema
  "Extend a schema"
  [schema document]
  (assert (empty? (::errors schema)) "Cannot extend schema when there are pre-existing errors")
  (reduce
   (fn [schema definition]
     (cond-> schema
       (= (::g/definition-type definition) :schema-extension)
       (process-schema-extension definition)))
   schema
   document))
