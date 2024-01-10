(ns game.macros-test
  (:require [game.core :as core]
            [game.core.card :refer [get-card]]
            [game.utils :refer [side-str]]
            [clojure.test :refer :all]
            [clojure.string :refer [join]]
            [kaocha.report :as k.report]
            [kaocha.output :as k.output]
            [kaocha.hierarchy :as k.hierarchy]
            [game.utils-test :refer :all]))

(defn- dont-use-me [s]
  `(throw (ex-info (str ~s " should only be used in do-game") {})))

(defmacro refresh [_]
  (dont-use-me "refresh"))
(defmacro prompt-map [_]
  (dont-use-me "prompt-map"))
(defmacro prompt-type [_]
  (dont-use-me "prompt-type"))
(defmacro prompt-buttons [_]
  (dont-use-me "prompt-buttons"))
(defmacro prompt-titles [_]
  (dont-use-me "prompt-titles"))
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro prompt-fmt [_]
  (dont-use-me "prompt-fmt"))
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro print-prompts []
  (dont-use-me "print-prompts"))

(defmacro do-game [s & body]
  `(let [~'state ~s
         ~'get-corp (fn [] (:corp @~'state))
         ~'get-runner (fn [] (:runner @~'state))
         ~'get-run (fn [] (:run @~'state))
         ~'hand-size (fn [side#] (core/hand-size ~'state side#))
         ~'refresh (fn [card#]
                     ;; ;; uncommenting the below two assertions causes a looot of tests to fail
                     ;; (is ~'card "card passed to refresh should not be nil")
                     (let [~'ret (get-card ~'state card#)]
                       ;; (is ~'ret "(refresh card) is nil - if this is intended, use (core/get-card state card)")
                       ~'ret))
         ~'prompt-map (fn [side#] (-> @~'state side# :prompt first))
         ~'prompt-type (fn [side#] (:prompt-type (~'prompt-map side#)))
         ~'prompt-buttons (fn [side#] (->> (~'prompt-map side#) :choices (map :value)))
         ~'prompt-titles (fn [side#] (map :title (~'prompt-buttons side#)))
         ~'prompt-fmt (fn [side#]
                        (let [prompt# (~'prompt-map side#)
                              choices# (:choices prompt#)
                              choices# (cond
                                         (nil? choices#) nil
                                         (sequential? choices#) choices#
                                         :else [choices#])
                              card# (:card prompt#)
                              prompt-type# (:prompt-type prompt#)]
                          (str (side-str side#) ": " (:msg prompt# "") "\n"
                               (when prompt-type# (str "Type: " prompt-type# "\n"))
                               (when card# (str "Card: " (:title card#) "\n"))
                               (join "\n" (map #(str "[ " (or (get-in % [:value :title])
                                                              (:value %)
                                                              %
                                                              "nil") " ]") choices#))
                               "\n")))
         ~'print-prompts (fn []
                           (print (~'prompt-fmt :corp))
                           (println (~'prompt-fmt :runner)))]
     ~@body))

(defmacro changes-val-macro [change-amt val-form msg & body-form]
  `(let [start-val# ~val-form]
     (do ~@body-form)
     (let [end-val# ~val-form
           actual-change# (- end-val# start-val#)]
       (clojure.test/do-report
         {:type (if (= actual-change# ~change-amt) :pass :fail)
          :actual actual-change#
          :expected ~change-amt
          :message (str "Changed from " start-val# " to " end-val# ", Expected end result of " (+ start-val# ~change-amt) "\n" ~msg " " (cons 'do '~body-form))}))))

(defmacro changes-val [change-amt val-form & body-form]
  `(changes-val-macro ~change-amt ~val-form "" ~@body-form))

(defmethod clojure.test/assert-expr 'changes-val [msg form]
  (let [change-amt (nth form 1)
        val-form (nth form 2)
        body-form (drop 3 form)]
    `(changes-val-macro ~change-amt ~val-form ~msg ~@body-form)))

(defmacro changes-credits [side change-amt & body-form]
  `(changes-val-macro ~change-amt (:credit ~side) "" ~@body-form))

;; Enables you to do this:
;; (is (changes-credits (get-runner) -5
;;   (play-from-hand state :runner "Magnum Opus")))
(defmethod clojure.test/assert-expr 'changes-credits [msg form]
  (let [side (nth form 1)
        change-amt (nth form 2)
        body-form (drop 3 form)]
    `(changes-val-macro ~change-amt (:credit ~side) ~msg ~@body-form)))

(defmacro before-each
  [let-bindings & testing-blocks]
  (assert (every? #(= 'testing (first %)) testing-blocks))
  (let [bundles (for [block testing-blocks] `(let [~@let-bindings] ~block))]
    `(do ~@bundles)))

#_{:clj-kondo/ignore [:unused-binding]}
(defmacro changed? [bindings & body]
  `(throw (ex-info "changed? should only be used in `is` asserts." {})))

(defmethod clojure.test/assert-expr 'changed?
  [msg [_changed bindings & body]]
  (let [exprs (take-nth 2 bindings)
        amts (take-nth 2 (drop 1 bindings))
        init-binds (repeatedly gensym)
        end-binds (repeatedly gensym)
        pairs (mapv vector
                    amts
                    (map #(list `quote %) exprs)
                    init-binds
                    end-binds)]
    `(let [~@(interleave init-binds exprs)
           _# (do ~@body)
           ~@(interleave end-binds exprs)]
       (doseq [[amt# expr# init# end#] ~pairs
               :let [expected# (+ init# amt#)
                     actual-change# (- end# init#)]]
         (clojure.test/do-report
           {:type (if (= actual-change# amt#) :pass :changed-fail)
            :expected amt#
            :actual actual-change#
            :message (format "%s\n%s => (%s to %s)" ~msg expr# init# end#)})))))

(defn report-failed-change [m]
  (with-test-out
    (println (str "\n" (k.output/colored :red "FAIL")
                  " in " (testing-vars-str m)))
    (when (seq *testing-contexts*)
      (println (testing-contexts-str)))
    (when-let [message (:message m)] 
      (println message))
    (println "expected diff:" (pr-str (:expected m)))
    (println "  actual diff:" (pr-str (:actual m)))))

(do
  (k.hierarchy/derive! :changed-fail :kaocha/known-key)
  (k.hierarchy/derive! :changed-fail :kaocha/fail-type)
  nil)

(defmethod k.report/fail-summary :changed-fail [m]
  (report-failed-change m))

;; Just in case someone uses a non-kaocha runner
(defmethod clojure.test/report :changed-fail [m]
  (inc-report-counter :fail)
  (report-failed-change m))
