(ns carmine-sentinel.core
  (:require [taoensso.carmine :as car]
            [taoensso.carmine.commands :as cmds])
  (:import (java.io EOFException)))

;; {Sentinel group -> master-name -> spec}
(defonce ^:private sentinel-resolved-specs (atom nil))
;; {Sentinel group -> specs}
(defonce ^:private sentinel-groups (volatile! nil))
;; Sentinel event listeners
(defonce ^:private sentinel-listeners (atom nil))
;; Carmine-sentinel event listeners
(defonce ^:private event-listeners (volatile! []))
;; Locks for resolving spec
(defonce ^:private locks (atom nil))

(defn- get-lock [sg mn]
  (if-let [lock (get @locks (str sg "/" mn))]
    lock
    (let [lock (Object.)
          curr @locks]
      (if (compare-and-set! locks curr (assoc curr (str sg "/" mn) lock))
        lock
        (recur sg mn)))))

(defmacro sync-on [sg mn & body]
  `(locking (get-lock ~sg ~mn)
     ~@body))

;;define commands for sentinel
(cmds/defcommand "SENTINEL get-master-addr-by-name"
  {
   :summary "get master address by master name.",
   :complexity "O(1)",
   :arguments [{:name "name",
                :type "string"}]})

(cmds/defcommand "SENTINEL slaves"
  {
   :summary "get slaves address by master name.",
   :complexity "O(1)",
   :arguments [{:name "name",
                :type "string"}]})


(cmds/defcommand "SENTINEL sentinels"
  {
   :summary "get sentinel instances by mater name.",
   :complexity "O(1)",
   :arguments [{:name "name",
                :type "string"}]})

(defn- master-role? [spec]
  (= "master"
     (first (car/wcar {:spec spec}
                      (car/role)))))

(defn- make-sure-master-role
  "Make sure the spec is a master role."
  [spec]
  (when-not (master-role? spec)
    (throw (IllegalStateException.
            (format "Spec %s is not master role." spec)))))

(defn- dissoc-in
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn notify-event-listeners [event]
  (doseq [listener @event-listeners]
    (try
      (listener event)
      (catch Exception _))))

(defn- handle-switch-master [sg msg]
  (when (= "message" (first msg))
    (let [[master-name old-ip old-port new-ip new-port]
          (clojure.string/split (-> msg nnext first)  #" ")]
      (when master-name
        ;;remove last resolved spec
        (swap! sentinel-resolved-specs dissoc-in [sg master-name])
        (notify-event-listeners {:event "+switch-master"
                                 :old {:host old-ip
                                       :port (Integer/valueOf ^String old-port)}
                                 :new {:host new-ip
                                       :port (Integer/valueOf ^String new-port)}})))))

(defn- subscribe-switch-master! [sg spec]
  (if-let [[_ listener] (get @sentinel-listeners spec)]
    (deref listener)
    (do
      (let [stop? (atom false)
            listener (atom nil)]
        (future
          (while (not @stop?)
            (try
              (->> (car/with-new-pubsub-listener (dissoc spec :timeout-ms)
                     {"+switch-master" (partial handle-switch-master sg)}
                     (car/subscribe "+switch-master"))
                   (reset! listener)
                   :future
                   (deref))
              (catch Exception _))
            (Thread/sleep 1000)))
        (->> (vector stop? listener)
             (swap! sentinel-listeners assoc spec)))
      (recur sg spec))))

(defn- unsubscribe-switch-master! [sentinel-spec]
  (try
    (when-let [[stop? listener] (get @sentinel-listeners sentinel-spec)]
      (reset! stop? true)
      (some->> @listener (car/close-listener))
      (swap! sentinel-listeners dissoc sentinel-spec))
    (catch Exception _)))

(defn- pick-specs-from-sentinel-raw-states [raw-states]
  (->> raw-states
       (map (partial apply hash-map))
       (map (fn [{:strs [ip port]}]
              {:host ip
               :port (Integer/valueOf ^String port)}))))

(defn- subscribe-all-sentinels [sentinel-group master-name]
  (when-let [old-sentinel-specs (not-empty (get-in @sentinel-groups [sentinel-group :specs]))]
    (let [valid-specs (->> old-sentinel-specs
                           (mapv #(try (-> (car/wcar {:spec %}
                                                     (sentinel-sentinels master-name))
                                           (pick-specs-from-sentinel-raw-states)
                                           (conj %))
                                       (catch Exception _
                                         [])))
                           (flatten)
                           ;; remove duplicate sentinel spec
                           (set))
          invalid-specs (remove valid-specs old-sentinel-specs)]
      (doseq [spec valid-specs]
        (subscribe-switch-master! sentinel-group spec))

      (vswap! sentinel-groups assoc-in [sentinel-group :specs]
              ;; still keep the invalid specs but append them to tail
              (vec (concat valid-specs invalid-specs)))

      ;; convert sentinel spec list to vector to take advantage of their order later
      (-> valid-specs
          (vec)
          (not-empty)))))

(defn- try-resolve-master-spec [specs sg master-name]
  (let [sentinel-spec (first specs)]
    (try
      (when-let [[master slaves]
                 (car/wcar {:spec sentinel-spec} :as-pipeline
                           (sentinel-get-master-addr-by-name master-name)
                           (sentinel-slaves master-name))]
        (let [master-spec {:host (first master)
                           :port (Integer/valueOf ^String (second master))}
              slaves (pick-specs-from-sentinel-raw-states slaves)]
          (make-sure-master-role master-spec)
          (swap! sentinel-resolved-specs assoc-in [sg master-name]
                 {:master master-spec
                  :slaves slaves})
          (make-sure-master-role master-spec)
          (notify-event-listeners {:event "get-master-addr-by-name"
                                   :sentinel-group sg
                                   :master-name master-name
                                   :master master
                                   :slaves slaves})
          [master-spec slaves]))
      (catch Exception e
        (swap! sentinel-resolved-specs dissoc-in [sg master-name])
        (notify-event-listeners
         {:event "error"
          :sentinel-group sg
          :master-name master-name
          :sentinel-spec sentinel-spec
          :exception e})
        ;;Close the listener
        (unsubscribe-switch-master! sentinel-spec)
        nil))))

(defn- choose-spec [mn master slaves prefer-slave? slaves-balancer]
  (when (= :error master)
    (throw (IllegalStateException.
            (str "Specs not found by master name: " mn))))
  (if (and prefer-slave? (seq slaves))
    (slaves-balancer slaves)
    master))

(defn- ask-sentinel-master [sg master-name
                            {:keys [prefer-slave? slaves-balancer]}]
  (if-let [all-specs (subscribe-all-sentinels sg master-name)]
    (loop [specs all-specs
           tried-specs []]
      (if (seq specs)
        (if-let [[ms sls] (try-resolve-master-spec specs sg master-name)]
          (do
            ;;Move the sentinel instance to the first position of sentinel list
            ;;to speedup next time resolving.
            (vswap! sentinel-groups assoc-in [sg :specs]
                   (vec (concat specs tried-specs)))
            (choose-spec master-name ms sls prefer-slave? slaves-balancer))
          ;;Try next sentinel
          (recur (next specs)
                 (conj tried-specs (first specs))))
        ;;Tried all sentinel instancs, we don't get any valid specs
        ;;Set a :error mark for this situation.
        (do
          (swap! sentinel-resolved-specs assoc-in [sg master-name]
                 {:master :error
                  :slaves :error})
          (notify-event-listeners {:event "get-master-addr-by-name"
                                   :sentinel-group sg
                                   :master-name master-name
                                   :master :error
                                   :slaves :error})
          (throw (IllegalStateException.
                  (str "Specs not found by master name: " master-name))))))
    (throw (IllegalStateException.
            (str "Missing specs for sentinel group: " sg)))))

;;APIs
(defn remove-invalid-resolved-master-specs!
  "Iterate all the resolved master specs and remove any invalid
   master spec found by checking role on redis.
   Please call this periodically to keep safe."
  []
  (doseq [[group-id resolved-specs] @sentinel-resolved-specs]
    (doseq [[master-name master-specs] resolved-specs]
      (try
        (when-not (master-role? (:master master-specs))
          (swap! sentinel-resolved-specs dissoc-in [group-id master-name]))
        (catch EOFException _
          (swap! sentinel-resolved-specs dissoc-in [group-id master-name]))))))

(defn register-listener!
  "Register listener for switching master.
  The listener will be called with an event:
    {:event \"+switch-master\"
     :old {:host old-master-ip
           :port old-master-port
     :new {:host new-master-ip
           :port new-master-port}}}
  "
  [listener]
  (vswap! event-listeners conj listener))

(defn unregister-listener!
  "Remove the listener for switching master."
  [listener]
  (vswap! event-listeners remove (partial = listener)))

(defn get-sentinel-redis-spec
  "Get redis spec by sentinel-group and master name.
   If it is not resolved, it will query from sentinel and
   cache the result in memory.
   Recommend to call this function at your app startup  to reduce
   resolving cost."
  [sg master-name {:keys [prefer-slave? slaves-balancer]
                   :or {prefer-slave? false
                        slaves-balancer first}
                   :as opts}]
  (when (nil? sg)
    (throw (IllegalStateException. "Missing sentinel-group.")))
  (when (empty? master-name)
    (throw (IllegalStateException. "Missing master-name.")))
  (if-let [ret (get-in @sentinel-resolved-specs [sg master-name])]
    (if-let [s (choose-spec master-name
                            (:master ret)
                            (:slaves ret)
                            prefer-slave?
                            slaves-balancer)]
      s
      (throw (IllegalStateException. (str "Spec not found: "
                                          sg
                                          "/"
                                          master-name
                                          ", "
                                          opts))))
    ;;Synchronized on [sg master-name] lock
    (sync-on sg master-name
             ;;Double checking
             (if (nil? (get-in @sentinel-resolved-specs [sg master-name]))
               (ask-sentinel-master sg master-name opts)
               (get-sentinel-redis-spec sg master-name opts)))))

(defn set-sentinel-groups!
  "Configure sentinel groups, it will replace current conf:
   {:group-name {:specs [{ :host host
                          :port port
                          :password password
                          :timeout-ms timeout-ms },
                         ...other sentinel instances...]
                 :pool {<opts>}}}
  The conf is a map of sentinel group to connection spec."
  [conf]
  (doseq [[_ group-conf] @sentinel-groups]
    (doseq [spec (:specs group-conf)]
      (unsubscribe-switch-master! spec)))
  (vreset! sentinel-groups conf))

(defn add-sentinel-groups!
  "Add sentinel groups,it will be merged into current conf:
   {:group-name {:specs  [{ :host host
                          :port port
                          :password password
                          :timeout-ms timeout-ms },
                          ...other sentinel instances...]
                 :pool {<opts>}}}
  The conf is a map of sentinel group to connection spec."
  [conf]
  (vswap! sentinel-groups merge conf))

(defn remove-sentinel-group!
  "Remove a sentinel group configuration by name."
  [group-name]
  (doseq [sentinel-spec (get-in @sentinel-groups [group-name :specs])]
    (unsubscribe-switch-master! sentinel-spec))
  (vswap! sentinel-groups dissoc group-name))

(defn remove-last-resolved-spec!
  "Remove last resolved master spec by sentinel group and master name."
  [sg master-name]
  (swap! sentinel-resolved-specs dissoc-in [sg master-name]))

(defn update-conn-spec
  "Cast a carmine-sentinel conn to carmine raw conn spec.
   It will resolve master from sentinel first time,then cache the result in
   memory for reusing."
  [conn]
  (if (and (:sentinel-group conn) (:master-name conn))
    (update conn
            :spec
            merge
            (get-sentinel-redis-spec (:sentinel-group conn)
                                     (:master-name conn)
                                     conn))
    conn))

(defmacro wcar
  "It's the same as taoensso.carmine/wcar, but supports
      :master-name \"mymaster\"
      :sentinel-group :default
   in conn for redis sentinel cluster.
  "
  {:arglists '([conn :as-pipeline & body] [conn & body])}
  [conn & sigs]
  `(car/wcar
    (update-conn-spec ~conn)
    ~@sigs))

(comment
  (set-sentinel-groups!
   {:group1
    {:specs [{:host "127.0.0.1" :port 5000} {:host "127.0.0.1" :port 5001} {:host "127.0.0.1" :port 5002}]}})
  (let [server1-conn {:pool {} :spec {} :sentinel-group :group1 :master-name "mymaster"}]
    (println
     (wcar server1-conn
           (car/set "a" 100)
           (car/get "a")))))
