(ns magic-sheet.core
  (:gen-class)
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [magic-sheet.styles :as styles]
            [magic-sheet.utils :refer [event-handler run-now]]
            [nrepl.core :as repl])
  (:import java.io.File
           java.util.UUID
           [javafx.animation Animation KeyFrame KeyValue Timeline]
           javafx.beans.property.ReadOnlyObjectWrapper
           javafx.collections.FXCollections
           [javafx.scene Node SceneBuilder]
           [javafx.scene.control Alert Alert$AlertType Button ButtonType ComboBox ContextMenu Dialog Label MenuItem TableColumn TableView TextArea TextField]
           javafx.scene.effect.DropShadow
           [javafx.scene.input Clipboard DataFormat]
           [javafx.scene.layout BorderPane GridPane HBox Pane VBox]
           javafx.scene.paint.Color
           [javafx.stage Modality StageBuilder]
           [javafx.util Callback Duration]
           [utils CellUtils DragResizeMod DragResizeMod$OnDragResizeEventListener]))

#_(javafx.embed.swing.JFXPanel.)

(def main-pane nil)
(def menu nil)
(def stage nil)
(def repl-connection nil)
(def repl-client nil)
(def nodes (atom {}))

(def magic-sheet-file-name "./magic-sheet.edn")
(def repl-command-timeout 60000) ;; timeout in  millis 1 minute

(defn store-node! [{:keys [node-id] :as node}]
  (swap! nodes assoc node-id node))

(defn update-node-in-store! [{:keys [node-id] :as node}]
  (swap! nodes update node-id merge node))

(defn remove-node-from-store! [node-id]
  (swap! nodes dissoc node-id))

(defn dump-nodes-to-file []
  (let [nodes-str (with-out-str
                    (->> @nodes
                         (map (fn [[nid n]]
                                [nid (dissoc n :exec-fn)]))
                         (into {})
                         pprint/pprint))]
    (spit magic-sheet-file-name nodes-str)))

(defn make-context-menu [items]
  (let [cm (ContextMenu.)
        cm-items (->> items
                      (map (fn [{:keys [text on-click]}]
                             (doto (MenuItem. text)
                               (.setOnAction (event-handler [_] (on-click)))))))]
    (-> cm
        .getItems
        (.addAll (into-array MenuItem cm-items)))
    cm))

(defn make-table-ui []
  (let [table-data-model (atom nil)
        table (TableView.)
        update-result (fn [data]
                        (assert (coll? data) (str "Data is not a collection " (type data)))
                        (assert (every? map? data) "Sequence elements should be maps")

                        (if-let [data-model @table-data-model]
                          (doto data-model
                            (.clear)
                            (.addAll (into-array Object data)))
                          (let [obs-list (FXCollections/observableArrayList data)
                                cols (->> data first keys)
                                table-columns (->> cols
                                                   (map (fn [c]
                                                          (doto (TableColumn. (str c)) 
                                                            (.setCellValueFactory (reify Callback
                                                                                    (call [this v]
                                                                                      (ReadOnlyObjectWrapper. (get (.getValue v) c)))))
                                                            (.setCellFactory (reify Callback
                                                                               (call [this v]                                                         
                                                                                 (let [cell (CellUtils/makeTableCell)]
                                                                                   (.setOnMouseClicked cell (event-handler
                                                                                                             [e]
                                                                                                             (let [cell-text (-> e .getTarget .getText)]
                                                                                                               (doto (Clipboard/getSystemClipboard)
                                                                                                                 (.setContent {DataFormat/PLAIN_TEXT cell-text})))))
                                                                                   cell))))))))]
                            (reset! table-data-model obs-list)
                            (doto table
                              (.setItems obs-list))
                            (-> table
                                .getColumns
                                (.addAll (into-array TableColumn table-columns))))))]
    
    {:result-node table
     :update-result update-result}))

(defn make-table-result-node []
  (let [{:keys [result-node update-result]} (make-table-ui)]
    {:result-node result-node
     :update-result update-result}))

(defn make-text-result-node []
  (let [ta (doto (TextArea.)
             (.setEditable false))
        update-result (fn [v]
                        (doto ta
                          (.setText (with-out-str
                                      (pprint/pprint v)))))]
    #_(update-result ret-val)
    {:result-node ta
     :update-result update-result}))

(defn make-params-box [inputs]
  (let [make-p-hbox (fn [[pname tf]] (HBox. 5 (into-array Node [(doto (Label. pname)
                                                                  (.setStyle styles/label)) tf])))]
   (VBox. 5 (into-array Node (map make-p-hbox inputs)))))

