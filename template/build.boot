(set-env!
  :resource-paths #{"src"}
  :dependencies '[[org.clojure/clojure "1.9.0" :scope "provided"]]
  :repositories (conj (get-env :repositories)
                  ["clojars" {:url "https://clojars.org/repo/"
                              :username (System/getenv "CLOJARS_USER")
                              :password (System/getenv "CLOJARS_PASS")}]))

(task-options!
  pom {:project 'edna/lein-template
       :version "1.5.3"
       :description "A template for making edna projects"
       :url "https://github.com/oakes/edna"
       :license {"Public Domain" "http://unlicense.org/UNLICENSE"}}
  push {:repo "clojars"})

(deftask local []
  (comp (pom) (jar) (install)))

(deftask deploy []
  (comp (pom) (jar) (push)))

