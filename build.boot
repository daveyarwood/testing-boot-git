(set-env!
  :dependencies '[[org.clojure/clojure "1.8.0"]
                  [adzerk/env          "0.4.0"]
                  [io.djy/boot-github  "0.1.4"]])

(def ^:const +version+ "0.0.28")

(require '[adzerk.env         :as    env]
         '[io.djy.boot-github :refer (push-version-tag create-release)])

(env/def GITHUB_TOKEN :required)

(deftask release
  []
  (comp
    (push-version-tag :version +version+)
    (create-release :version      +version+
                    :changelog    true
                    :github-token GITHUB_TOKEN
                    :assets       #{"lol-this-file-doesnt-exist.txt"})))

