(set-env!
  :dependencies '[[org.clojure/clojure     "1.8.0"]
                  [adzerk/env              "0.4.0"]
                  [irresponsible/tentacles "0.6.1"]
                  [instaparse              "1.4.7"]
                  [cheshire                "5.7.1"]

                  ; silence slf4j logging dammit
                  [org.slf4j/slf4j-nop     "1.7.25"]])

(def ^:const +version+ "0.0.22")

(require '[adzerk.env         :as    env]
         '[boot.util          :refer (dosh info fail)]
         '[clojure.java.io    :as    io]
         '[clojure.java.shell :as    sh]
         '[clojure.pprint     :refer (pprint)]
         '[clojure.set        :as    set]
         '[clojure.string     :as    str]
         '[tentacles.repos    :as    repos]
         '[instaparse.core    :as    insta]
         '[cheshire.core      :as    json])

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
    (repos/create-release user repo {:oauth-token GITHUB_TOKEN
                                     :tag_name    +version+
                                     :name        +version+
                                     :body        description})))

(defn curl
  "Minimal cURL wrapper."
  [{:keys [headers method data-binary]} url]
  (:out (apply sh/sh (concat ["curl"]
                             (mapcat (fn [[k v]]
                                       ["-H" (str k ":" v)])
                                     headers)
                             ["-X" method]
                             [url]
                             ["--data-binary" (str "@" data-binary)]))))

(defn upload-asset
  [upload-url file]
  (let [;; The "upload_url" from the GitHub API response is a hypermedia
        ;; relation. In this case, we want to turn {?name,label} into
        ;; ?name=foop.txt (or whatever our file asset is called)
        url (str/replace upload-url
                         #"\{\?[^}]*\}"
                         (format "?name=%s" (.getName file)))]
    ;; We have to shell out use cURL for this because clj-http doesn't appear to
    ;; support SNI, which the GitHub API requires in order to upload assets.
    (-> (curl {:headers     {"Authorization" (str "token " GITHUB_TOKEN)
                             "Content-Type"  "application/octet-stream"}
               :method      "POST"
               :data-binary (.getAbsolutePath file)}
              url)
        (json/parse-string true))))

(deftask release
  "* Creates a new version tag and pushes it to the remote.
   * Creates a new release via the GitHub API.
     * The description of the release is parsed from CHANGELOG.md."
  [a assets ASSETS #{str} "Assets to upload and include with this release."]
  (assert (repo-clean?) "You have uncommitted changes. Aborting.")
  (let [changes (changelog-for +version+)
        files   (map io/file assets)]
    (assert changes (format "Missing changelog for version %s." +version+))
    (doseq [file files]
      (assert (.exists file) (format "File not found: %s" (.getName file))))
    (create-tag +version+ (format "version %s" +version+))
    (push-tags)
    (let [{:keys [id html_url upload_url body] :as response}
          (create-release +version+ changes)]
      (if id ; if JSON result contains an "id" field, then it was successful
        (do
          (info "Release published: %s\n" html_url)
          (println)
          (println "Release description:")
          (println)
          (println body)
          (doseq [file files]
            (let [{:keys [id browser_download_url] :as response}
                  (upload-asset upload_url file)]
              (if id
                (info "Asset uploaded: %s\n" (.getName file))
                (do
                  (fail "Failed to upload %s. API response:\n")
                  (pprint response))))))
        (do
          (fail "Failed to create release. API response:\n")
          (pprint response))))))

