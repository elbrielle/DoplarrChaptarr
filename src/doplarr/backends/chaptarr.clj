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
  "Resolve the Chaptarr author id for this request AND report how we
  found it. Four paths, tried in order:

  1. `:existing-book-id` set — lookup returned a book already indexed,
     OR request-embed stashed it for the fast click-time path. GET the
     book, read author-id. `:posted? false`, `:via :existing-book-id`.
  2. `:existing-author-id` set (rare; lookup's `author.id` was non-zero).
     `:posted? false`, `:via :existing-author-id`.
  3. `impl/find-existing-author` matches against Chaptarr's indexed
     author list — tries foreignAuthorId first, then single-result
     normalized author-name match. Handles cross-provider cases where
     lookup says `gr:38550` but Chaptarr stored the same author as
     `hc:204214` (Live Test 14 Brandon Sanderson). `:posted? false`,
     `:via` is the sub-path keyword from `find-existing-author`.
  4. Otherwise — genuinely new author. POST /book to create the author
     and their full catalog as placeholders. `:posted? true`,
     `:via :post`.

  Returns `{:author-id <id-or-nil> :posted? <bool> :via <keyword>}`.
  See §3.19 for the motivation and tradeoffs."
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
  "POST the author (if not already indexed), wait for the specific requested
  title to resolve, and return the target book row + author id.

  Idempotent on the existing-book-id path: when the payload already carries
  an existing-book-id (either because the book was already in Chaptarr's
  library when the user searched, OR because request-embed already ran and
  stashed the id back into the cached payload), we skip the POST and just
  look up. This is the fast path used by `request` after `request-embed`
  has pre-POSTed.

  Returns a channel yielding {:author-id ..., :target-book ...}. Throws on
  network/POST failure — callers decide whether to surface or fall back.

  See CHAPTARR_INTEGRATION.md §3.17 for the request-embed integration."
  [payload media-type]
  (a/go
    (let [rfs (a/<! (impl/rootfolders))
          chosen-rootfolder-path (utils/name-from-id rfs (:rootfolder-id payload))
          format-paths (resolve-format-rootfolder-paths chosen-rootfolder-path)
          requested-title (:raw-title payload)
          ;; resolve-author-id returns {:author-id, :posted?, :via}.
          ;; Only poll when `posted?` is true (actually created a new
          ;; author). The no-POST paths hit Chaptarr's current catalog
          ;; directly — metadata refresh happened or failed long ago
          ;; and polling would waste time. `:via` feeds the log below
          ;; so we can see which resolution path fired. §3.19.
          {author-id :author-id posted? :posted? via :via}
          (a/<! (resolve-author-id payload media-type format-paths))
          books (when author-id
                  (a/<! (if posted?
                          (impl/wait-for-resolved-book author-id media-type requested-title)
                          (impl/books-for-author author-id))))
          target-book (impl/preferred-book-for-format books media-type requested-title)]
      ;; One unified log per call so both request-embed's pre-POST and
      ;; request's click-time calls emit traceable lines. When something
      ;; short-circuits downstream (status=:processing, 403, etc.), we can
      ;; match the log pair to see where the divergence happened.
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
          ;; Do the POST + poll + pick NOW (before rendering the
          ;; confirmation embed) so we have a resolved book row whose
          ;; images[] carries absolute upstream URLs, not the relative
          ;; /MediaCoverProxy/... paths that /book/lookup returns.
          ;; Chaptarr builds with Plex auth 401 the proxy path, which
          ;; means the only way to get a working cover in Discord is to
          ;; use the post-add row's absolute URL. See §3.14 and §3.17.
          ;;
          ;; If the POST fails (network, invalid payload, etc.), degrade:
          ;; fall back to the lookup's remote-cover path and the attach /
          ;; public-url mitigations in `cover-poster-and-attachment`. The
          ;; Request-click handler will re-attempt the POST and surface
          ;; the real error to the user with a proper failure message.
          pre-request (try
                        (a/<! (resolve-target-book! payload media-type))
                        (catch Throwable e
                          (warn (str "request-embed: pre-request POST failed, "
                                     "falling back to lookup cover URL — "
                                     (.getMessage e)))
                          nil))
          target-book (:target-book pre-request)
          ;; Absolute URL from the resolved row beats the lookup's
          ;; relative path. Fall back to lookup if the pre-request didn't
          ;; resolve a row (POST failed, or polling timed out without
          ;; finding a match — which also warns in wait-for-resolved-book).
          effective-cover (or (impl/cover-url target-book) remote-cover)
          cover (a/<! (cover-poster-and-attachment effective-cover))]
      ;; Stash the resolved book id back into the cached payload so the
      ;; Request-click handler in `request` takes the existing-book-id
      ;; fast path (skip POST, skip polling — both already done here).
      ;; If target-book didn't resolve but the author POST succeeded,
      ;; fall back to stashing any book id under the author — this
      ;; prevents `request` from re-POSTing and 409-ing on duplicate
      ;; foreignBookId. `request` will still run the selection logic,
      ;; and by click-time Chaptarr may have finished resolving the
      ;; requested title.
      (when sm-uuid
        (let [target-id (:id target-book)
              fallback-id (when (and (not target-id) (:author-id pre-request))
                            (:id (first (a/<! (impl/books-for-author
                                               (:author-id pre-request))))))
              stash-id (or target-id fallback-id)]
          (when stash-id
            (swap! state/cache assoc-in [sm-uuid :payload :existing-book-id] stash-id))))
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

(defn request [payload media-type]
  (a/go
    (let [{:keys [author-id target-book]} (a/<! (resolve-target-book! payload media-type))
          ;; Must happen BEFORE the per-book PUT: Chaptarr silently
          ;; rejects book-level *Monitored=true when the author's
          ;; *MonitorFuture flag for that format is false. Idempotent
          ;; — no-op when the flag is already set. See §3.12.
          _ (when author-id
              (a/<! (impl/ensure-author-enabled-for-format author-id media-type)))
          raw-status (when target-book (impl/status target-book media-type))
          ;; Defensive cross-check: `impl/status` should only return
          ;; :available/:processing when the requested-format monitor
          ;; flag is true on the target row. If it ever returns a
          ;; truthy value while that flag is false, something's wrong
          ;; (stale cache, cross-format contamination, wrong target
          ;; picked upstream) and we'd lie to the user with a "this is
          ;; currently processing" message even though nothing is.
          ;; Ignore the status and log a loud warning so the
          ;; underlying bug surfaces instead of being hidden. See
          ;; Existing-Author Regression report, 2026-04-22.
          format-flag (when target-book
                        (if (= media-type :audiobook)
                          (:audiobook-monitored target-book)
                          (:ebook-monitored target-book)))
          current-status (cond
                           (nil? raw-status) nil
                           format-flag raw-status
                           :else
                           (do
                             (warn (str "Chaptarr request: suppressing bogus status="
                                        raw-status " for book " (:id target-book)
                                        " ('" (:title target-book) "') — requested "
                                        (name media-type) " monitor flag is false "
                                        "(ebookMonitored=" (:ebook-monitored target-book)
                                        ", audiobookMonitored=" (:audiobook-monitored target-book)
                                        "). Falling through to normal PUT path."))
                             nil))]
      (cond
        ;; Already monitored and downloaded/in-progress for this format
        current-status
        (do
          (info (str "Chaptarr request: short-circuiting on status=" current-status
                     " for book " (:id target-book) " ('" (:title target-book) "')"))
          current-status)

        ;; Found the book entity matching the requested format — PUT it
        ;; with monitored + the one correct format flag, then fire an
        ;; explicit BookSearch so Chaptarr actively grabs a release rather
        ;; than waiting for its next RSS cycle.
        target-book
        (let [target-id (:id target-book)
              _ (info (str "Chaptarr request: monitoring book " target-id
                           " ('" (:title target-book) "') for "
                           (name media-type) " request (requested-title='"
                           (:raw-title payload) "')"))
              _ (a/<! (impl/monitor-book target-id))
              ;; `/book/monitor` returns 202 Accepted with only a short
              ;; status snippet, not the updated book record, so we have
              ;; to re-GET the book to verify the flip landed. Keeps the
              ;; "silently rejected" warning useful for future Chaptarr
              ;; quirks (placeholder-row drop, unexpected author gate,
              ;; etc.) rather than waiting for "no grab happened" logs.
              verified (a/<! (impl/get-book-by-id target-id))
              flag-key (if (= media-type :audiobook)
                         :audiobook-monitored
                         :ebook-monitored)]
          (when-not (get verified flag-key)
            (warn (str "Chaptarr silently rejected monitor flip on book " target-id
                       " — " (name flag-key) " is still false after PUT /book/monitor. "
                       "Unexpected under the new endpoint; capture full book + author "
                       "state and see CHAPTARR_INTEGRATION.md §3.15.")))
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
