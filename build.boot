(set-env!
  :dependencies '[[org.clojure/clojure     "1.8.0"]
                  [adzerk/env              "0.4.0"]
                  [irresponsible/tentacles "0.6.1"]
                  [instaparse              "1.4.7"]

                  ; silence slf4j logging dammit
                  [org.slf4j/slf4j-nop     "1.7.25"]])

(def ^:const +version+ "0.0.15")

(require '[adzerk.env         :as    env]
         '[boot.util          :refer (dosh info)]
         '[clojure.java.shell :as    sh]
         '[clojure.set        :as    set]
         '[clojure.string     :as    str]
         '[tentacles.core     :as    gh]
         '[instaparse.core    :as    insta])

(env/def GITHUB_TOKEN :required)

(defn repo-clean?
  []
  (-> (sh/sh "git" "status" "--porcelain") :out empty?))

(defn create-tag
  "Create a tag in the local repo."
  [name message]
  (info "Creating tag %s...\n" name)
  (dosh "git" "tag" "-a" name "-m" message))

(defn push-tags
  "Push tags to remote repo."
  []
  (info "Pushing tags...\n")
  (dosh "git" "push" "--tags"))

(defn changelog-for
  [version]
  (as-> (slurp "CHANGELOG.md") x
    ((insta/parser
       "changelog      = <preamble?> version+
        preamble       = #'(#+\\s*)?CHANGELOG\\n+'
        version        = version-number changes
        version-number = <#'#+\\s*'> #'\\d[^\\s]*'
        changes        = (!version-number #'(.|\\n)')*")
     x)
    (insta/transform
      {:changes        str
       :version-number identity
       :version        list
       :changelog      #(reduce (fn [m [k v]]
                                  (assoc m k (str "## " k v)))
                                {}
                                %&)}
      x)
    (get x version)))

(defn current-remote
  []
  (-> (sh/sh "git" "remote") :out str/trim-newline))

(defn current-github-repo
  "Returns a tuple of the username and repo name of the current repo."
  []
  (->> (sh/sh "git" "remote" "get-url" "--push" (current-remote))
       :out
       (re-find #"github.com[:/](.*)/(.*).git")
       rest))

(defn create-release
  [version description]
  (let [[user repo] (current-github-repo)]
    (info "Creating release for %s...\n" version)
    (let [result         (-> (gh/api-call :post "repos/%s/%s/releases"
                                          [user repo]
                                          {:oauth-token GITHUB_TOKEN
                                           :tag_name    +version+
                                           :name        +version+
                                           :body        description}))]
      (if (:id result)
        ;; Success! return the most relevant keys, for the sake of brevity.
        (select-keys result [:id :name :tag_name :body :html_url
                             :published_at])
        ;; Something went wrong; return the whole result.
        result))))

(deftask release
  "* Creates a new version tag and pushes it to the remote.
   * Creates a new release via the GitHub API.
     * The description of the release is parsed from CHANGELOG.md."
  []
  (assert (repo-clean?) "You have uncommitted changes. Aborting.")

  (create-tag +version+ (format "version %s" +version+))
  (push-tags)
  (let [changes (changelog-for +version+)]
    (clojure.pprint/pprint (create-release +version+ changes))))

