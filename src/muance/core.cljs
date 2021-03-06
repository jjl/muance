(ns muance.core
  (:refer-clojure :exclude [remove key])
  (:require [goog.object :as o]))

(def ^{:private true} index-typeid 0)
(def ^{:private true} index-parent-vnode 1)
(def ^{:private true} index-node 2)
(def ^{:private true} index-component 3)
(def ^{:private true} index-children-count 4)
(def ^{:private true} index-children 5)
(def ^{:private true} index-attrs 6)
;; Unmount is stored on the node since it must be called when one of the parents of the node
;; is removed
(def ^{:private true} index-unmount 7)
(def ^{:private true} index-key 8)
;; A slot which stores one of two flags:
;; - moved-flag
;; - moving-flag
;; See the documentation for these two flags for more details
(def ^{:private true} index-key-moved 9)
(def ^{:private true} index-keymap 10)
;; When a keyed node is removed, the keymap is marked as invalid. Invalid keymaps are
;; cleaned when the close function of the node is called
(def ^{:private true} index-keymap-invalid 11)

(def ^{:private true} index-text 3)

;; component specific data
(def ^{:private true} index-comp-data 2)
(def ^{:private true} index-comp-props 3)
(def ^{:private true} index-comp-state 6)

(def ^{:private true} index-comp-data-name 0)
(def ^{:private true} index-comp-data-state-ref 1)
(def ^{:private true} index-comp-data-svg-namespace 2)
;; index-in-parent is used when rendering a component after its local state has changed.
;; we must initialize the children-count slot to the same value than index-in-parent
(def ^{:private true} index-comp-data-index-in-parent 3)
;; the depth of the component is stored to be able to init the component-state var when a
;; component is re-rendered because of a local state change
(def ^{:private true} index-comp-data-depth 4)
(def ^{:private true} index-comp-data-dirty-flag 5)

(def ^{:private true} index-render-queue-async 0)
(def ^{:private true} index-render-queue-dirty-flag 1)

;; The data needed in a local state watcher is stored on the local state itself, using this key.
(def ^{:private true} vnode-stateful-key "muance.core/vnode-stateful")

(def ^{:dynamic true :private true} *component* nil)
;; Whether the current vnode has just been created or not
(def ^{:dynamic true :private true} *new-node* nil)
(def ^{:dynamic true :private true} *attrs-count* nil)
;; Set to the value of the moved node when a moved node is met, unless if was already set before
;; Thus it keeps the value of the higher moved node in the tree, even if child nodes are
;; themselves moved. This is necessary to know when to unset the value 
(def ^{:dynamic true :private true} *moved-vnode* nil)
;; used to handle a edge case when open-impl returns two vnodes to be removed.
;; See the invalid states handling in open-impl
(def ^{:dynamic true :private true} *vnode-to-remove* nil)
(def ^{:dynamic true :private true} *props* nil)
;; Whether to skip a component body or not, depending on whether its props and state has changed
;; or not
(def ^{:dynamic true :private true} *skip* nil)
;; component-depth is used to be able to always render components top -> down. Rendering
;; components top->down avoids unecessary diffing sometimes
(def ^{:dynamic true :private true} *component-depth* nil)
;; Used to avoid re-rendering when a state update is done from a will-receive-props hook
(def ^{:dynamic true :private true} *watch-local-state* true)
(def ^{:dynamic true :private true} *components-queue-count* nil)
;; Components that need to be re-rendered are stored in the render-queue
(def ^{:dynamic true :private true} *render-queue* nil)
;; incremented on svg open, decremented on svg close, reseted to 0 on foreignObject open,
;; previous value restored on foreignObject close
(def ^{:dynamic true :private true} *svg-namespace* nil)

