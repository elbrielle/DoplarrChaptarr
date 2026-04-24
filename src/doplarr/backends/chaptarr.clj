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

      (let [;; Prompt for the requested format's quality profile only.
            ;; Auto-resolve the other format's so the POST body carries
            ;; all four per-format profile ids (Chaptarr silently ignores
            ;; the singular qualityProfileId field).
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
  "Find (or create) the Chaptarr author id for this request. Returns
  `{:author-id <id> :posted? <bool> :via <keyword>}`. Paths tried in
  order:

    :existing-book-id    — payload already has a book id (lookup or
                           request-embed stash). GET, read authorId.
    :existing-author-id  — lookup's author.id was populated (rare).
    :foreign-author-id   — foreignAuthorId equality against
                           GET /author.
    :author-name         — normalized author-name, single match.
                           Bridges cross-provider author-id drift.
    :post                — genuinely new; POST /book creates the
                           author and seeds its catalog."
  [payload media-type format-paths]
  (a/go
    (cond
      (:existing-book-id payload)
      {:author-id (:author-id (a/<! (impl/get-book-by-id (:existing-book-id payload))))
       :posted? false
       :via :existing-book-id}

      (:existing-author-id payload)
      {:author-id (:existing-author-id payload)
       :posted? false
       :via :existing-author-id}

      :else
      (if-let [match (a/<! (impl/find-existing-author
                            (:foreign-author-id payload)
                            (:author-name payload)))]
        {:author-id (:id match) :posted? false :via (:via match)}
        (let [submit-payload (-> payload
                                 (assoc :media-type media-type)
                                 (merge format-paths))
              post-body (utils/to-camel (impl/request-payload submit-payload))
              resp (a/<! (impl/POST "/book" {:form-params post-body
                                             :content-type :json}))]
          {:author-id (impl/extract-author-id resp) :posted? true :via :post})))))

(defn- resolve-target-book!
  "Resolve the author, list its books, pick the best row for this
  request. Called twice per request: once from `request-embed`
  (pre-POST so the embed can use absolute cover URLs) and once from
  `request` (fast-path via the cached :existing-book-id). Returns
  `{:author-id ..., :target-book ...}`.

  Polling only runs when we actually POSTed a new author; the
  existing-author paths hit Chaptarr's current catalog directly."
  [payload media-type]
  (a/go
    (let [rfs (a/<! (impl/rootfolders))
          chosen-rootfolder-path (utils/name-from-id rfs (:rootfolder-id payload))
          format-paths (resolve-format-rootfolder-paths chosen-rootfolder-path)
          requested-title (:raw-title payload)
          {author-id :author-id posted? :posted? via :via}
          (a/<! (resolve-author-id payload media-type format-paths))
          books (when author-id
                  (a/<! (if posted?
                          (impl/wait-for-resolved-book author-id media-type requested-title)
                          (impl/books-for-author author-id))))
          target-book (impl/preferred-book-for-format books media-type requested-title)]
      (info (str "Chaptarr resolve-target-book!: media-type=" media-type
                 " existing-book-id=" (:existing-book-id payload)
                 " existing-author-id=" (:existing-author-id payload)
                 " foreign-author-id=" (pr-str (:foreign-author-id payload))
                 " author-name=" (pr-str (:author-name payload))
                 " via=" via
                 " posted?=" posted?
                 " requested-title=" (pr-str requested-title)
                 " author-id=" author-id
                 " total-books=" (count books)
                 " target-book-id=" (:id target-book)
                 " target-title=" (pr-str (:title target-book))
                 " target-media-type=" (pr-str (:media-type target-book))
                 " target-ebook-monitored=" (:ebook-monitored target-book)
                 " target-audiobook-monitored=" (:audiobook-monitored target-book)))
      {:author-id author-id
       :target-book target-book})))

(defn request-embed [payload media-type]
  (a/go
    (let [{:keys [title author-name overview remote-cover
                  ebook-quality-profile-id audiobook-quality-profile-id
                  ebook-metadata-profile-id audiobook-metadata-profile-id
                  rootfolder-id sm-uuid]} payload
          rootfolders (a/<! (impl/rootfolders))
          quality-profiles (a/<! (impl/quality-profiles))
          metadata-profiles (a/<! (impl/metadata-profiles))
          audiobook? (= media-type :audiobook)
          shown-q-id (if audiobook? audiobook-quality-profile-id ebook-quality-profile-id)
          shown-m-id (if audiobook? audiobook-metadata-profile-id ebook-metadata-profile-id)
          ;; Run POST + pick now so we have a resolved row with edition
          ;; identifiers (isbn13, asin) by embed-render time. Lookup
          ;; results don't carry these fields, so deferring the POST
          ;; would strip the public-CDN cover fallback of its inputs.
          pre-request (try
                        (a/<! (resolve-target-book! payload media-type))
                        (catch Throwable e
                          (warn (str "request-embed: pre-request POST failed, "
                                     "continuing without a resolved row — "
                                     (.getMessage e)))
                          nil))
          target-book (:target-book pre-request)
          ;; Prefer an absolute URL from the resolved row (Hardcover /
          ;; Amazon / Goodreads CDN). If Chaptarr only has the relative
          ;; /MediaCoverProxy/... proxy path, fall back to a public CDN
          ;; keyed off the edition's ISBN or ASIN. Lookup's remote-cover
          ;; is the last resort — it's usually the same proxy path so
          ;; only useful when target-book failed to resolve at all.
          resolved-cover (impl/cover-url target-book)
          poster (cond
                   (impl/absolute-cover-url? resolved-cover) resolved-cover
                   target-book (impl/public-cover-url target-book)
                   (impl/absolute-cover-url? remote-cover) remote-cover
                   :else nil)]
      ;; Stash the resolved book id so the Request-click handler takes
      ;; the :existing-book-id fast path (skips POST + polling). When
      ;; target-book didn't resolve but author did, fall back to any
      ;; book under the author so `request` doesn't re-POST and 409 on
      ;; duplicate foreignBookId.
      (when sm-uuid
        (let [target-id (:id target-book)
              fallback-id (when (and (not target-id) (:author-id pre-request))
                            (:id (first (a/<! (impl/books-for-author
                                               (:author-id pre-request))))))
              stash-id (or target-id fallback-id)]
          (when stash-id
            (swap! state/cache assoc-in [sm-uuid :payload :existing-book-id] stash-id))))
      {:title (if (and author-name (not (.contains (or title "") author-name)))
                (str title " — " author-name)
                title)
       :overview overview
       :poster poster
       :media-type media-type
       :request-formats [""]
       :quality-profile (utils/name-from-id quality-profiles shown-q-id)
       :metadata-profile (utils/name-from-id metadata-profiles shown-m-id)
       :rootfolder (utils/name-from-id rootfolders rootfolder-id)})))

(defn- remediate-placeholder-target!
  "Fire a `RefreshAuthor` command and poll for the requested title to
  resolve into a non-placeholder row. Returns a re-selected
  target-book on success, or nil if it stays a placeholder.

  Chaptarr's POST /book returns immediately but the upstream metadata
  source (Hardcover / Goodreads / AudiMeta) may take seconds to
  populate a real edition — or never populate one at all. Without
  this, stale-monitored placeholders short-circuit the request with
  a misleading \"this is currently processing\" message.

  Chaptarr metadata is author-level (per community Discord), and
  `RefreshBook` throws errors in community reports, so we refresh
  the whole author. Gated on `(not book-row-complete?)` because
  `RefreshAuthor` can cull editions per the metadata profile — safe
  only when the target has no editions to lose."
  [author-id media-type requested-title]
  (a/go
    (info (str "Chaptarr remediate-placeholder-target!: firing RefreshAuthor "
               "on author-id=" author-id " for title=" (pr-str requested-title)
               " — current target is an unresolved placeholder"))
    (a/<! (impl/refresh-author author-id))
    (let [books (a/<! (impl/wait-for-resolved-book
                       author-id media-type requested-title
                       {:max-attempts 20 :interval-ms 1000}))
          remediated (impl/preferred-book-for-format
                      books media-type requested-title)]
      (if (impl/book-row-complete? remediated)
        (do
          (info (str "Chaptarr remediate-placeholder-target!: resolved — "
                     "new target-book-id=" (:id remediated)
                     " target-title=" (pr-str (:title remediated))
                     " foreign-edition-id=" (pr-str (:foreign-edition-id remediated))))
          remediated)
        (do
          (warn (str "Chaptarr remediate-placeholder-target!: RefreshAuthor did "
                     "not resolve a complete row for title="
                     (pr-str requested-title) " within the cap — metadata "
                     "source likely cannot supply this edition. "
                     "target-book-id=" (:id remediated)
                     " foreign-edition-id=" (pr-str (:foreign-edition-id remediated))))
          nil)))))

(defn- placeholder-unresolved-error
  "Build the ex-info thrown when placeholder remediation fails. Surfaces a
  clearer message than the generic :processing short-circuit so the user
  knows the request didn't silently disappear into Chaptarr's queue."
  [media-type]
  (ex-info
   "Chaptarr has only a placeholder row for this book and couldn't resolve it"
   {:status 403
    :body {"message"
           (str "Chaptarr couldn't resolve this "
                (case media-type :audiobook "audiobook" "ebook")
                "'s metadata. Try refreshing the author in Chaptarr — "
                "if that doesn't help, the upstream source may not carry "
                "this edition.")}}))

(defn request [payload media-type]
  (a/go
    ;; Catch everything and return the Throwable as a channel value —
    ;; core.async's ioc-macro exception routing leaks when a
    ;; continuation runs on a hato HTTP worker thread, and the state
    ;; machine's `a/<!!` would hang until Discord's ack timeout fires.
    ;; `ex-info` with `:status 403 :body {"message" ...}` renders as an
    ;; ephemeral Discord reply via the state machine's `else` branch.
    (try
      (let [{:keys [author-id target-book]} (a/<! (resolve-target-book! payload media-type))
            requested-title (:raw-title payload)
            ;; Give Chaptarr one chance to resolve placeholder rows
            ;; before reading their (stale) monitor state.
            target-book (if (and target-book (not (impl/book-row-complete? target-book)))
                          (or (a/<! (remediate-placeholder-target!
                                     author-id media-type requested-title))
                              (throw (placeholder-unresolved-error media-type)))
                          target-book)
            ;; Author-level *MonitorFuture flag must be true for the
            ;; format, or Chaptarr silently drops the per-book monitor
            ;; PUT. Idempotent when already set.
            _ (when author-id
                (a/<! (impl/ensure-author-enabled-for-format author-id media-type)))
            current-status (when target-book (impl/status target-book media-type))]
        (cond
          current-status
          (do (info (str "Chaptarr request: short-circuiting on status=" current-status
                         " for book " (:id target-book) " ('" (:title target-book) "')"))
              current-status)

          target-book
          (let [target-id (:id target-book)
                flag-key (if (= media-type :audiobook) :audiobook-monitored :ebook-monitored)]
            (info (str "Chaptarr request: monitoring book " target-id
                       " ('" (:title target-book) "') for " (name media-type)
                       " request (requested-title='" (:raw-title payload) "')"))
            (a/<! (impl/monitor-book target-id))
            ;; /book/monitor returns 202 with only a status snippet;
            ;; re-GET to verify the flip landed, WARN on silent drop.
            (let [verified (a/<! (impl/get-book-by-id target-id))]
              (when-not (get verified flag-key)
                (warn (str "Chaptarr silently rejected monitor flip on book " target-id
                           " — " (name flag-key) " is still false after PUT /book/monitor."))))
            (a/<! (impl/search-book target-id))
            nil)

          :else
          (throw (ex-info
                  "Chaptarr has no matching format for this title"
                  {:status 403
                   :body {"message"
                          (str "Chaptarr doesn't have this title as "
                               (case media-type :audiobook "an audiobook" "an ebook")
                               ". Try the other format.")}}))))
      (catch Throwable e
        (warn (str "Chaptarr request: returning Throwable to channel — "
                   (.getMessage e)
                   (when-let [d (ex-data e)]
                     (str " " (pr-str (select-keys d [:status]))))))
        e))))
