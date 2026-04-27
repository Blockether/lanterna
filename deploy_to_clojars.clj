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
(require '[deps-deploy.deps-deploy :as dd])

(let [jar     "target/lanterna-3.1.5-vis.2.jar"
      pom     "pom.xml"
      user    (System/getenv "CLOJARS_USERNAME")
      pass    (System/getenv "CLOJARS_PASSWORD")]
  (when (or (empty? user) (empty? pass))
    (binding [*out* *err*]
      (println "ERROR: CLOJARS_USERNAME and CLOJARS_PASSWORD must be set."))
    (System/exit 1))
  (println "Deploying" jar "as com.blockether/lanterna 3.1.5-vis.2 to Clojars ...")
  (dd/deploy {:installer       :remote
              :artifact        jar
              :pom-file        pom
              :sign-releases?  false})
  (println "✓ deployed"))
