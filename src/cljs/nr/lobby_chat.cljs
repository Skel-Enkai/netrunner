(ns nr.lobby-chat
  (:require
   [nr.avatar :refer [avatar]]
   [nr.translations :refer [tr]]
   [nr.ws :as ws]
   [reagent.core :as r]))

(defn send-message [state]
  (let [text (:msg @state)]
    (when (and (string? text) (not-empty text))
      (ws/ws-send! [:lobby/say text])
      (swap! state assoc :should-scroll true)
      (swap! state assoc :msg ""))))

(defn scrolled-to-end?
  [el tolerance]
  (> tolerance (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el))))

(defn lobby-chat [_messages]
  (r/with-let [state (r/atom {:message-list nil
                              :msg ""
                              :should-scroll false})
               message-list (r/cursor state [:message-list])
               current-input (r/cursor state [:msg])
               should-scroll (r/cursor state [:should-scroll])]
    (r/create-class
      {:display-name "lobby-chat"
       :component-did-mount
       (fn []
         (let [el (r/dom-node @message-list)]
           (set! (.-scrollTop el) (.-scrollHeight el))))
       :component-will-update
       (fn []
         (let [el (r/dom-node @message-list)]
           (swap! state assoc :should-scroll (or @should-scroll
                                                 (scrolled-to-end? el 15)))))
       :component-did-update
       (fn []
         (let [el (r/dom-node @message-list)]
           (when @should-scroll
             (swap! state assoc :should-scroll false)
             (set! (.-scrollTop el) (.-scrollHeight el)))))
       :reagent-render
       (fn [messages]
         [:div.chat-box
          [:h3 (tr [:lobby.chat "Chat"])]
          [:div.message-list {:ref #(swap! state assoc :message-list %)}
           (doall
             (map-indexed
               (fn [i msg]
                 (if (= (:user msg) "__system__")
                   [:div.system {:key i} (:text msg)]
                   [:div.message {:key i}
                    [avatar (:user msg) {:opts {:size 38}}]
                    [:div.content
                     [:div.username (get-in msg [:user :username])]
                     [:div (:text msg)]]]))
               @messages))]
          [:div
           [:form.msg-box {:on-submit #(do (.preventDefault %)
                                           (send-message state))}
            [:input {:placeholder (tr [:chat.placeholder "Say something"])
                     :type "text"
                     :value @current-input
                     :on-change #(swap! state assoc :msg (-> % .-target .-value))}]
            [:button (tr [:chat.send "Send"])]]]])})))
