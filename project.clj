(defproject magic-sheet "0.1.0"
  :description "Create magic sheets to improve your Clojure[Script] repl experience."
  :url "https://github.com/jpmonettas/magic-sheet"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [nrepl "0.4.4"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/tools.cli "0.3.7"]]
  :source-paths      ["src"]
  :java-source-paths ["java-src"]
  :main ^:skip-aot magic-sheet.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