;; Nodes that moved because of child nodes reconciliation
;; are marked with this flag. This is useful to detect an attempt to move an already moved node,
;; a situation which can happen when duplicate keys are met.
;; This flag changes on every render pass because this avoids the need to clean the flag when
;; cleaning a keymap.
(defonce ^{:private true} moved-flag nil)
;; Nodes are marked as "moving" during child node reconciliation when they get removed. We mark
;; them as moving because a removed node may be a node that is in fact moving further. So we
;; mark it as moving but keep it in the keymap so it can be added back later. If it is added
;; back later (the node moved further), then we clean the moving-flag, else (if the node really
;; is a removed node) the node is really removed when cleaning the keymap
(defonce ^{:private true} moving-flag #js [])
;; Set on a component "props" slot when this component does not have props. This is useful to
;; differentiate between "nil" props and no props at all. When a component does not have props,
;; no props are passed to the patch function when it is re-rendered.
(defonce ^{:private true} no-props-flag #js [])
;; Used to enqueue components with a did-mount / will-unmount hook, and then call the hooks
;; in order
(def ^{:private true} components-queue #js [])

(def svg-ns "http://www.w3.org/2000/svg")
(def xml-ns "http://www.w3.org/XML/1998/namespace")
(def xlink-ns "http://www.w3.org/1999/xlink")

(def ^:dynamic *state* "The local state value of the current component." nil)
(def ^:dynamic *vnode* "The current virtual node, or component." nil)

(declare process-render-queue)

(defn- component? [vnode]
  (< (aget vnode index-typeid) 0))

(defn- dirty-component? [vnode]
  (aget vnode index-comp-data index-comp-data-dirty-flag))

(defn component-name
  "Return the fully qualified name of the node's component, as a string."
  [vnode]
  (assert vnode "muance.core/component-name expects a vnode.")
  (if (component? vnode)
    (aget vnode index-comp-data index-comp-data-name)
    (aget vnode index-component index-comp-data index-comp-data-name)))

(defn moving?
  "Whether a node or one of its parent is being moved during child nodes reconciliation, or not.
  Use this function to handle the potential side effect of moving a DOM node, such as loss of
  focus state."
  [vnode]
  (assert vnode "muance.core/moving? expects a vnode.")
  (boolean *moved-vnode*))

(defn- dom-nodes* [acc vnode]
  (if (component? vnode)
    (when-let [children (aget vnode index-children)]
      (let [l (.-length children)]
        (loop [i 0]
          (when (< i l)
            (dom-nodes* acc (aget children i))
            (recur (inc i))))))
    (.push acc (aget vnode index-node)))
  acc)

(declare ref-node-down)

(defn dom-nodes
  "Return an javascript array of all the DOM nodes associated with vnode."
  [vnode]
  (assert vnode "muance.core/dom-nodes expects a vnode.")
  (if (component? vnode)
    (dom-nodes* #js [] vnode)
    #js [(aget vnode index-node)]))

(defn dom-node
  "Return the DOM nodes associated with vnode. Returns the first children of vnode if vnode is
  a component and is associated with multiple DOM nodes."
  [vnode]
  (assert vnode "muance.core/dom-node expects a vnode.")
  (if (component? vnode)
    (ref-node-down vnode)
    (aget vnode index-node)))

(defn key
  "Returns the :muance.core/key attribute of vnode, as a string."
  [vnode]
  (assert vnode "muance.core/key expects a vnode.")
  (aget vnode index-key))

(defn- remove-vnode-key [vnode key]
  (let [parent (aget vnode index-parent-vnode)]
    (aset vnode index-key-moved moving-flag)
    (aset parent index-keymap-invalid
          (inc (aget parent index-keymap-invalid)))))

(defn- on-state-change [k r o n]
  (when *watch-local-state*
    (let [stateful-data (o/get r vnode-stateful-key)
          vnode (aget stateful-data 0)
          comp-fn (aget stateful-data 1)
          render-queue (aget stateful-data 2)
          async (aget render-queue index-render-queue-async)
          component-depth (aget vnode index-comp-data index-comp-data-depth)]
      (when (not (dirty-component? vnode))
        (aset (aget vnode index-comp-data) index-comp-data-dirty-flag true)
        (if-let [dirty-comps (aget render-queue (inc component-depth))]
          (do (.push dirty-comps comp-fn)
              (.push dirty-comps vnode))
          (aset render-queue (inc component-depth) #js [comp-fn vnode]))
        (when-not (aget render-queue index-render-queue-dirty-flag)
          (aset render-queue index-render-queue-dirty-flag true)
          (if async
            (.requestAnimationFrame js/window
                                    (fn []
                                      (process-render-queue render-queue)))
            (process-render-queue render-queue)))))))

(defn- remove-real-node [vnode]
  (if (component? vnode)
    (when-let [children (aget vnode index-children)]
      (let [l (.-length children)]
        (loop [i 0]
          (when (< i l)
            (remove-real-node (aget children i))
            (recur (inc i))))))
    (let [node (aget vnode index-node)]
      (when-let [p (.-parentNode node)]
        (.removeChild p node)))))

(defn- enqueue-unmounts [vnode]
  (when (component? vnode)
    (remove-watch (aget vnode index-comp-data index-comp-data-state-ref) ::component))
  (when (aget vnode index-unmount)
      (aset components-queue *components-queue-count* vnode)
      (set! *components-queue-count* (inc *components-queue-count*)))
  (when-let [children (aget vnode index-children)]
    (let [children-count (.-length children)]
      (loop [i 0]
        (when (< i children-count)
          (let [child (aget vnode index-children i)]            
            (enqueue-unmounts child))
          (recur (inc i)))))))

(defn- call-unmounts [queue-start]
  (loop [i (dec *components-queue-count*)]
    (when (>= i queue-start)
      (let [vnode (aget components-queue i)
            component (if (component? vnode) vnode (aget vnode index-component))
            props (aget component index-comp-props)
            state (aget component index-comp-state)]
        ;; *vnode* is rebound in remove-node
        (set! *vnode* vnode)
        ((aget vnode index-unmount) props state))
      (recur (dec i))))
  (set! *components-queue-count* queue-start))

(defn- remove-node [vnode]
  (let [current-vnode *vnode*
        queue-start *components-queue-count*]
    (enqueue-unmounts vnode)
    (call-unmounts queue-start)
    (set! *vnode* current-vnode))
  (remove-real-node vnode))

(defn- clean-keymap [vnode]
  (let [keymap-invalid (or (aget vnode index-keymap-invalid) 0)]
    (when (> keymap-invalid 0)
      (let [keymap (aget vnode index-keymap)]
        (o/forEach keymap
                   (fn [v k]
                     (when (identical? (aget v index-key-moved) moving-flag)
                       (remove-node v)
                       (o/remove keymap k)))))
      (aset vnode index-keymap-invalid 0))))

(defn- clean-children [vnode]
  (when-let [children (aget vnode index-children)]
    (let [children-count (aget vnode index-children-count)
          children-length (.-length children)]
      (loop [l children-length]
        (when (> l children-count)
          (let [removed-vnode (.pop children)
                k (aget removed-vnode index-key)]
            (if k
              (remove-vnode-key removed-vnode key)
              (remove-node removed-vnode)))
          (recur (dec l)))))))

(defn- set-attribute [node ns key val]
  (if (nil? val)
    (.removeAttribute node key)
    (if (nil? ns)
      (.setAttribute node key val)
      (.setAttributeNS node ns key val))))

(defn- set-property [node ns key val]
  (o/set node key val))

(defn- set-input-value [node ns key val]
  (when (not= (o/get node key) val)
    (o/set node key val)))

(defn- set-style [node ns key val]
  (o/set (.-style node) key val))

(defn- set-style-custom [node ns key val]
  (.setProperty (.-style node) key val))

(defn- init-keymap [keymap]
  (aset *vnode* index-keymap keymap)
  (aset *vnode* index-keymap-invalid 0)
  keymap)

(defn- new-vnode [typeid element]
  #js [typeid *vnode* element])

(defn- new-vnode-key [typeid element keymap key]
  (let [keymap (if (nil? keymap) (init-keymap #js {}) keymap)
        vnode #js [typeid *vnode* element
                   nil nil nil nil nil key moved-flag]]
    (o/set keymap key vnode)
    vnode))

(defn- new-text-vnode [element text]
  #js [0 *vnode* element text])

(defn- create-element [tag]
  ;; tag is nil when opening a component
  (when tag
    (if (> *svg-namespace* 0)
      (.createElementNS js/document svg-ns tag)
      (.createElement js/document tag))))

(defn- parent-node [parent]
  (if (component? parent)
    (recur (aget parent index-parent-vnode))
    (aget parent index-node)))

(defn- ref-node-down [vnode]
  (if (component? vnode)
    (when-let [children (aget vnode index-children)]
      (let [l (.-length children)]
        (loop [i 0]
          (when (< i l)
            (if-let [node (ref-node-down (aget children i))]
              node
              (recur (inc i)))))))
    (aget vnode index-node)))

(defn- ref-node-up [vnode]
  ;; index-children has already been incremented
  ;; children cannot be nil
  (let [children (aget vnode index-children)
        l (.-length children)
        found-node (loop [i (aget vnode index-children-count)
                          found-node nil]
                     (if found-node
                       found-node
                       (when (< i l)
                         (recur (inc i) (ref-node-down (aget children i))))))]
    (if (nil? found-node)
      (when (component? vnode)
        (recur (aget vnode index-parent-vnode)))
      found-node)))

(defn- insert-vnode-before* [parent-node vnode ref-node]
  (if (component? vnode)
    (when-let [children (aget vnode index-children)]
      (let [l (.-length children)]
        (loop [i 0]
          (when (< i l)
            (insert-vnode-before* parent-node (aget children i) ref-node)
            (recur (inc i))))))
    (.insertBefore parent-node (aget vnode index-node) ref-node)))

(defn- insert-vnode-before [parent-vnode vnode ref-vnode]
  (if (component? parent-vnode)
    (let [parent-node (parent-node parent-vnode)]
      (if-let [ref-node (when ref-vnode (ref-node-down ref-vnode))]
        (insert-vnode-before* parent-node vnode ref-node)
        (insert-vnode-before* parent-node vnode (ref-node-up parent-vnode))))
    (let [parent-node (aget parent-vnode index-node)]
      (if (nil? ref-vnode)
        (insert-vnode-before* parent-node vnode nil)
        (if-let [ref-node (ref-node-down ref-vnode)]
          (insert-vnode-before* parent-node vnode ref-node)
          (insert-vnode-before* parent-node vnode (ref-node-up parent-vnode)))))))

(defn- splice-to [nodes index moved-node to-node]
  (let [next-moved-node (aget nodes index)]
    (aset nodes index moved-node)
    (when (component? moved-node)
      (aset (aget moved-node index-comp-data) index-comp-data-index-in-parent index))
    (when (and next-moved-node (not (identical? next-moved-node to-node)))
      (recur nodes (inc index) next-moved-node to-node))))

(defn- call-will-receive-props [prev-props props state-ref will-receive-props]
  (when (and will-receive-props (not (identical? prev-props props)))
    (will-receive-props prev-props props state-ref)
    (set! *state* @state-ref)))

(defn- comp-props [vnode]
  (let [props (aget vnode index-comp-props)]
    (if (identical? props no-props-flag)
      nil props)))

;; hooks are called after open-impl to keep things consistent in case of an exception when
;; calling the hooks
(defn- open-impl [tag typeid key vnode-index]
  (let [key (when key (str key))
        parent-children (or (aget *vnode* index-children) #js [])
        prev (aget parent-children vnode-index)
        prev-key (when prev (aget prev index-key))
        prev-typeid (when prev (aget prev index-typeid))
        keymap (aget *vnode* index-keymap)]
    (aset *vnode* index-children-count (inc vnode-index))
    (when (nil? (aget *vnode* index-children))
      (aset *vnode* index-children parent-children))
    (if (and (= typeid prev-typeid) (= key prev-key))
      (do (when key
            (aset prev index-key-moved moved-flag))
          (set! *vnode* prev)
          nil)
      (let [moved-vnode (and key keymap (o/get keymap key))]
        (if (and moved-vnode
                 (= typeid (aget moved-vnode index-typeid))
                 (not (identical? moved-flag (aget moved-vnode index-key-moved))))
          (do
            (when (nil? *moved-vnode*)
              (set! *moved-vnode* moved-vnode))
            (insert-vnode-before *vnode* moved-vnode prev)
            (aset parent-children vnode-index moved-vnode)
            (if (not (identical? moving-flag (aget moved-vnode index-key-moved)))
              ;; moved-vnode is amongs the next children -> splice between the
              ;; current index and the index of the moved node
              (splice-to parent-children (inc vnode-index) prev moved-vnode)
              ;; the moved-node is coming from the previous children -> replace the node
              ;; at the current index
              (aset *vnode* index-keymap-invalid
                    (dec (aget *vnode* index-keymap-invalid))))
            (aset moved-vnode index-key-moved moved-flag)
            (set! *vnode* moved-vnode)
            prev)
          ;; this is a new node -> replace the node at the current index
          (let [vnode (if key
                        (new-vnode-key typeid (create-element tag) keymap key)
                        (new-vnode typeid (create-element tag)))]
            ;; handle invalid states
            (cond (and moved-vnode
                       (identical?
                        moved-flag (aget moved-vnode index-key-moved)))
                  (do
                    (.error js/console
                            (str "Duplicate key: " key
                                 " in component "
                                 (component-name moved-vnode)))
                    (aset moved-vnode index-key nil))
                  (and moved-vnode (not= typeid (aget moved-vnode index-typeid)))
                  (do
                    #_(.warn
                       js/console
                       (str "Nodes with same key and different typeids. key: " key))
                    (when (identical? (aget moved-vnode index-key-moved) moving-flag)
                      (aset *vnode* index-keymap-invalid
                            (dec (aget *vnode* index-keymap-invalid)))
                      (set! *vnode-to-remove* moved-vnode))
                    (aset moved-vnode index-key nil)))
            (insert-vnode-before *vnode* vnode prev)
            (aset parent-children vnode-index vnode)
            (set! *new-node* (inc *new-node*))
            (set! *vnode* vnode)
            prev))))))

(defn- open [tag typeid key will-update will-unmount]
  (assert (not (nil? *component*))
          (str "tag " tag " was called outside a render loop"))
  (let [prev (open-impl tag (or typeid tag) key
                        (or (aget *vnode* index-children-count) 0))]
    (if (> *new-node* 0)
      (do (aset *vnode* index-component *component*)
          (when prev
            (if-let [prev-key (aget prev index-key)]
              (remove-vnode-key prev prev-key)
              (remove-node prev)))
          (when *vnode-to-remove*
            (remove-node *vnode-to-remove*)
            (set! *vnode-to-remove* nil))
          (when (= tag "foreignObject")
            (set! *svg-namespace* 0)))
      (do
        (when prev
          (if-let [prev-key (aget prev index-key)]
            (remove-vnode-key prev prev-key)
            (remove-node prev)))
        (when will-update (will-update *props* *state*))
        (when (aget *vnode* index-children-count)
          (aset *vnode* index-children-count 0))
        (clean-keymap *vnode*))))
  (when (not= (aget *vnode* index-unmount) will-unmount)
    (aset *vnode* index-unmount will-unmount))
  (set! *attrs-count* 0))

(defn- close-impl [did-mount did-update]
  (clean-children *vnode*)
  (clean-keymap *vnode*)
  (if (> *new-node* 0)
    (do
      (set! *new-node* (dec *new-node*))
      (when did-mount
        (aset components-queue *components-queue-count* did-mount)
        (aset components-queue (inc *components-queue-count*) *vnode*)
        (set! *components-queue-count* (+ *components-queue-count* 2))))
    (when did-update (did-update *props* *state*)))
  (when (identical? *moved-vnode* *vnode*)
    (set! *moved-vnode* nil)))

(defn- close [did-mount did-update]
  (close-impl did-mount did-update)
  (set! *vnode* (aget *vnode* index-parent-vnode)))

(defn- text-node [t]
  (let [vnode-index (or (aget *vnode* index-children-count) 0)
        parent-children (or (aget *vnode* index-children) #js [])
        prev (aget parent-children vnode-index)
        prev-key (when prev (aget prev index-key))
        prev-typeid (when prev (aget prev index-typeid))]
    (aset *vnode* index-children-count (inc vnode-index))
    (when (nil? (aget *vnode* index-children))
      (aset *vnode* index-children parent-children))
    (if (= 0 prev-typeid)
      (when (not= (aget prev index-text) t)
        (aset prev index-text t)
        (o/set (aget prev index-node) "nodeValue" t))
      (let [vnode (new-text-vnode (.createTextNode js/document t) t)]
        (insert-vnode-before *vnode* vnode (aget parent-children vnode-index))
        (aset parent-children vnode-index vnode)
        (if prev-key
          (remove-vnode-key prev prev-key)
          (when prev (remove-node prev)))))))

(def ^{:private true} hooks-key "muance.core/hooks")

(def ^{:private true} index-hooks-get-initial-state 0)
(def ^{:private true} index-hooks-will-receive-props 1)
(def ^{:private true} index-hooks-did-mount 2)
(def ^{:private true} index-hooks-did-update 3)
(def ^{:private true} index-hooks-will-unmount 4)
(def ^{:private true} index-hooks-will-update 5)

(defn- open-comp [component-name typeid props? props comp-fn key hooks]
  (assert (not (nil? *vnode*))
          (str "tried to render " component-name " outside a render loop"))
  (let [vnode-index (or (aget *vnode* index-children-count) 0)
        will-unmount (when hooks (aget hooks index-hooks-will-unmount))
        will-update (when hooks (aget hooks index-hooks-will-update))
        will-receive-props (when hooks (aget hooks index-hooks-will-receive-props))
        prev (open-impl nil typeid key vnode-index)]
    (set! *props* props)
    (when (not= (aget *vnode* index-unmount) will-unmount)
      (aset *vnode* index-unmount will-unmount))
    (if (> *new-node* 0)
      (let [state-ref (atom nil)
            get-initial-state (and hooks (aget hooks index-hooks-get-initial-state))]
        (o/set state-ref vnode-stateful-key
               #js [*vnode* comp-fn *render-queue*])
        (add-watch state-ref ::component on-state-change)
        (aset *vnode* index-comp-props (if props? *props* no-props-flag))
        (aset *vnode* index-comp-data
              #js[component-name state-ref *svg-namespace* vnode-index *component-depth*])
        ;; call will-unmount at the end to keep things consistent in case of an exception
        ;; in will-unmount
        (when prev
          (if-let [prev-key (aget prev index-key)]
            (remove-vnode-key prev prev-key)
            (remove-node prev)))
        (when *vnode-to-remove*
          (remove-node *vnode-to-remove*)
          (set! *vnode-to-remove* nil))
        ;; call get-initial-state at the end to keep things consistent in case of an exception
        ;; in get-initial-state
        (if get-initial-state
          (do (reset! state-ref (get-initial-state *props*))
              (set! *state* @state-ref)
              (aset *vnode* index-comp-state *state*))
          (do (set! *state* nil)
              (aset *vnode* index-comp-state nil))))
      (let [prev-props (comp-props *vnode*)
            prev-state (aget *vnode* index-comp-state)
            state-ref (aget *vnode* index-comp-data index-comp-data-state-ref)
            state @state-ref
            comp-data (aget *vnode* index-comp-data)]
        (aset *vnode* index-comp-props (if props? *props* no-props-flag))
        (set! *state* state)
        (aset *vnode* index-comp-state state)
        (aset comp-data index-comp-data-dirty-flag nil)
        (when *moved-vnode*
          (aset comp-data index-comp-data-index-in-parent vnode-index))
        (when prev
          (if-let [prev-key (aget prev index-key)]
            (remove-vnode-key prev prev-key)
            (remove-node prev)))
        (if (and 
             (identical? prev-props *props*)
             (identical? prev-state state)
             (nil? *moved-vnode*))
          (set! *skip* true)
          (do
            (call-will-receive-props prev-props *props* state-ref will-receive-props)
            (when will-update (will-update *props* *state*))))
        (when (aget *vnode* index-children-count)
          (aset *vnode* index-children-count 0))
        (clean-keymap *vnode*)))
    (set! *component* *vnode*)
    (set! *component-depth* (inc *component-depth*))))

(defn- close-comp [parent-component hooks]
  (when-not *skip*
    (if hooks
      (close-impl (aget hooks index-hooks-did-mount) (aget hooks index-hooks-did-update))
      (close-impl nil nil)))
  (set! *component* parent-component)
  (set! *component-depth* (dec *component-depth*))
  (set! *vnode* (aget *vnode* index-parent-vnode))
  (when parent-component
    (set! *props* (aget parent-component index-comp-props))
    (set! *state* (aget parent-component index-comp-state)))
  (set! *skip* false))

(defn- attr-impl [ns key val set-fn]
  (let [prev-attrs (or (aget *vnode* index-attrs) #js [])
        prev-val (aget prev-attrs *attrs-count*)
        prev-node (aget *vnode* index-node)]
    (when (nil? (aget *vnode* *attrs-count*))
      (aset *vnode* *attrs-count* prev-attrs))
    (set! *attrs-count* (inc *attrs-count*))
    (when (not= prev-val val)
      (aset prev-attrs *attrs-count* val)
      (set-fn prev-node ns key val))))

(defn- handle-event-handlers [attrs attrs-index key handler f]
  (let [node (aget *vnode* index-node)]
    (when-let [prev-handler (aget attrs attrs-index)]
      (.removeEventListener node key prev-handler false))
    (when handler
      (.addEventListener node key handler false))
    (aset attrs attrs-index handler)
    (aset attrs (inc attrs-index) f)))

(defn- on-impl [key f param1 param2 param3 param-count]
  (let [prev-attrs (or (aget *vnode* index-attrs) #js [])
        prev-f (aget prev-attrs (inc *attrs-count*))
        state-ref (aget *component* index-comp-data index-comp-data-state-ref)]
    (when (nil? (aget *vnode* index-attrs))
      (aset *vnode* index-attrs prev-attrs))
    (set! *attrs-count* (+ *attrs-count* 2))
    (cond (and (= 0 param-count) (not= prev-f f))
          (let [handler (when (fn? f) (fn [e] (f e state-ref)))]
            (handle-event-handlers prev-attrs *attrs-count* key handler f))
          (and (= 1 param-count) (or (not= prev-f f)
                                     (not= param1 (aget prev-attrs (+ *attrs-count* 2)))))
          (let [handler (when (fn? f) (fn [e] (f e state-ref param1)))]
            (handle-event-handlers prev-attrs *attrs-count* key handler f)
            (aset prev-attrs (+ *attrs-count* 2) param1))
          (and (= 2 param-count) (or (not= prev-f f)
                                     (not= param1 (aget prev-attrs (+ *attrs-count* 2)))
                                     (not= param2 (aget prev-attrs (+ *attrs-count* 3)))))
          (let [handler (when (fn? f) (fn [e] (f e state-ref param1 param2)))]
            (handle-event-handlers prev-attrs *attrs-count* key handler f)
            (aset prev-attrs (+ *attrs-count* 2) param1)
            (aset prev-attrs (+ *attrs-count* 3) param2))
          (and (= 3 param-count) (or (not= prev-f f)
                                     (not= param1 (aget prev-attrs (+ *attrs-count* 2)))
                                     (not= param2 (aget prev-attrs (+ *attrs-count* 3)))
                                     (not= param3 (aget prev-attrs (+ *attrs-count* 4)))))
          (let [handler (when (fn? f) (fn [e] (f e state-ref param1 param2 param3)))]
            (handle-event-handlers prev-attrs *attrs-count* key handler f)
            (aset prev-attrs (+ *attrs-count* 2) param1)
            (aset prev-attrs (+ *attrs-count* 3) param2)
            (aset prev-attrs (+ *attrs-count* 4) param3)))))

(defn- on [key f]
  (on-impl key f nil nil nil 0))

(defn- on-static [key f]
  (when (and (> *new-node* 0) (fn? f))
    (let [node (aget *vnode* index-node)
          state-ref (aget *component* index-comp-data index-comp-data-state-ref)]
      (.addEventListener node key (fn [e] (f e state-ref)) false))))

(defn- on1 [key f attr1]
  (on-impl key f attr1 nil nil 1))

(defn- on-static1 [key f attr1]
  (when (and (> *new-node* 0) (fn? f))
    (let [node (aget *vnode* index-node)
          state-ref (aget *component* index-comp-data index-comp-data-state-ref)]
      (.addEventListener node key (fn [e] (f e state-ref attr1)) false))))

(defn- on2 [key f attr1 attr2]
  (on-impl key f attr1 attr2 nil 2))

(defn- on-static2 [key f attr1 attr2]
  (when (and (> *new-node* 0) (fn? f))
    (let [node (aget *vnode* index-node)
          state-ref (aget *component* index-comp-data index-comp-data-state-ref)]
      (.addEventListener node key (fn [e] (f e state-ref attr1 attr2)) false))))

(defn- on3 [key f attr1 attr2 attr3]
  (on-impl key f attr1 attr2 attr3 3))

(defn- on-static3 [key f attr1 attr2 attr3]
  (when (and (> *new-node* 0) (fn? f))
    (let [node (aget *vnode* index-node)
          state-ref (aget *component* index-comp-data index-comp-data-state-ref)]
      (.addEventListener node key (fn [e] (f e state-ref attr1 attr2 attr3)) false))))

(defn- attr-ns [ns key val]
  (attr-impl ns key (when (not (nil? val)) (str val)) set-attribute))

(defn- attr-ns-static [ns key val]
  (when (and (> *new-node* 0) (not (nil? val)))
    (let [node (aget *vnode* index-node)]
      (set-attribute node ns key (str val)))))

(defn- prop [key val]
  (if (> *svg-namespace* 0)
    (attr-impl nil key (when (not (nil? val)) (str val)) set-attribute)
    (attr-impl nil key val set-property)))

(defn- prop-static [key val]
  (when (and (> *new-node* 0) (not (nil? val)))
    (let [node (aget *vnode* index-node)]
      (if (> *svg-namespace* 0)
        (set-attribute node nil key (str val))
        (set-property node nil key val)))))

(defn- input-value [val]
  (attr-impl nil "value" (when (not (nil? val)) (str val)) set-input-value))

(defn- style [key val]
  (attr-impl nil key (str val) set-style))

(defn- style-static [key val]
  (when (and (> *new-node* 0) (not (nil? val)))
    (let [node (aget *vnode* index-node)]
      (set-style node nil key (str val)))))

(defn- style-custom [key val]
  (attr-impl nil key (str val) set-style-custom))

(defn- style-custom-static [key val]
  (when (and (> *new-node* 0) (not (nil? val)))
    (let [node (aget *vnode* index-node)]
      (set-style-custom node nil key (str val)))))

(defn- call-did-mount-hooks [i]
  (when (> i -1)
    (let [vnode (aget components-queue i)
          component (if (component? vnode) vnode (aget vnode index-component))
          props (aget component index-comp-props)
          state-ref (aget component index-comp-data index-comp-data-state-ref)]
      (set! *vnode* vnode)
      ((aget components-queue (dec i)) props state-ref))
    (recur (- i 2))))

;; vnode is nil on first render
(defn- patch-impl [render-queue parent-vnode vnode patch-fn maybe-props]
  (set! moved-flag #js [])
  (if vnode
    (aset parent-vnode index-children-count
          (aget vnode index-comp-data index-comp-data-index-in-parent))
    (aset parent-vnode index-children-count 0))
  (binding [*vnode* parent-vnode
            *component* nil
            *new-node* 0
            *attrs-count* 0
            *props* nil
            *state* nil
            *moved-vnode* nil
            *vnode-to-remove* nil
            *skip* false
            *svg-namespace* (if vnode
                              (aget vnode index-comp-data index-comp-data-svg-namespace)
                              0)
            *component-depth* (if vnode
                                (aget vnode index-comp-data index-comp-data-depth)
                                1)
            *watch-local-state* false
            *components-queue-count* 0
            *render-queue* render-queue]
    (if (identical? maybe-props no-props-flag)
      (patch-fn (when vnode (aget vnode index-key)))
      (patch-fn (when vnode (aget vnode index-key)) maybe-props))
    (set! *watch-local-state* true)
    (call-did-mount-hooks (dec *components-queue-count*))))

(defn- process-render-queue [render-queue]
  (let [l (.-length render-queue)]
    (loop [i 2]
      (when (< i l)
        (when-let [dirty-comps (aget render-queue i)]
          (loop []
            (let [vnode (.pop dirty-comps)
                  comp-fn (.pop dirty-comps)]
              ;; stop when there is no more dirty component. A component can push itself in the
              ;; dirty comps (at the same depth, in a did-mount hook)
              (when (and vnode (dirty-component? vnode))
                (patch-impl render-queue
                            (aget vnode index-parent-vnode) vnode
                            comp-fn (aget vnode index-comp-props))
                (recur)))))
        (recur (inc i)))))
  (aset render-queue index-render-queue-dirty-flag nil))

(defn- patch-root-impl [vtree patch-fn props]
  ;; On first render, render synchronously
  (let [vnode (.-vnode vtree)
        render-queue (.-render-queue vtree)
        async (aget render-queue index-render-queue-async)
        children (aget vnode index-children)]
    (if-let [comp (aget children 0)]
      (do (aset render-queue index-render-queue-dirty-flag true)
          (if async
            (.requestAnimationFrame js/window
                                    (fn []
                                      (patch-impl render-queue vnode comp patch-fn props)
                                      (process-render-queue render-queue)))
            (do
              (patch-impl render-queue vnode comp patch-fn props)
              (process-render-queue render-queue))))
      (patch-impl render-queue vnode nil patch-fn props))))

(deftype VTree [vnode render-queue])

(defn vtree
  "Creates a new vtree. By default the vtree is rendered asynchronously. When async is false,
  the vtree is rendered synchronously."
  ([]
   (VTree. #js [nil nil (.createDocumentFragment js/document) nil 0 #js []] #js [true]))
  ([async]
   (VTree. #js [nil nil (.createDocumentFragment js/document) nil 0 #js []] #js [async])))

(defn patch
  "Patch a vtree using component. The optional third argument is the component props."
  ([vtree component]
   (patch-root-impl vtree component no-props-flag))
  ([vtree component props]
   (patch-root-impl vtree component props)))

(defn remove [vtree]
  "Remove a vtree from the DOM. A removed vtree can still be patched and added back to the DOM."
  (let [vnode (.-vnode vtree)
        fragment (.createDocumentFragment js/document)]
    (when-let [comp (aget vnode index-children 0)]
      (insert-vnode-before* fragment comp nil))
    (aset vnode index-node fragment)))

(defn insert-before [vtree ref-node]
  "Inserts the DOM node(s) associated with vtree in the DOM, before ref-node."
  (let [parent-node (.-parentNode ref-node)
        vnode (.-vnode vtree)]
    (when-let [comp (aget vnode index-children 0)]
      (insert-vnode-before* parent-node comp ref-node))
    (aset vnode index-node parent-node)))

(defn append-child [vtree parent-node]
  "Inserts the DOM node(s) associated with vtree in the DOM, as the last child(ren) of 
parent-node."
  (let [vnode (.-vnode vtree)]
    (when-let [comp (aget vnode index-children 0)]
      (insert-vnode-before* parent-node comp nil))
    (aset vnode index-node parent-node)))

;; node identity is the same implies that the svg-namespace value did not change

;; index-in-parent is set when moving node (including in splice) to keep things consistent
;; in case of an exception in a hook function

;; exceptions in hooks:
;; did-mount -> should prevent the call of next did-mounts, since did-mounts are called after
;; the patch process
;; will-update -> should prevent the rest of the patch process
;; did-update -> should prevent the rest of the patch process
;; will-unmount -> should prevent the removal of the node. May prevent next patch calls when
;; the exception happens on a keyed vnode, because the next patching processes will try to
;; clean the keyed node (and fail).
