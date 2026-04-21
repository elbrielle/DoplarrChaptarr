(ns doplarr.discord
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as a]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.rpl.specter :as s]
   [discljord.messaging :as m]
   [doplarr.utils :as utils]
   [fmnoise.flow :as flow :refer [else]]
   [hato.client :as hc]
   [taoensso.timbre :refer [fatal]]))

(defn request-command [media-types]
  {:name "request"
   :description "Request media"
   :options
   (into [] (for [media media-types]
              {:type 1
               :name (name media)
               :description (str "Request " (name media))
               :options [{:type 3
                          :name "query"
                          :description "Query"
                          :required true}]}))})

(defn content-response [content]
  {:content content
   :flags 64
   :embeds []
   :components []})

(def interaction-types {1 :ping
                        2 :application-command
                        3 :message-component})

(def component-types {1 :action-row
                      2 :button
                      3 :select-menu})

(def MAX-OPTIONS 25)
(def MAX-CHARACTERS 100)

(def request-thumbnail
  {:series "https://thetvdb.com/images/logo.png"
   :movie "https://i.imgur.com/44ueTES.png"
   :book "https://raw.githubusercontent.com/kiranshila/Doplarr/main/logos/logo_title.png"
   :audiobook "https://raw.githubusercontent.com/kiranshila/Doplarr/main/logos/logo_title.png"})

(defn application-command-interaction-option-data [app-com-int-opt]
  [(keyword (:name app-com-int-opt))
   (into {} (map (juxt (comp keyword :name) :value)) (:options app-com-int-opt))])

(defn interaction-data [interaction]
  {:id (:id interaction)
   :type (interaction-types (:type interaction))
   :token (:token interaction)
   :user-id (s/select-one [:member :user :id] interaction)
   :channel-id (:channel-id interaction)
   :payload
   {:component-type (component-types (get-in interaction [:data :component-type]))
    :component-id (s/select-one [:data :custom-id] interaction)
    :name (s/select-one [:data :name] interaction)
    :values (s/select-one [:data :values] interaction)
    :options (into {} (map application-command-interaction-option-data) (get-in interaction [:data :options]))}})

(defn request-button [format uuid]
  {:type 2
   :style 1
   :disabled false
   :custom_id (str "request:" uuid ":" format)
   :label (str/trim (str "Request " format))})

(defn page-button [uuid option page label]
  {:type 2
   :style 1
   :custom_id (str "option-page:" uuid ":" option "-" page)
   :disabled false
   :label (apply str (take MAX-CHARACTERS label))})

(defn select-menu-option [index result]
  {:label (apply str (take MAX-CHARACTERS (or (:title result) (:name result))))
   :description (:year result)
   :value index})

(defn dropdown [content id options]
  {:content content
   :flags 64
   :components [{:type 1
                 :components [{:type 3
                               :custom_id id
                               :options options}]}]})

(defn search-response [results uuid]
  (if (empty? results)
    {:content "Search result returned no hits"
     :flags 64}
    (dropdown "Choose one of the following results"
              (str "result-select:" uuid)
              (map-indexed select-menu-option results))))

