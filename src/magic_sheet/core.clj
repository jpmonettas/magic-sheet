(ns magic-sheet.core
  (:require [nrepl.core :as repl]
            [clojure.core.async :as async]
            [magic-sheet.utils :refer [run-later run-now event-handler]])
  (:import [javafx.scene SceneBuilder]
           [javafx.scene.control Button
                                 TableView
                                 TableColumn
                                 ContextMenu
                                 MenuItem
                                 Dialog
                                 ButtonType
                                 Label
                                 TextArea
                                 TextField
                                 ComboBox]
           [javafx.scene.text Text]
           [javafx.scene.layout BorderPane Pane VBox HBox GridPane]
           [javafx.stage StageBuilder Modality]
           [javafx.scene Node Cursor]
           [javafx.collections FXCollections]
           [javafx.util Callback]
           [javafx.beans.value ObservableValue]
           [javafx.beans.property ReadOnlyObjectWrapper]
           [java.util UUID]
           [utils DragResizeMod]
           [javafx.geometry Insets])
  (:gen-class))

(defonce force-toolkit-init (javafx.embed.swing.JFXPanel.))

(def main-pane (Pane.))

(def stage (-> (StageBuilder/create)
               (.title "Main")
               (.scene (-> (SceneBuilder/create)
                           (.height 500)
                           (.width 500)
                           (.root main-pane)
                           (.build)))))

(def repl-connection (repl/connect :port 40338))
(def repl-client (repl/client repl-connection 5000))

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
        table-columns (->> cols
                           (map (fn [c]
                                  (doto (TableColumn. (str c))
                                    (.setCellValueFactory (reify Callback
                                                            (call [this v]
                                                              (ReadOnlyObjectWrapper. (get (.getValue v) c)))))))))
        table (doto (TableView. obs-list)
                (.setColumnResizePolicy TableView/CONSTRAINED_RESIZE_POLICY))]
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

(defn make-node-ui [{:keys [title on-close sub-bar child]}]
  (let [title-txt (Text. title)
        
        close-btn (Button. "X")
        bar (doto (HBox. (into-array Node (cond-> []
                                            title (conj title-txt)
                                            sub-bar   (conj sub-bar)
                                            true  (conj close-btn))))
              (.setStyle "-fx-background-color: orange;"))
        bar2 (doto (BorderPane.)
               (.setLeft title-txt)
               (.setCenter sub-bar)
               (.setRight close-btn))
        main-box (doto (VBox. (into-array Node [bar2 child]))
                   (.setStyle "-fx-background-color: red; -fx-padding: 10;"))]

    (doto close-btn
      (.setOnAction (event-handler [_] (on-close main-box))))

    (DragResizeMod/makeResizable main-box)
    
    main-box))

(defn remove-node [n]
  (-> main-pane
      .getChildren
      (.remove n)))


(def nodes (atom {}))

(defn eval-on-repl [code]
  (->> (repl/message repl-client {:op :eval :code code})
       (filter :value)
       first :value))

(defn add-result-node [{:keys [title code result-type]}]
  (let [update-fn (fn []
                    (binding [*default-data-reader-fn* str]
                      (when-let [v (eval-on-repl code)]
                        (read-string v))))
        ret-val (update-fn)
        {:keys [result-node data-model]} (make-table-ui ret-val)
        result-ui-bar (make-result-ui-bar {:on-update (fn []
                                                        (doto data-model
                                                          (.clear)
                                                          (.addAll (into-array Object (update-fn)))))})
        node-id (str (UUID/randomUUID))
        node (make-node-ui {:title    title
                            :on-close (fn [n]
                                        (swap! nodes dissoc node-id)
                                        (remove-node n))
                            :sub-bar      result-ui-bar
                            :child    result-node})]
    (swap! nodes assoc node-id {:data-model data-model
                                :update-fn  update-fn
                                :code       code})
    (-> main-pane
        .getChildren
        (.add node))))

(defn add-command-node [{:keys [title code] :as args}]
  (prn "Adding for " args)
  (let [update-fn (fn []
                    (binding [*default-data-reader-fn* str]
                      (when-let [v (eval-on-repl code)]
                        (read-string v))))
        node-id (str (UUID/randomUUID))
        eval-btn (Button. title)
        node (make-node-ui {:on-close (fn [n]
                                        (swap! nodes dissoc node-id)
                                        (remove-node n))
                            :child    eval-btn})]
    (swap! nodes assoc node-id {:update-fn  update-fn
                                :code       code})
    (doto eval-btn
      (.setOnAction (event-handler [_] (update-fn))))
    (-> main-pane
        .getChildren
        (.add node))))

(defn make-new-command-dialog [ask-for-result?]
  (let [d (doto (Dialog.)
            (.setTitle "New command")
            (.initModality Modality/APPLICATION_MODAL)
            (.setResizable false)
            (.setWidth 620)
            (.setHeight 285))
        
        title-input (TextField.)
        code-txta (TextArea.)
        type-combo (ComboBox.)
        grid-pane (doto (GridPane.)
                    (.setHgap 10)
                    (.setVgap 10)
                    (.add (Label. "Title:")      0 0)
                    (.add title-input            1 0)
                    (.add (Label. "Code:")       0 1)
                    (.add code-txta              1 1))
        result-type-options {"Result as table" :as-table
                             "Result as value" :as-value
                             "Resutl as tree"  :as-tree}]

    (when ask-for-result?
      (doto grid-pane
        (.add (Label. "Result as:")  0 2)
        (.add type-combo             1 2)))
    
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
                                 (cond-> {:title       (.getText title-input)
                                          :code        (.getText code-txta)}
                                   ask-for-result? (assoc :result-type (result-type-options (.getValue type-combo))))))))
    d))


(def menu (make-context-menu [{:text "New command" :on-click #(-> (make-new-command-dialog false)
                                                                  .showAndWait
                                                                  .get 
                                                                  add-command-node)}
                              {:text "New command for result" :on-click #(-> (make-new-command-dialog true)
                                                                             .showAndWait
                                                                             .get
                                                                             add-result-node)}
                              {:text "Save sheet" :on-click #(println "Saving all!")}
                              {:text "Quit" :on-click #(println "Bye bye")}]))


(doto main-pane
  (.setOnContextMenuRequested (event-handler
                               [ev]
                               (.show menu
                                      main-pane
                                      (.getScreenX ev)
                                      (.getScreenY ev)))))
#_
(comment

  (run-now (when-let [s @stage] (.close s)))
  
  (run-now (add-result-node {:title "Super node"
                             :code "(user/super-test)"}))

  (run-now (add-command-node {:title "Super node"
                              :code "(user/increment-atom)"}))
  
  (run-now (-> stage .build .show))

  (run-now (-> (make-new-command-dialog true) .showAndWait println))
 
  (doall 
   (pmap (fn [_]
           (->> (-> repl-client
                    (repl/message {:op :eval :code "(user/test)"})
                    doall)
                (filter :value)
                first
                :value))
         (range 5)))
 )

(defn -main
  ""
  [& args]
  
  )