(defn make-node-ui [{:keys [node-id title on-close result-type x y w h key ret-val input-params]
                     :or {x 0 y 0 h 100 w 100}}]
  (let [title-btn (doto (Button. (format "[%s] %s" key title))
                    (.setStyle styles/button))
        inputs (->> input-params
                    (map (fn [p] [p (TextField.)]))
                    (into {}))
        get-params-values (fn [] (map (fn [[p tf]] [p (.getText tf)]) inputs))
        close-btn (doto (Button. "X")
                    (.setStyle styles/button))
        bar (doto (BorderPane.)
              (.setLeft title-btn)
              (.setRight close-btn))
        {:keys [update-result result-node]} (case result-type
                                              :as-table (make-table-result-node)
                                              :as-value (make-text-result-node)
                                              nil)
        
        main-box (doto (VBox. 5 (into-array Node (cond-> [bar]
                                                   (not-empty input-params) (conj (make-params-box inputs)) 
                                                   result-node              (conj result-node))))
                   (.setStyle styles/node)
                   (.setLayoutX x)
                   (.setLayoutY y)
                   (.setPrefSize w h))]

    (doto close-btn
      (.setOnAction (event-handler [_] (on-close main-box))))

    (DragResizeMod/makeResizable main-box (reify DragResizeMod$OnDragResizeEventListener
                                            (onDrag [this node x y h w]
                                              (update-node-in-store! {:node-id node-id :x x :y y :w w :h h})
                                              (.setLayoutX node x)
                                              (.setLayoutY node y))
                                            (onResize [this node x y h w]
                                              (update-node-in-store! {:node-id node-id :x x :y y :w w :h h})
                                              (.setPrefSize node w h))))
    
    {:node main-box
     :x x :y y :w w :h h
     :exec-button title-btn
     :update-result update-result
     :get-params-values get-params-values}))

(defn remove-node [n]
  (-> main-pane
      .getChildren
      (.remove n)))

(defn eval-on-repl [code]
  (let [result (repl/message repl-client {:op :eval :code code})
        err (->> result
             (filter :err)
             first :err)]
    (if err
      {:error err}
      {:value (->> result
               (filter :value)
               first :value)})))

(defn make-executing-animation [node]
  (let [animation-min 1000
        animation-started (atom nil)
        shadow (doto (DropShadow.)
                 (.setColor (Color/web "#0D8163"))
                 (.setSpread 0.75))
        executing-anim (doto (Timeline. (into-array KeyFrame
                                                    [(KeyFrame. Duration/ZERO (into-array KeyValue [(KeyValue. (.radiusProperty shadow)  0)]))
                                                     (KeyFrame. (Duration/seconds 0.5) (into-array KeyValue [(KeyValue. (.radiusProperty shadow)  20)]))]))
                         (.setCycleCount Animation/INDEFINITE))]
    {:start-animation (fn []
                        (reset! animation-started (System/currentTimeMillis))
                        (.setEffect node shadow)
                        (.play executing-anim))
     :stop-animation (fn []
                       (if (> (System/currentTimeMillis) (+ @animation-started animation-min))
                         (do (.stop executing-anim)
                             (.setEffect node nil))
                         (async/take! (async/timeout animation-min)
                                      (fn [_]
                                        (.stop executing-anim)
                                        (.setEffect node nil)))))}))

