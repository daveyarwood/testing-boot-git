(set-env!
  :dependencies '[[org.clojure/clojure "1.8.0"]
                  [adzerk/env "0.4.0"]

                  ; silence slf4j logging dammit
                  [org.slf4j/slf4j-nop "1.7.25"]])

(def ^:const +version+ "0.0.4")

(require '[boot.util          :refer (dosh)]
         '[clojure.java.shell :as sh]
         '[clojure.set        :as set])

(defn repo-clean?
  []
  (-> (sh/sh "git" "status" "--porcelain") :out empty?))

(defn create-tag
  "Create a tag in the local repo."
  [name message]
  (dosh "git" "tag" "-a" name "-m" message))

(defn push-tags
  "Push tags to remote repo."
  []
  (dosh "git" "push" "--tags"))

(deftask push-version
  "Creates a new version tag and pushes it to the remote."
  []
  (assert (repo-clean?) "You have uncommitted changes. Aborting.")

  (create-tag +version+ (format "version %s" +version+))
  (push-tags))

