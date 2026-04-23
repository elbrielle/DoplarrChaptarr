(ns doplarr.interaction-state-machine
  (:require
   [clojure.core.async :as a]
   [clojure.string :as str]
   [com.rpl.specter :as s]
   [discljord.messaging :as m]
   [doplarr.discord :as discord]
   [doplarr.state :as state]
   [doplarr.utils :as utils :refer [log-on-error]]
   [fmnoise.flow :refer [else then]]
   [taoensso.timbre :refer [fatal info]]))

(def channel-timeout 600000)

(defn start-interaction! [interaction]
  (a/go
    (let [uuid (str (java.util.UUID/randomUUID))
          id (:id interaction)
          token (:token interaction)
          payload-opts (:options (:payload interaction))
          media-type (first (keys payload-opts))
          query (s/select-one [media-type :query] payload-opts)
          {:keys [messaging bot-id]} @state/discord]
                                        ; Send the ack for delayed response
      (->> @(m/create-interaction-response! messaging id token 5 :data {:flags 64})
           (else #(fatal % "Error in interaction ack")))
                                        ; Search for results
      (info "Performing search for" (name media-type) query)
      (let [results (->> (log-on-error
                          (a/<! ((utils/media-fn media-type "search") query media-type))
                          "Exception from search")
                         (then #(->> (take (:max-results @state/config discord/MAX-OPTIONS) %)
                                     (into []))))]
                                        ; Setup ttl cache entry
        (swap! state/cache assoc uuid {:results results
                                       :media-type media-type
                                       :token token
                                       :last-modified (System/currentTimeMillis)})
                                        ; Create dropdown for search results
        (->> @(m/edit-original-interaction-response! messaging bot-id token (discord/search-response results uuid))
             (else #(fatal % "Error in creating search responses")))))))

(defn query-for-option-or-request [pending-opts uuid]
  (a/go
    (let [{:keys [messaging bot-id]} @state/discord
          {:keys [media-type token payload]} (get @state/cache uuid)]
      (if (empty? pending-opts)
        (let [;; Pass the uuid through payload so backends can side-effect the
              ;; cached payload from inside request-embed — the Chaptarr
              ;; backend uses this to stash the resolved book id after its
              ;; pre-request POST, so the Request-click handler can take the
              ;; fast PUT+BookSearch path instead of re-POSTing. Other
              ;; backends ignore the extra key.
              embed (log-on-error
                     (a/<! ((utils/media-fn media-type "request-embed")
                            (assoc payload :sm-uuid uuid)
                            media-type))
                     "Exception from request-embed")]
          (swap! state/cache assoc-in [uuid :embed] embed)
          ;; send-request-embed! dispatches to discljord's normal edit path or
          ;; a multipart HTTP PATCH when the embed carries a :cover-attachment
          ;; (fork addition — needed for Chaptarr cover images).
          (->> (discord/send-request-embed! messaging bot-id token embed uuid)
               (else #(fatal % "Error in sending request embed"))))
        (let [[op options] (first pending-opts)]
          (->> @(m/edit-original-interaction-response! messaging bot-id token (discord/option-dropdown op options uuid 0))
               (else #(fatal % "Error in creating option dropdown"))))))))

(defmulti process-event! (fn [event _ _ _] event))

(defmethod process-event! "result-select" [_ interaction uuid _]
  (a/go
    (let [{:keys [results media-type]} (get @state/cache uuid)
          result (nth results (discord/dropdown-result interaction))
          add-opts (log-on-error
                    (a/<! ((utils/media-fn media-type "additional-options") result media-type))
                    "Exception thrown from additional-options")
          pending-opts (->> add-opts
                            (filter #(seq? (second %)))
                            (into {}))
          ready-opts (apply (partial dissoc add-opts) (keys pending-opts))]
                                        ; Start setting up the payload
      (swap! state/cache assoc-in [uuid :payload] result)
      (swap! state/cache assoc-in [uuid :pending-opts] pending-opts)
                                        ; Merge in the opts that are already satisfied
      (swap! state/cache update-in [uuid :payload] merge ready-opts)
      (query-for-option-or-request pending-opts uuid))))

(defmethod process-event! "option-page" [_ _ uuid option]
  (let [{:keys [messaging bot-id]} @state/discord
        {:keys [pending-opts token]} (get @state/cache uuid)
        [opt page] (str/split option #"-")
        op (keyword opt)
        page (Long/parseLong page)
        options (op pending-opts)]
    (->> @(m/edit-original-interaction-response! messaging bot-id token (discord/option-dropdown op options uuid page))
         (else #(fatal % "Error in updating option dropdown")))))

(defmethod process-event! "option-select" [_ interaction uuid option]
  (let [selection (discord/dropdown-result interaction)
        cache-val (swap! state/cache update-in [uuid :pending-opts] #(dissoc % (keyword option)))]
    (swap! state/cache assoc-in [uuid :payload (keyword option)] selection)
    (query-for-option-or-request (get-in cache-val [uuid :pending-opts]) uuid)))

(defmethod process-event! "request" [_ interaction uuid format]
  (let [{:keys [messaging bot-id]} @state/discord
        {:keys [payload media-type token embed]} (get @state/cache uuid)
        {:keys [user-id channel-id]} interaction]
    (letfn [(msg-resp [msg] (->> @(m/edit-original-interaction-response! messaging bot-id token (discord/content-response msg))
                                 (else #(fatal % "Error in message response"))))]
      ;; Immediately replace the confirmation embed with a progress
      ;; message. Three effects matter:
      ;;  - User sees the click took effect (the current silent ACK
      ;;    leaves the confirmation embed unchanged until the request
      ;;    finishes, which on the Chaptarr placeholder-remediation
      ;;    path is up to 60s — Live Test 17 showed users re-clicking
      ;;    and then seeing Discord's generic 'This interaction failed'
      ;;    because the second click landed on a stale component).
      ;;  - `discord/content-response` returns `:components []`, so the
      ;;    Request/Cancel buttons are stripped from the message. The
      ;;    user physically cannot re-click, which closes the
      ;;    duplicate-click pathway entirely (narrow race in the
      ;;    initial tens of ms before this edit lands remains; track
      ;;    separately if it surfaces).
      ;;  - For book/audiobook requests specifically, tell the user
      ;;    the slow path exists so 'it's taking a while' doesn't feel
      ;;    like a bug. Other backends (Sonarr/Radarr/Overseerr) are
      ;;    fast enough that the generic message is fine.
      (msg-resp (if (contains? #{:book :audiobook} media-type)
                  (str "Working on your Chaptarr request… Chaptarr may be "
                       "pulling metadata for this author from its upstream "
                       "source, which can take up to a minute for large "
                       "catalogs. This message will update when it's done.")
                  "Working on your request…"))
      (->>  (log-on-error
             (a/<!! ((utils/media-fn media-type "request")
                     (assoc payload :format (keyword format) :discord-id user-id)
                     media-type))
             "Exception from request")
            (then (fn [status]
                    (case status
                      :unauthorized (msg-resp "You are unauthorized to perform this request in the configured backend")
                      :pending (msg-resp "This has already been requested and the request is pending")
                      :processing (msg-resp "This is currently processing and should be available soon!")
                      :available (msg-resp "This selection is already available!")
                      (do
                        (info "Performing request for " payload)
                        (msg-resp "Request performed!")
                        (case (:discord/requested-msg-style @state/config)
                          :none nil
                          :embed (m/create-message! messaging channel-id (discord/request-performed-embed embed user-id))
                          (m/create-message! messaging channel-id (discord/request-performed-plain payload media-type user-id)))))))
            (else (fn [e]
                    (let [{:keys [status body] :as data} (ex-data e)]
                      (if (= status 403)
                        (->> @(m/edit-original-interaction-response! messaging bot-id token (discord/content-response (body "message")))
                             (else #(fatal % "Error in sending request failure response")))
                        (->> @(m/edit-original-interaction-response! messaging bot-id token (discord/content-response "Unspecified error on request, check logs"))
                             (then #(fatal "Non 403 error on request" % data))
                             (else #(fatal % "Error in sending error response")))))))))))

(defn continue-interaction! [interaction]
  (let [[event uuid option] (str/split (s/select-one [:payload :component-id] interaction) #":")
        now (System/currentTimeMillis)
        {:keys [token id]} interaction
        {:keys [messaging bot-id]} @state/discord]
    ; Send the ack
    (->> @(m/create-interaction-response! messaging id token 6)
         (else #(fatal % "Error sending response ack")))
    ; Check last modified
    (if-let [{:keys [token last-modified]} (get @state/cache uuid)]
      (if (> (- now last-modified) channel-timeout)
        ; Update interaction with timeout message
        (->> @(m/edit-original-interaction-response! messaging bot-id token (discord/content-response "Request timed out, please try again"))
             (else #(fatal % "Error in sending timeout response")))
        ; Move through the state machine to update cache side effecting new components
        (do
          (swap! state/cache assoc-in [uuid :last-modified] now)
          (process-event! event interaction uuid option)))
      (->> @(m/edit-original-interaction-response! messaging bot-id token (discord/content-response "Request timed out, please try again"))
           (else #(fatal % "Error in sending timeout response"))))))
