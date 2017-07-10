(set-env!
  :dependencies '[[org.clojure/clojure     "1.8.0"]
                  [adzerk/env              "0.4.0"]
                  [irresponsible/tentacles "0.6.1"]
                  [instaparse              "1.4.7"]

                  ; silence slf4j logging dammit
                  [org.slf4j/slf4j-nop     "1.7.25"]])

(def ^:const +version+ "0.0.19")

(require '[adzerk.env         :as    env]
         '[boot.util          :refer (dosh info fail)]
         '[clojure.java.shell :as    sh]
         '[clojure.pprint     :refer (pprint)]
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
    (gh/api-call :post "repos/%s/%s/releases"
                 [user repo]
                 {:oauth-token GITHUB_TOKEN
                  :tag_name    +version+
                  :name        +version+
                  :body        description})))

(deftask release
  "* Creates a new version tag and pushes it to the remote.
   * Creates a new release via the GitHub API.
     * The description of the release is parsed from CHANGELOG.md."
  [a assets ASSETS #{str} "Assets to upload and include with this release."]
  (assert (repo-clean?) "You have uncommitted changes. Aborting.")
  (let [changes (changelog-for +version+)]
    (assert changes (format "Missing changelog for version %s." +version+))
    (create-tag +version+ (format "version %s" +version+))
    (push-tags)
    (let [{:keys [id html_url body] :as response}
          (create-release +version+ changes)]
      (if id ; if JSON result contains an "id" field, then it was successful
        (do
          (info "Release published: %s\n" html_url)
          (println)
          (println "Release description:")
          (println)
          (println body))
        (do
          (fail "There was a problem. Here is the GitHub API response:\n")
          (pprint response))))))