(defn params-names [code]
  (->> (re-seq #"\$\{(.+?)\}" code)
       (map second)))

(defn render-code [code-template params-value-map]
  (reduce (fn [r [p v]]
            (str/replace r (format "${%s}" p) (str v)))
          code-template
          params-value-map))

(defn show-error [header error-message]
  (run-now
   (let [alert (doto (Alert. Alert$AlertType/ERROR)
                 (.setTitle "Error")
                 (.setHeaderText header)
                 (.setContentText error-message))]
     (.showAndWait alert))))


(defn add-command-node [{:keys [title code x y w h result-type node-id key] :as node}]
  (let [input-params (params-names code)        
        eval-code (fn [params-values]
                    (binding [*default-data-reader-fn* str]
                      (let [{:keys [error value]} (-> (render-code code params-values)
                                                      eval-on-repl)]
                        (if error
                          (show-error "Error " error)
                          (read-string value)))))
        node-ui-result (make-node-ui (merge node
                                            {:on-close (fn [n]
                                                         (remove-node-from-store! node-id)
                                                         (remove-node n))
                                             :ret-val (when result-type
                                                       (eval-code {}))
                                            :input-params input-params}))
        {:keys [node exec-button update-result get-params-values x y w h]} node-ui-result
        
        {:keys [start-animation stop-animation]} (make-executing-animation node)
        exec-fn (fn []
                  (start-animation)
                  (async/thread
                    (try
                      (when-let [v (eval-code (get-params-values))]
                        (run-now (when update-result
                                   (update-result v))))
                      (catch Exception e
                        (show-error "Error "  (with-out-str (.printStackTrace e)))
                        (.printStackTrace e))
                      (finally (run-now (stop-animation))))))]

    (doto exec-button
      (.setOnAction (event-handler [_] (exec-fn))))

    (-> main-pane
        .getChildren
        (.add node))
    
    (store-node! {:node-id node-id
                  :title title
                  :result-type result-type
                  :code code
                  :x x
                  :y y
                  :w w
                  :h h
                  :key key
                  :exec-fn exec-fn})))

(defn make-new-command-dialog []
  (let [node-id (str (UUID/randomUUID))
        d (doto (Dialog.)
            (.setTitle "New command")
            (.initModality Modality/APPLICATION_MODAL)
            (.setResizable false)
            (.setWidth 620)
            (.setHeight 285))
        
        title-input (doto (TextField.) (.setStyle styles/inputs))
        key-input (doto (TextField.) (.setStyle styles/inputs))
        code-txta (doto (TextArea.) (.setStyle styles/inputs))
        type-combo (doto (ComboBox.) (.setStyle styles/inputs))
        make-label (fn [text] (doto (Label. text)
                                (.setStyle styles/label)))
        grid-pane (doto (GridPane.)
                    (.setHgap 10)
                    (.setVgap 10)
                    (.add (make-label "Title:")      0 0)
                    (.add title-input                1 0)
                    (.add (make-label "Code:")       0 1)
                    (.add code-txta                  1 1)
                    (.add (make-label "Key:")        0 2)
                    (.add key-input                  1 2)
                    (.add (make-label "Result as:")  0 3)
                    (.add type-combo                 1 3)
                    (.setStyle styles/backpane))
        result-type-options {"Result as table" :as-table
                             "Result as value" :as-value}]

    (-> type-combo
        .getItems
        (.addAll (into-array String (keys result-type-options))))
    (-> d
        .getDialogPane
        .getButtonTypes
        (.addAll (into-array Object [ButtonType/OK ButtonType/CANCEL])))
    (doto (.getDialogPane d)
      (.setContent grid-pane)
      (.setStyle styles/backpane))

    (.setResultConverter d (reify Callback
                             (call [this button]
                               (when (= "OK" (.getText button))
                                 (cond-> {:node-id node-id
                                          :title   (.getText title-input) 
                                          :code    (.getText code-txta)
                                          :key     (str/upper-case (first (.getText key-input)))
                                          :result-type (result-type-options (.getValue type-combo))})))))
    d))

(defn load-nodes-from-file [file]
  (if (.exists (File. file))
   (let [loaded-nodes (->> (slurp file)
                           edn/read-string
                           vals)]
     (doseq [{:keys [type] :as n} loaded-nodes]
       (add-command-node n)))
   (println "No magic-sheet.edn found, starting a new sheet.")))

(defn -main
  ""
  [& args]
  (let [[repl-host repl-port] (some-> args
                                      first
                                      (str/split #":"))]

    (when (or (empty? repl-port)
              (empty? repl-host))
      (println "\nUsage: \n\t java -jar magic-sheet.jar localhost:7777\n")
      (System/exit 1))
    
    ;; Make repl connection and client
    (try
      (alter-var-root #'repl-connection (constantly (repl/connect :host repl-host :port (Integer/parseInt repl-port))))
      (alter-var-root #'repl-client (constantly (repl/client repl-connection repl-command-timeout)))
      (catch java.net.ConnectException ce
        (println (format "\nConnection refused. Are you sure there is a nrepl server running at %s:%s ?\n" repl-host repl-port))
        (System/exit 1)))
    
    ;; Initialize the JavaFX toolkit
    (javafx.embed.swing.JFXPanel.)

    (alter-var-root #'menu
                    (constantly (make-context-menu [{:text "New command" :on-click #(-> (make-new-command-dialog)
                                                                                        .showAndWait
                                                                                        .get 
                                                                                        add-command-node)}
                                                    {:text "Save sheet" :on-click #(do
                                                                                     (dump-nodes-to-file)
                                                                                     (println "All nodes saved to" magic-sheet-file-name))}
                                                    {:text "Quit" :on-click #(do
                                                                               (println "Bye bye")
                                                                               (System/exit 0))}])))
    
    (alter-var-root #'main-pane (constantly (doto (Pane.)
                                              (.setStyle styles/backpane))))

    (let [scene (-> (SceneBuilder/create)
                    (.height 500)
                    (.width 500)
                    (.root main-pane)
                    (.build))]
      (doto scene
        (.setOnKeyPressed (event-handler
                           [ke]
                           (let [key (.getName (.getCode ke))
                                 update-fn (->> (vals @nodes)
                                                (filter #(= (:key %) key))
                                                first
                                                :exec-fn)]
                             (when update-fn
                               (update-fn)))))) 

      (alter-var-root #'stage (constantly (-> (StageBuilder/create)
                                              (.title "Main")
                                              (.scene scene)))))
    (run-now
     (.setOnContextMenuRequested main-pane
                                 (event-handler
                                  [ev]
                                  (.show menu
                                         main-pane
                                         (.getScreenX ev)
                                         (.getScreenY ev))))
     (-> stage .build .show)
     
     (load-nodes-from-file magic-sheet-file-name))))

#_
(comment

  (run-now (add-result-node {:title "Super node"
                             :code "(user/super-test)"}))

  (run-now (add-command-node {:title "Super node"
                              :code "(user/increment-atom)"}))
  
  (run-now (-> (make-new-command-dialog true) .showAndWait println))

  (->> (-> repl-client
           (repl/message {:op :eval :code "(+ 3 4)"}))
       (filter :value)
       first
       :value)
  
  )


