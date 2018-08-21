(ns magic-sheet.core
  (:require [clojure.core.async :as async]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [magic-sheet.utils :refer [event-handler run-now]]
            [nrepl.core :as repl]
            [clojure.edn :as edn])
  (:import java.util.UUID
           [javafx.animation Animation KeyFrame KeyValue Timeline]
           javafx.beans.property.ReadOnlyObjectWrapper
           javafx.collections.FXCollections
           [javafx.scene Node SceneBuilder]
           [javafx.scene.control Button ButtonType ComboBox ContextMenu Dialog Label MenuItem TableColumn TableView TextArea TextField]
           javafx.scene.effect.DropShadow
           [javafx.scene.input Clipboard DataFormat]
           [javafx.scene.layout BorderPane GridPane HBox Pane VBox]
           javafx.scene.paint.Color
           javafx.scene.text.Text
           [javafx.stage Modality StageBuilder]
           [javafx.util Callback Duration]
           [utils DragResizeMod DragResizeMod$OnDragResizeEventListener CellUtils] 
           [java.io File])
  (:gen-class))

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
  (let [nodes-str (->> @nodes
                       (map (fn [[nid n]]
                              [nid (dissoc n :update-fn)]))
                       (into {})
                       pr-str)]
    (spit magic-sheet-file-name )))

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

