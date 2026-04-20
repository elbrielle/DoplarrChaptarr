(ns doplarr.backends.chaptarr
  (:require
   [clojure.core.async :as a]
   [doplarr.backends.chaptarr.impl :as impl]
   [doplarr.state :as state]
   [doplarr.utils :as utils]
   [fmnoise.flow :refer [then]]
   [taoensso.timbre :refer [warn]]))

(defn- rootfolder-config-key [media-type]
  (if (= media-type :audiobook)
    :chaptarr/audiobook-rootfolder
    :chaptarr/ebook-rootfolder))

(defn- quality-profile-config-key [media-type]
  (if (= media-type :audiobook)
    :chaptarr/audiobook-quality-profile
    :chaptarr/ebook-quality-profile))

(defn search [term _]
  (impl/lookup-book term))

(defn additional-options [_ media-type]
  (a/go
    (let [quality-profiles (a/<! (impl/quality-profiles))
          metadata-profiles (a/<! (impl/metadata-profiles))
          rootfolders (a/<! (impl/rootfolders))
          config @state/config
          quality-profile (get config (quality-profile-config-key media-type))
          rootfolder (get config (rootfolder-config-key media-type))
          metadata-profile (:chaptarr/metadata-profile config)
          default-profile-id (utils/id-from-name quality-profiles quality-profile)
          default-metadata-id (utils/id-from-name metadata-profiles metadata-profile)
          default-root-folder (utils/id-from-name rootfolders rootfolder)]
      (when (and quality-profile (nil? default-profile-id))
        (warn (str "Chaptarr " (name media-type) " quality profile `" quality-profile
                   "` not found in backend, check spelling")))
      (when (and metadata-profile (nil? default-metadata-id))
        (warn (str "Chaptarr metadata profile `" metadata-profile
                   "` not found in backend, check spelling")))
      (when (and rootfolder (nil? default-root-folder))
        (warn (str "Chaptarr " (name media-type) " root folder `" rootfolder
                   "` not found in backend, check spelling")))
      {:quality-profile-id
       (cond
         default-profile-id             default-profile-id
         (= 1 (count quality-profiles)) (:id (first quality-profiles))
         :else quality-profiles)
       :metadata-profile-id
       (cond
         default-metadata-id             default-metadata-id
         (= 1 (count metadata-profiles)) (:id (first metadata-profiles))
         :else metadata-profiles)
       :rootfolder-id
       (cond
         default-root-folder            default-root-folder
         (= 1 (count rootfolders))      (:id (first rootfolders))
         :else rootfolders)})))

(defn request-embed [{:keys [title author-name overview remote-cover
                             quality-profile-id metadata-profile-id rootfolder-id]}
                     media-type]
  (a/go
    (let [rootfolders (a/<! (impl/rootfolders))
          quality-profiles (a/<! (impl/quality-profiles))
          metadata-profiles (a/<! (impl/metadata-profiles))]
      {:title (if (and author-name (not (.contains (or title "") author-name)))
                (str title " — " author-name)
                title)
       :overview overview
       :poster remote-cover
       :media-type media-type
       :request-formats [""]
       :quality-profile (utils/name-from-id quality-profiles quality-profile-id)
       :language-profile (utils/name-from-id metadata-profiles metadata-profile-id)
       :rootfolder (utils/name-from-id rootfolders rootfolder-id)})))

(defn- resolve-format-rootfolder-paths
  "Always send both ebook and audiobook root folder paths on the author so the
  requested format lands in its configured folder and the other format has a
  valid path for future requests. Falls back to the user-selected root folder
  when a per-format default isn't set in config."
  [chosen-rootfolder-path]
  (let [{:chaptarr/keys [ebook-rootfolder audiobook-rootfolder]} @state/config]
    {:ebook-rootfolder-path     (or ebook-rootfolder chosen-rootfolder-path)
     :audiobook-rootfolder-path (or audiobook-rootfolder chosen-rootfolder-path)}))

(defn request [payload media-type]
  (a/go
    (let [{:keys [existing-book-id]} payload
          existing (when existing-book-id
                     (a/<! (impl/get-book-by-id existing-book-id)))
          current-status (when existing (impl/status existing media-type))
          rfs (a/<! (impl/rootfolders))
          chosen-rootfolder-path (utils/name-from-id rfs (:rootfolder-id payload))
          format-paths (resolve-format-rootfolder-paths chosen-rootfolder-path)
          submit-payload (-> payload
                             (assoc :media-type media-type)
                             (merge format-paths))]
      (if current-status
        current-status
        (->> (a/<! (impl/POST "/book" {:form-params (utils/to-camel (impl/request-payload submit-payload))
                                       :content-type :json}))
             (then (constantly nil)))))))
