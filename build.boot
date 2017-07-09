(set-env!
  :dependencies '[[org.clojure/clojure "1.8.0"]
                  [adzerk/env "0.4.0"]
                  [irresponsible/tentacles "0.6.1"]

                  ; silence slf4j logging dammit
                  [org.slf4j/slf4j-nop "1.7.25"]])

(def ^:const +version+ "0.0.5")

(require '[adzerk.env         :as    env]
         '[boot.util          :refer (dosh)]
         '[clojure.java.shell :as    sh]
         '[clojure.set        :as    set]
         '[tentacles.core     :as    gh])

(env/def GITHUB_TOKEN :required)

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

(defn changelog-for
  [version]
  "TODO: parse changes from CHANGELOG.md")

(defn create-release
  [version description]
  (gh/api-call :post "repos/%s/%s/releases" [todo todo]
               {:oauth-token GITHUB_TOKEN
                :tag_name    +version+
                :name        +version+
                :body        description}))

(deftask release
  "* Creates a new version tag and pushes it to the remote.
   * Creates a new release via the GitHub API.
     * The description of the release is parsed from CHANGELOG.md."
  []
  (assert (repo-clean?) "You have uncommitted changes. Aborting.")

  (create-tag +version+ (format "version %s" +version+))
  (push-tags)
  (let [changes (changelog-for +version+)]
    (create-release +version+ changes)))

