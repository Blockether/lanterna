;; One-shot Clojars deployer for the lanterna fork jar.
;;
;; Reads CLOJARS_USERNAME and CLOJARS_PASSWORD from env (deps-deploy's
;; default conventions). For our setup:
;;   CLOJARS_USERNAME = blockether-deployer
;;   CLOJARS_PASSWORD = $CLOJARS_DEPLOY_TOKEN
;;
;; Run with:
;;   clojure -Sdeps '{:deps {slipset/deps-deploy {:mvn/version "0.2.3"}}}' \
;;     -M deploy_to_clojars.clj
(require '[deps-deploy.deps-deploy :as dd]
         '[clojure.string :as str])

;; Version is read straight from pom.xml so this script never goes stale when
;; the fork bumps (it used to be hardcoded — and drifted to vis.8 while the
;; pom was on vis.10).
(let [pom     "pom.xml"
      version (-> (slurp pom)
                ;; first <version> in the pom is the artifact's own version
                (->> (re-find #"<version>([^<]+)</version>"))
                second
                str/trim)
      jar     (str "target/lanterna-" version ".jar")
      user    (System/getenv "CLOJARS_USERNAME")
      pass    (System/getenv "CLOJARS_PASSWORD")]
  (when (or (empty? user) (empty? pass))
    (binding [*out* *err*]
      (println "ERROR: CLOJARS_USERNAME and CLOJARS_PASSWORD must be set."))
    (System/exit 1))
  (when-not (.exists (clojure.java.io/file jar))
    (binding [*out* *err*]
      (println "ERROR:" jar "not found — build it first:")
      (println "  mvn -o -q -DskipTests -Djacoco.skip=true package"))
    (System/exit 1))
  (println "Deploying" jar "as com.blockether/lanterna" version "to Clojars ...")
  (dd/deploy {:installer       :remote
              :artifact        jar
              :pom-file        pom
              :sign-releases?  false})
  (println "✓ deployed"))
