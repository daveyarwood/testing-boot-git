(set-env!
  :dependencies '[[org.clojure/clojure "1.8.0"]
                  [clj-jgit "0.8.9"]

                  ; silence slf4j logging dammit
                  [org.slf4j/slf4j-nop "1.7.25"]])

(def ^:const +version+ "0.0.2")

(require '[clj-jgit.porcelain :as jgit]
         '[clojure.set :as set])

(defn repo-clean?
  []
  (jgit/with-repo "."
    (->> repo jgit/git-status vals (apply set/union) empty?)))

(defn create-tag
  [name message]
  (jgit/with-repo "."
    (doto (.tag repo)
      (.setName name)
      (.setMessage message)
      .call)))

(deftask push-version
  "Creates a new version tag and pushes it to the remote."
  []
  (assert (repo-clean?) "You have uncommitted changes. Aborting.")

  (create-tag +version+ (format "version %s" +version+)))

