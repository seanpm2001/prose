(ns fr.jeremyschoffen.prose.alpha.eval.sci
  (:refer-clojure :exclude [eval])
  (:require
    [sci.core :as sci]))


;;----------------------------------------------------------------------------------------------------------------------
;; Eval inside sci
;;----------------------------------------------------------------------------------------------------------------------
(def eval-ns
  '(do
     (ns fr.jeremyschoffen.prose.alpha.eval.sci)

     (defn wrap-eval-exception
       "Wraps an eval function so that exceptions thrown are caught and rethrown in an ex-info
       containing the form that threw in its ex-data."
       [e]
       (fn [form]
         (try
           (e form)
           (catch #?(:clj Exception
                     :cljs js/Error) e
             (throw (ex-info "Error during evaluation."
                             {:form form}
                             e))))))


     (defn eval-form->eval-forms
       "Turn an function that evals a form into a function the evals a sequence of forms in sequence.
       The returned function returns the sequence of evaluation results."
       [eval-form]
       (fn [forms]
         (into []
               (map eval-form)
               forms)))


     (defn make-eval-ctxt
       "Make a basic evaluation context."
       [eval-form forms]
       {:eval/forms forms
        :eval/eval-forms (-> eval-form wrap-eval-exception eval-form->eval-forms)})


     (defn eval-ctxt
       "Performs the evaluation described by the `ctxt` map.

       Return the context with the evaluation result associated under the key `:eval/result`.
       If the evaluation throws, associates the exception under the
       key `:eval/error` instead."
       [{:eval/keys [forms eval-forms] :as ctxt}]
       (let [[ret res] (try
                         [:eval/result (eval-forms forms)]
                         (catch #?(:clj Exception
                                   :cljs js/Error) e
                           [:eval/error e]))]
         (assoc ctxt ret res)))


     (defn wrap-eval-result
       "Middleware that either return the result of the evaluation or throw any error raised."
       [eval-ctxt*]
       (fn [ctxt]
         (let [{:eval/keys [result error]} (eval-ctxt* ctxt)]
           (if result
             result
             (throw error)))))


     (defn wrap-snapshot-ns [eval-ctxt*]
       "Middleware making sure the current ns stays the same after an evaluation."
       (fn [ctxt]
         (let [current-ns (-> *ns* str symbol)
               ret (eval-ctxt* ctxt)]
           (in-ns current-ns)
           ret)))


     (defn wrap-eval-in-temp-ns
       "Middleware that makes the evaluation take place in a temporary namespace."
       ([eval-ctxt*]
        (wrap-eval-in-temp-ns eval-ctxt* (gensym "temp_ns__")))
       ([eval-ctxt* temp-ns]
        (fn [{:eval/keys [eval-forms] :as ctxt}]
          (let [res (do
                      (eval-forms [(list 'ns temp-ns)])
                      (eval-ctxt* ctxt))]
            (remove-ns temp-ns)
            res))))


     (def wrap-eval-forms-in-temp-ns
       "Middleware used in [[eval-forms-in-temp-ns]] namely:
       - [[wrap-eval-result]]
       - [[wrap-snapshot-ns]]
       - [[wrap-eval-in-temp-ns]]"
       (comp wrap-eval-result
             wrap-snapshot-ns
             wrap-eval-in-temp-ns))


     (defn eval-forms-in-temp-ns
       "Evaluate a sequence of forms in a temporary namespace.

       Args:
       - `forms`; a sequence of forms to eval
       - `eval-form`: a function a evaluates one form defaulting to sci own eval."
       ([forms]
        (eval-forms-in-temp-ns eval forms))
       ([eval-form forms]
        (let [ctxt (make-eval-ctxt eval-form forms)
              eval-forms* (wrap-eval-forms-in-temp-ns eval-ctxt)]
          (eval-forms* ctxt))))


     (def wrap-eval-forms
       "Middleware used in [[eval-forms]] namely:
       - [[wrap-eval-result]]
       - [[wrap-snapshot-ns]]"
       (comp wrap-eval-result
             wrap-snapshot-ns))


     (defn eval-forms
       "Evaluate a sequence of forms ensuring the the current namespace doesn't change after the evaluation."
       ([forms]
        (eval-forms eval forms))
       ([eval-form forms]
        (let [ctxt (make-eval-ctxt eval-form forms)
              eval-forms* (wrap-eval-forms eval-ctxt)]
          (eval-forms* ctxt))))))


(defn install-code [sci-ctxt code]
  (sci/binding [sci/ns @sci/ns]
    (sci/eval-form sci-ctxt code))
  sci-ctxt)


(defn init
  "Create a sci evaluation context.

  Same as [[sci.core/init]] with the [[eval-ns]] installed. The goal is to provide code executed by sci
  an environment that has a namespace equivalent to [[fr.jeremyschoffen.prose.alpha.eval.clojure]] pre-installed."
  [opts]
  (let [sci-ctxt (sci/init opts)]
    (install-code sci-ctxt eval-ns)
    sci-ctxt))

(comment
  (def env (init {}))

  (sci/binding [sci/ns @sci/ns
                sci/out *out*]
    (sci/eval-form env
                   '(do
                      (in-ns 'fr.jeremyschoffen.prose.alpha.eval.sci)
                      (eval-forms-in-temp-ns '[(+ 1 2 3)
                                               (println *ns*)
                                               (throw (ex-info "some msg" {:toto 1}))]))))

  (-> *e ex-data) ;; should contains faulty form
  (-> *e ex-message)
  (-> *e ex-cause ex-cause ex-data)
  (-> *e ex-cause ex-cause ex-message)


  (sci/binding [sci/ns @sci/ns
                sci/out *out*]
    (doseq [f '[(println *ns*)
                (ns foobar)
                (def inc* inc)
                (inc* 3)
                (println *ns*)]]
      (sci/eval-form env f)))


  (sci/binding [sci/ns @sci/ns
                sci/out *out*]
    (sci/eval-form env
      '(contains? (into (sorted-set)
                        (comp
                          (map str)
                          (map keyword))
                        (all-ns))
                  :foobar))))


;;----------------------------------------------------------------------------------------------------------------------
;; Utilities
;;----------------------------------------------------------------------------------------------------------------------
(defn make-eval-fn
  "Make an eval function from an sci context.

  The result is a function of one argument, a form to be evaluated by sci in the evaluation context `ctxt`."
  [ctxt]
  (fn [form]
    (sci/eval-form ctxt form)))


(defn eval-form->eval-forms
  "Turn an function that evals a form into a function the evals a sequence of forms in sequence.
  The returned function returns the sequence of evaluation results."
  [eval-form]
  (fn [forms]
    (into []
          (map eval-form)
          forms)))


(defn wrap-sci-bindings
  "Middle for evaluation functions that return an performing the evaluation with the sci `bindings` properly set."
  [eval-fn bindings]
  (fn [form]
    (sci/with-bindings bindings
      (eval-fn form))))