(defn make-table-ui [data]
  (assert (seq? data) (str "Data is not a sequence " (type data)))
  (assert (every? map? data) "Sequence elements should be maps")
  
  (let [obs-list (FXCollections/observableArrayList data)
        cols (->> data first keys)
        table (TableView. obs-list)
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
    (-> table
        .getColumns
        (.addAll (into-array TableColumn table-columns)))
    {:result-node table
     :data-model obs-list}))

(defn make-result-ui-bar [{:keys [on-update]}]
  (let [update-btn (Button. "[r]")
        bar (doto (HBox. (into-array Node [update-btn])))
        main-box (doto (VBox. (into-array Node [bar]))
                   (.setStyle "-fx-background-color: green; -fx-padding: 10;"))]
    
    (doto update-btn
      (.setOnAction (event-handler [_] (on-update))))
    
    main-box))

(defn make-node-ui [{:keys [node-id title on-close x y w h key]
                     :or {x 0 y 0 h 100 w 100}}]
  (let [title-txt (Text. (format "[%s] %s" key title))
        close-btn (Button. "X")
        sub-bar (HBox.)
        bar (doto (BorderPane.)
              (.setLeft title-txt)
              (.setCenter sub-bar)
              (.setRight close-btn))
        main-box (doto (VBox. (into-array Node [bar]))
                   (.setStyle "-fx-background-color: red; -fx-padding: 10;")
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
     :sub-bar-pane sub-bar
     :x x :y y :w w :h h}))

(defn remove-node [n]
  (-> main-pane
      .getChildren
      (.remove n)))

(defn eval-on-repl [code]
  (->> (repl/message repl-client {:op :eval :code code})
       (filter :value)
       first :value))

(defn make-table-result-node [ret-val generic-update-fn]
  (let [{:keys [result-node data-model]} (make-table-ui ret-val)
        update-fn (fn []
                    (generic-update-fn
                     (fn [v]
                       (doto data-model
                         (.clear)
                         (.addAll (into-array Object v))))))]
    {:result-node result-node
     :result-ui-bar (make-result-ui-bar {:on-update update-fn})
     :update-fn update-fn}))

(defn make-text-result-node [ret-val generic-update-fn]
  (let [ta (doto (TextArea.)
             (.setEditable false))
        format-and-set (fn [v]
                         (doto ta
                           (.setText (with-out-str
                                       (pprint/pprint v)))))
        update-fn (fn []
                    (generic-update-fn format-and-set))]
    (format-and-set ret-val)
    {:result-node ta
     :result-ui-bar (make-result-ui-bar {:on-update update-fn})
     :update-fn update-fn}))

(defn make-tree-result-node [ret-val update-fn]
  {:result-node (Text. "Sowing result as tree not implemented yet for val ")})

(defn make-executing-animation [node]
  (let [animation-min 1000
        animation-started (atom nil)
        shadow (doto (DropShadow.)
                 (.setColor (Color/BLUE))
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

(defn add-result-node [{:keys [title code x y w h result-type node-id key] :as node}]
  (let [{:keys [node sub-bar-pane x y w h]} (make-node-ui (merge node
                                                                 {:on-close (fn [n]
                                                                              (remove-node-from-store! node-id)
                                                                              (remove-node n))}))
        {:keys [start-animation stop-animation]} (make-executing-animation node)
        generic-update-fn (fn [update-ui]
                    (binding [*default-data-reader-fn* str]
                      (start-animation)
                      (-> (async/thread
                            (when-let [v (eval-on-repl code)]
                              (stop-animation)
                              (read-string v)))
                          (async/take! (fn [v]
                                         (stop-animation)
                                         (update-ui v))))))]

    (generic-update-fn (fn [ret-val]
                         (run-now
                          (let [{:keys [result-node result-ui-bar update-fn]} (case result-type
                                                                                :as-table (make-table-result-node ret-val generic-update-fn)
                                                                                :as-value (make-text-result-node ret-val generic-update-fn)
                                                                                :as-tree  (make-tree-result-node ret-val generic-update-fn))]
                            (-> sub-bar-pane .getChildren (.add result-ui-bar))
                            
                            (-> node .getChildren (.add result-node))

                            (-> main-pane
                                .getChildren
                                (.add node))
                            
                            (store-node! {:node-id node-id
                                          :title title
                                          :type :for-result
                                          :result-type result-type
                                          :code code
                                          :x x
                                          :y y
                                          :w w
                                          :h h
                                          :key key
                                          :update-fn update-fn})))))))

(defn param-names [code]
  (->> (re-seq #"\$\{(.+?)\}" code)
       (map second)))

(defn render-code [code-template params-value-map]
  (reduce (fn [r [p v]]
            (str/replace r (format "${%s}" p) (str v)))
          code-template
          params-value-map))

(defn make-params-box [inputs]
  (VBox. (into-array Node (map (fn [[pname tf]] (HBox. (into-array Node [(Label. pname) tf]))) inputs))))

(defn add-command-node [{:keys [title code x y w h node-id key] :as node}]
  (let [eval-btn (Button. (format "[%s] %s" key title))
        input-params (param-names code)
        {:keys [node x y w h]} (make-node-ui (merge (dissoc node :title)
                                                    {:on-close (fn [n]
                                                                 (remove-node-from-store! node-id)
                                                                 (remove-node n))}))
        inputs (->> input-params
                    (map (fn [p] [p (TextField.)]))
                    (into {}))
        {:keys [start-animation stop-animation]} (make-executing-animation node)
        update-fn (fn []
                    (start-animation)
                    (-> (async/thread
                          (let [params-values (map (fn [[p tf]] [p (.getText tf)]) inputs)]
                           (eval-on-repl (render-code code params-values))))
                        (async/take! (fn [_] (stop-animation)))))]

    (-> node .getChildren (.addAll (into-array Node [eval-btn (make-params-box inputs)])))
    
    (doto eval-btn
      (.setOnAction (event-handler [_] (update-fn))))
    
    (-> main-pane
        .getChildren
        (.add node))

    (store-node! {:node-id node-id
                  :title title
                  :type :for-command
                  :code code
                  :x x
                  :y y
                  :w w
                  :h h
                  :key key
                  :update-fn update-fn})))

(defn make-new-command-dialog [ask-for-result?]
  (let [node-id (str (UUID/randomUUID))
        d (doto (Dialog.)
            (.setTitle "New command")
            (.initModality Modality/APPLICATION_MODAL)
            (.setResizable false)
            (.setWidth 620)
            (.setHeight 285))
        
        title-input (TextField.)
        key-input (TextField.)
        code-txta (TextArea.)
        type-combo (ComboBox.)
        grid-pane (doto (GridPane.)
                    (.setHgap 10)
                    (.setVgap 10)
                    (.add (Label. "Title:")      0 0)
                    (.add title-input            1 0)
                    (.add (Label. "Code:")       0 1)
                    (.add code-txta              1 1)
                    (.add (Label. "Key:")        0 2)
                    (.add key-input              1 2))
        result-type-options {"Result as table" :as-table
                             "Result as value" :as-value
                             "Resutl as tree"  :as-tree}]

    (when ask-for-result?
      (doto grid-pane
        (.add (Label. "Result as:")  0 3)
        (.add type-combo             1 3)))
    
    (-> type-combo
        .getItems
        (.addAll (into-array String (keys result-type-options))))
    (-> d
        .getDialogPane
        .getButtonTypes
        (.addAll (into-array Object [ButtonType/OK ButtonType/CANCEL])))
    (-> d
        .getDialogPane
        (.setContent grid-pane))

    (.setResultConverter d (reify Callback
                             (call [this button]
                               (when (= "OK" (.getText button))
                                 (cond-> {:node-id node-id
                                          :title   (.getText title-input) 
                                          :code    (.getText code-txta)
                                          :key     (str/upper-case (first (.getText key-input)))}
                                   ask-for-result? (assoc :result-type (result-type-options (.getValue type-combo))))))))
    d))

(defn load-nodes-from-file [file]
  (if (.exists (File. file))
   (let [loaded-nodes (->> (slurp file)
                           edn/read-string
                           vals)]
     (doseq [{:keys [type] :as n} loaded-nodes]
       (case type
         :for-command (add-command-node n)
         :for-result (add-result-node n))))
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
                    (constantly (make-context-menu [{:text "New command" :on-click #(-> (make-new-command-dialog false)
                                                                                        .showAndWait
                                                                                        .get 
                                                                                        add-command-node)}
                                                    {:text "New command for result" :on-click #(-> (make-new-command-dialog true)
                                                                                                   .showAndWait
                                                                                                   .get
                                                                                                   add-result-node)}
                                                    {:text "Save sheet" :on-click #(do
                                                                                     (dump-nodes-to-file)
                                                                                     (println "All nodes saved to" magic-sheet-file-name))}
                                                    {:text "Quit" :on-click #(do
                                                                               (println "Bye bye")
                                                                               (System/exit 0))}])))
    
    (alter-var-root #'main-pane (constantly (Pane.)))

    (let [scene (-> (SceneBuilder/create)
                    (.height 500)
                    (.width 500)
                    (.root main-pane)
                    (.build))]
      (doto scene
        (.setOnKeyPressed (event-handler
                           [ke]
                           (let [key (.getName (.getCode ke))
                                 _ (println "Looking for " key)
                                 update-fn (->> (vals @nodes)
                                                (filter #(= (:key %) key))
                                                first
                                                :update-fn)]
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


