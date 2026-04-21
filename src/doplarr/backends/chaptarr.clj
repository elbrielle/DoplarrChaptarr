(ns doplarr.backends.chaptarr
  (:require
   [clojure.core.async :as a]
   [doplarr.backends.chaptarr.impl :as impl]
   [doplarr.state :as state]
   [doplarr.utils :as utils]
   [taoensso.timbre :refer [info warn]]))

(defn- rootfolder-config-key [media-type]
  (if (= media-type :audiobook)
    :chaptarr/audiobook-rootfolder
    :chaptarr/ebook-rootfolder))

(defn- quality-profile-config-key [media-type]
  (if (= media-type :audiobook)
    :chaptarr/audiobook-quality-profile
    :chaptarr/ebook-quality-profile))

(defn- metadata-profile-config-key [media-type]
  (if (= media-type :audiobook)
    :chaptarr/audiobook-metadata-profile
    :chaptarr/ebook-metadata-profile))

(defn- quality-profiles-for
  "Filter quality profiles to the ones matching a given media-type.
  Chaptarr's quality profileType is a string: \"ebook\" or \"audiobook\"."
  [media-type profiles]
  (let [target (if (= media-type :audiobook) "audiobook" "ebook")]
    (filter #(= target (:profile-type %)) profiles)))

(defn- metadata-profiles-for
  "Filter metadata profiles to the ones matching a given media-type.
  Chaptarr's metadata profileType is an int: 0=None, 1=audiobook, 2=ebook."
  [media-type profiles]
  (let [target (if (= media-type :audiobook) 1 2)]
    (filter #(= target (:profile-type %)) profiles)))

(defn- auto-pick-profile
  "Resolve a profile id for a format we are NOT prompting the user about
  (always the 'other' format on a given request). Preference order: configured
  env name → single profile of this type → first profile of this type →
  first profile overall (a None-type fallback for fresh installs with no
  matching profile). Returns nil if the whole profile list is empty."
  [config-name all-profiles filtered-profiles]
  (or (and config-name (utils/id-from-name all-profiles config-name))
      (:id (first filtered-profiles))
      (:id (first all-profiles))))

(defn- resolve-requested-profile
  "For the profile the user IS being prompted about (the quality profile of
  the format they're requesting): prefer an env default, auto-pick if there
  is exactly one matching profile, otherwise return the filtered list so the
  interaction state machine renders a dropdown."
  [config-name all-profiles filtered-profiles]
  (let [env-id (and config-name (utils/id-from-name all-profiles config-name))]
    (cond
      env-id                         env-id
      (= 1 (count filtered-profiles)) (:id (first filtered-profiles))
      (seq filtered-profiles)         filtered-profiles
      :else                           (:id (first all-profiles)))))

(defn search [term _]
  (impl/lookup-book term))

(defn additional-options [_ media-type]
  (a/go
    (let [all-q-profiles (a/<! (impl/quality-profiles))
          all-m-profiles (a/<! (impl/metadata-profiles))
          rootfolders    (a/<! (impl/rootfolders))
          config         @state/config

          audiobook?       (= media-type :audiobook)
          ebook-q-profiles     (quality-profiles-for :book all-q-profiles)
          audiobook-q-profiles (quality-profiles-for :audiobook all-q-profiles)
          ebook-m-profiles     (metadata-profiles-for :book all-m-profiles)
          audiobook-m-profiles (metadata-profiles-for :audiobook all-m-profiles)

          ebook-q-cfg     (:chaptarr/ebook-quality-profile     config)
          audiobook-q-cfg (:chaptarr/audiobook-quality-profile config)
          ebook-m-cfg     (:chaptarr/ebook-metadata-profile    config)
          audiobook-m-cfg (:chaptarr/audiobook-metadata-profile config)
          rootfolder-cfg  (get config (rootfolder-config-key media-type))

          requested-q-cfg (get config (quality-profile-config-key media-type))
          default-root-folder (utils/id-from-name rootfolders rootfolder-cfg)]

      (when (and ebook-q-cfg (nil? (utils/id-from-name all-q-profiles ebook-q-cfg)))
        (warn (str "Chaptarr ebook quality profile `" ebook-q-cfg
                   "` not found in backend, check spelling")))
      (when (and audiobook-q-cfg (nil? (utils/id-from-name all-q-profiles audiobook-q-cfg)))
        (warn (str "Chaptarr audiobook quality profile `" audiobook-q-cfg
                   "` not found in backend, check spelling")))
      (when (and ebook-m-cfg (nil? (utils/id-from-name all-m-profiles ebook-m-cfg)))
        (warn (str "Chaptarr ebook metadata profile `" ebook-m-cfg
                   "` not found in backend, check spelling")))
      (when (and audiobook-m-cfg (nil? (utils/id-from-name all-m-profiles audiobook-m-cfg)))
        (warn (str "Chaptarr audiobook metadata profile `" audiobook-m-cfg
                   "` not found in backend, check spelling")))
      (when (and rootfolder-cfg (nil? default-root-folder))
        (warn (str "Chaptarr " (name media-type) " root folder `" rootfolder-cfg
                   "` not found in backend, check spelling")))

      (let [;; The user is only ever asked about one quality profile per request
            ;; — the one matching the format they're invoking. The other
            ;; format's quality profile is auto-resolved so the author POST
            ;; has all four required fields populated (Chaptarr silently
            ;; ignores the singular qualityProfileId, per §3.1 of handoff).
            requested-q-id (resolve-requested-profile
                            requested-q-cfg all-q-profiles
                            (if audiobook? audiobook-q-profiles ebook-q-profiles))
            other-q-id (auto-pick-profile
                        (if audiobook? ebook-q-cfg audiobook-q-cfg)
                        all-q-profiles
                        (if audiobook? ebook-q-profiles audiobook-q-profiles))
            ebook-q-id     (if audiobook? other-q-id     requested-q-id)
            audiobook-q-id (if audiobook? requested-q-id other-q-id)

            ;; Metadata profiles are never prompted — always auto-resolved
            ;; since they're purely edition-filtering config and users rarely
            ;; care to pick one at request time.
            ebook-m-id (auto-pick-profile ebook-m-cfg all-m-profiles ebook-m-profiles)
            audiobook-m-id (auto-pick-profile audiobook-m-cfg all-m-profiles audiobook-m-profiles)]
        {;; For the requested format, the key matches the dropdown label the
         ;; state machine derives from canonical-option-name — e.g.
         ;; :audiobook-quality-profile-id -> "audiobook quality profile?"
         :ebook-quality-profile-id     ebook-q-id
         :audiobook-quality-profile-id audiobook-q-id
         :ebook-metadata-profile-id    ebook-m-id
         :audiobook-metadata-profile-id audiobook-m-id
         :rootfolder-id
         (cond
           default-root-folder       default-root-folder
           (= 1 (count rootfolders)) (:id (first rootfolders))
           :else                     rootfolders)}))))

(defn- cover-bytes-or-nil
  "Attempt to download cover bytes from Chaptarr so they can be attached to
  the Discord embed as a file. Returns nil when the download fails — the
  embed renders without a cover in that case, which is strictly better UX
  than failing the whole request."
  [cover-path]
  (a/go
    (let [result (a/<! (impl/download-cover cover-path))]
      (when (and result (not (instance? Throwable result)))
        result))))

(defn- cover-poster-and-attachment
  "Decide how to present the book cover in the Discord embed based on what
  URL shape Chaptarr returned and what config is available:

  - absolute http(s) URL → hand it to Discord's :image.url directly; no
    attachment needed
  - relative path + CHAPTARR__PUBLIC_URL set → rewrite to absolute and use
    :image.url (same as above)
  - relative path + no public URL → download the bytes inside Doplarr's
    container (where Chaptarr is reachable) and attach them to the
    interaction response; embed :image.url becomes attachment://cover.jpeg

  If every path fails, the confirmation embed just omits the cover."
  [remote-cover]
  (a/go
    (let [absolute (impl/absolutify-cover-url remote-cover)]
      (cond
        absolute
        {:poster absolute}

        (and remote-cover (.startsWith ^String remote-cover "/"))
        (if-let [bytes (a/<! (cover-bytes-or-nil remote-cover))]
          {:poster "attachment://cover.jpeg"
           :cover-attachment {:bytes bytes
                              :filename "cover.jpeg"
                              :content-type "image/jpeg"}}
          {:poster nil})

        :else
        {:poster nil}))))

(defn request-embed [{:keys [title author-name overview remote-cover
                             ebook-quality-profile-id audiobook-quality-profile-id
                             ebook-metadata-profile-id audiobook-metadata-profile-id
                             rootfolder-id]}
                     media-type]
  (a/go
    (let [rootfolders (a/<! (impl/rootfolders))
          quality-profiles (a/<! (impl/quality-profiles))
          metadata-profiles (a/<! (impl/metadata-profiles))
          audiobook? (= media-type :audiobook)
          shown-q-id (if audiobook? audiobook-quality-profile-id ebook-quality-profile-id)
          shown-m-id (if audiobook? audiobook-metadata-profile-id ebook-metadata-profile-id)
          cover (a/<! (cover-poster-and-attachment remote-cover))]
      (cond-> {:title (if (and author-name (not (.contains (or title "") author-name)))
                        (str title " — " author-name)
                        title)
               :overview overview
               :poster (:poster cover)
               :media-type media-type
               :request-formats [""]
               :quality-profile (utils/name-from-id quality-profiles shown-q-id)
               :metadata-profile (utils/name-from-id metadata-profiles shown-m-id)
               :rootfolder (utils/name-from-id rootfolders rootfolder-id)}
        (:cover-attachment cover) (assoc :cover-attachment (:cover-attachment cover))))))

(defn- resolve-format-rootfolder-paths
  "Always send both ebook and audiobook root folder paths on the author so the
  requested format lands in its configured folder and the other format has a
  valid path for future requests. Falls back to the user-selected root folder
  when a per-format default isn't set in config."
  [chosen-rootfolder-path]
  (let [{:chaptarr/keys [ebook-rootfolder audiobook-rootfolder]} @state/config]
    {:ebook-rootfolder-path     (or ebook-rootfolder chosen-rootfolder-path)
     :audiobook-rootfolder-path (or audiobook-rootfolder chosen-rootfolder-path)}))

(defn- resolve-author-id
  "Either look up the author of an already-indexed book, or POST a new book
  to create the author + full catalog. Returns the author id in both cases.

  Important: the POST here creates the author and all of its book entities
  in an all-unmonitored state. Flipping the correct format's monitor flag
  and firing the search is deliberately a separate step — see
  CHAPTARR_INTEGRATION.md §3.6."
  [payload media-type format-paths]
  (a/go
    (if-let [existing-book-id (:existing-book-id payload)]
      (:author-id (a/<! (impl/get-book-by-id existing-book-id)))
      (let [submit-payload (-> payload
                               (assoc :media-type media-type)
                               (merge format-paths))
            post-body (utils/to-camel (impl/request-payload submit-payload))
            resp (a/<! (impl/POST "/book" {:form-params post-body
                                           :content-type :json}))]
        (impl/extract-author-id resp)))))

(defn request [payload media-type]
  (a/go
    (let [rfs (a/<! (impl/rootfolders))
          chosen-rootfolder-path (utils/name-from-id rfs (:rootfolder-id payload))
          format-paths (resolve-format-rootfolder-paths chosen-rootfolder-path)
          freshly-added? (nil? (:existing-book-id payload))
          author-id (a/<! (resolve-author-id payload media-type format-paths))
          ;; Must happen BEFORE the per-book PUT: Chaptarr silently rejects
          ;; book-level *Monitored=true when the author's *MonitorFuture flag
          ;; for that format is false. Idempotent — no-op on fresh author-adds
          ;; (request-payload already set the right flag) and does real work
          ;; on cross-format re-requests. See CHAPTARR_INTEGRATION.md §3.12.
          _ (when author-id
              (a/<! (impl/ensure-author-enabled-for-format author-id media-type)))
          ;; Chaptarr's author-add returns before the metadata source has
          ;; materialized real edition rows — only skeleton placeholders
          ;; exist for a few seconds. We poll /book?authorId=... until at
          ;; least one resolved edition for the requested format shows up,
          ;; otherwise we'd race into PUTing a placeholder that Chaptarr
          ;; silently drops. Cross-format re-requests against an existing
          ;; author skip this path — those books are already resolved.
          ;; See CHAPTARR_INTEGRATION.md §3.13.
          books (when author-id
                  (a/<! (if freshly-added?
                          (impl/wait-for-resolved-book author-id media-type)
                          (impl/books-for-author author-id))))
          target-book (impl/preferred-book-for-format books media-type)
          current-status (when target-book (impl/status target-book media-type))]
      (cond
        ;; Already monitored and downloaded/in-progress for this format
        current-status
        current-status

        ;; Found the book entity matching the requested format — PUT it
        ;; with monitored + the one correct format flag, then fire an
        ;; explicit BookSearch so Chaptarr actively grabs a release rather
        ;; than waiting for its next RSS cycle.
        target-book
        (let [target-id (:id target-book)
              candidate-count (count books)
              matching-count (count (filter #(impl/book-matches-format? % media-type) books))
              _ (info (str "Chaptarr request: selected book " target-id
                           " for " (name media-type) " request ("
                           matching-count "/" candidate-count " matching rows under author)"))
              put-resp (a/<! (impl/PUT (str "/book/" target-id)
                                       {:form-params (utils/to-camel
                                                      (impl/update-monitor-payload target-book media-type))
                                        :content-type :json}))
              ;; Chaptarr silently 2xxs PUTs that it internally rejects
              ;; (author gate, placeholder row, etc.). Verify the flag
              ;; actually flipped in the PUT response body so we get a
              ;; log signal when a future Chaptarr quirk causes a drop
              ;; instead of waiting for "no grab happened" diagnosis.
              updated (utils/from-camel (:body put-resp))
              flag-key (if (= media-type :audiobook)
                         :audiobook-monitored
                         :ebook-monitored)]
          (when-not (get updated flag-key)
            (warn (str "Chaptarr silently rejected monitor flip on book " target-id
                       " — " (name flag-key) " is still false in the PUT response. "
                       "Likely causes: author not enabled for this format, or book row "
                       "is a metadata placeholder. Check Chaptarr server logs.")))
          (a/<! (impl/search-book target-id))
          nil)

        ;; No book entity in Chaptarr's catalog matches the requested
        ;; format. Could happen when a title is ebook-only and the user
        ;; asked for the audiobook, or vice versa. Surface this as a
        ;; user-facing error via the 403 branch of the state machine.
        :else
        (throw (ex-info
                "Chaptarr has no matching format for this title"
                {:status 403
                 :body {"message"
                        (str "Chaptarr doesn't have this title available as "
                             (case media-type :audiobook "an audiobook" "an ebook")
                             ". Try the other format, or check Chaptarr's "
                             "metadata source for edition availability.")}}))))))