(defn option-dropdown [option options uuid page]
  (let [all-options (map #(set/rename-keys % {:name :label :id :value}) options)
        chunked (partition-all MAX-OPTIONS all-options)
        ddown (dropdown (str "Which " (utils/canonical-option-name option) "?")
                        (str "option-select:" uuid ":" (name option))
                        (nth chunked page))]
    (cond-> ddown
      ; Create the action row if we have more than 1 chunk
      (> (count chunked) 1) (update-in [:components] conj {:type 1 :components []})
      ; More chunks exist
      (< page (dec (count chunked))) (update-in [:components 1 :components] conj (page-button uuid (name option) (inc page) "More"))
      ; Past chunk 1
      (> page 0) (update-in [:components 1 :components] conj (page-button uuid (name option) (dec page) "Less")))))

(defn dropdown-result [interaction]
  (Integer/parseInt (s/select-one [:payload :values 0] interaction)))

(defn request-embed [{:keys [media-type title overview poster season quality-profile language-profile metadata-profile rootfolder]}]
  ; :image is only included when poster is a non-nil URL. A {:url nil} or
  ; {:image nil} triggers Discord's 50035 Invalid Form Body validator on the
  ; embed image field — notably relevant for the Chaptarr backend, which
  ; returns relative cover paths (e.g. /MediaCoverProxy/...) that can't be
  ; shown without a publicly-reachable base URL.
  (cond-> {:title title
           :description overview
           :thumbnail {:url (media-type request-thumbnail)}
           :fields (filterv
                    identity
                    ; Some overrides to make things pretty
                    [(when quality-profile
                       {:name "Profile"
                        :value quality-profile})
                     (when language-profile
                       {:name "Language Profile"
                        :value language-profile})
                     (when metadata-profile
                       {:name "Metadata Profile"
                        :value metadata-profile})
                     (when season
                       {:name "Season"
                        :value (if (= season -1) "All" season)})
                     (when rootfolder
                       {:name "Root Folder"
                        :value rootfolder})])}
    poster (assoc :image {:url poster})))

(defn request [embed-data uuid]
  {:content (str "Request this " (name (:media-type embed-data)) " ?")
   :embeds [(request-embed embed-data)]
   :flags 64
   :components [{:type 1 :components (for [format (:request-formats embed-data)]
                                       (request-button format uuid))}]})

;; ------------------------------------------------------------------
;; Fork addition: multipart edit-original-interaction-response.
;;
;; discljord's edit-original-interaction-response! does not expose Discord's
;; multipart/file upload support — only create-followup-message! does. The
;; Chaptarr backend needs to attach book cover bytes to the confirmation
;; embed (because Chaptarr returns relative cover paths that Discord can't
;; fetch on its own). Rather than refactor the state machine to send a
;; separate followup message (which would split the interaction into two
;; bot messages), we go direct to Discord's HTTP webhook endpoint for the
;; attachment case only; discljord is still used for every non-attachment
;; edit.
;;
;; Rate limiting: discljord tracks rate limits across its own calls. This
;; bypass is not tracked. Acceptable here because this code path runs at
;; most once per user book request — nowhere near Discord's 50-reqs/sec
;; interaction webhook limit.
;; ------------------------------------------------------------------

(defn- interaction-webhook-url [bot-id token]
  (str "https://discord.com/api/v10/webhooks/" bot-id "/" token "/messages/@original"))

(defn edit-original-with-attachment!
  "Edit an interaction's initial response with a file attachment via a direct
  multipart PATCH. Returns a channel yielding the response map on success or
  an exception on failure — same shape as discljord's promise-based
  helpers, so callers can use the fmnoise (else …) pattern to handle errors
  consistently."
  [bot-id token payload {:keys [bytes filename content-type]}]
  (let [chan (a/promise-chan)]
    (hc/request
     {:method :patch
      :url (interaction-webhook-url bot-id token)
      :async? true
      :version :http-1.1
      :throw-exceptions? true
      :multipart [{:name "payload_json"
                   :content (json/generate-string payload)
                   :content-type "application/json"}
                  {:name "files[0]"
                   :content bytes
                   :file-name (or filename "cover.jpeg")
                   :content-type (or content-type "image/jpeg")}]}
     #(a/put! chan %)
     #(a/put! chan %))
    chan))

(defn send-request-embed!
  "Route the confirmation embed through either discljord's normal edit path
  or the multipart-attachment direct-HTTP path, depending on whether the
  embed data carries a :cover-attachment. Strips :cover-attachment from the
  payload before sending so it never reaches Discord's JSON."
  [messaging bot-id token embed uuid]
  (if-let [attachment (:cover-attachment embed)]
    (let [clean-embed (dissoc embed :cover-attachment)
          payload (request clean-embed uuid)]
      (a/<!! (edit-original-with-attachment! bot-id token payload attachment)))
    @(m/edit-original-interaction-response! messaging bot-id token (request embed uuid))))

(defn request-performed-plain [payload media-type user-id]
  {:content
   (str "<@" user-id "> your request for the "
        (name media-type) " `" (:title payload) " (" (:year payload) ")"
        "` has been received!")})

(defn request-performed-embed [embed-data user-id]
  {:content (str "<@" user-id "> has requested:")
   :embeds [(request-embed embed-data)]})

;; Discljord Utilities
(defn register-commands [media-types bot-id messaging guild-id]
  (->> @(m/bulk-overwrite-guild-application-commands!
         messaging bot-id guild-id
         [(request-command media-types)])
       (else #(fatal % "Error in registering commands"))))
