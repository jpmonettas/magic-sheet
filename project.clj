(defproject magic-sheet "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.oracle/javafx-runtime "2.2.0"]
                 [nrepl "0.4.4"]
                 [org.clojure/core.async "0.4.474"]]
  :source-paths      ["src"]
  :java-source-paths ["java-src"]
  :main ^:skip-aot magic-sheet.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
