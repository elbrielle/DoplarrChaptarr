(ns doplarr.backends.chaptarr.impl
  (:require
   [clojure.core.async :as a]
   [clojure.string :as str]
   [doplarr.state :as state]
   [doplarr.utils :as utils]
   [fmnoise.flow :refer [then]]))

(def base-url (delay (str (:chaptarr/url @state/config) "/api/v1")))
(def api-key  (delay (:chaptarr/api @state/config)))

(defn GET [endpoint & [params]]
  (utils/http-request :get (str @base-url endpoint) @api-key params))

(defn POST [endpoint & [params]]
  (utils/http-request :post (str @base-url endpoint) @api-key params))

(defn PUT [endpoint & [params]]
  (utils/http-request :put (str @base-url endpoint) @api-key params))

(defn- process-chaptarr-profile
  "Chaptarr profiles carry a profileType discriminator we must preserve for
  per-format routing. Quality endpoint returns profileType as a string
  (\"ebook\"/\"audiobook\"); metadata endpoint returns it as an int
  (0=None, 1=audiobook, 2=ebook). Both are passed through as-is."
  [profile]
  (-> profile
      (select-keys ["id" "name" "profileType"])
      utils/from-camel))

(defn quality-profiles []
  (utils/request-and-process-body
   GET
   #(map process-chaptarr-profile %)
   "/qualityprofile"))

(defn metadata-profiles []
  (utils/request-and-process-body
   GET
   #(map process-chaptarr-profile %)
   "/metadataprofile"))

(defn rootfolders []
  (utils/request-and-process-body
   GET
   utils/process-rootfolders
   "/rootfolder"))

(defn- year-from-release-date [release-date]
  (when (and (string? release-date) (>= (count release-date) 4))
    (subs release-date 0 4)))

(defn- cover-url
  "Readarr-style book lookup stores covers inside an images array, not a flat
  remoteCover field. Preference order: explicit coverType=\"cover\" at the book
  level, then the book's first image, then any edition cover, then the legacy
  remoteCover field just in case a Chaptarr build still emits it."
  [kebab]
  (or (->> (:images kebab)
           (filter #(= "cover" (:cover-type %)))
           first :url)
      (->> (:images kebab) first :url)
      (->> (:editions kebab)
           (mapcat :images)
           (filter #(= "cover" (:cover-type %)))
           first :url)
      (->> (:editions kebab)
           (mapcat :images)
           first :url)
      (:remote-cover kebab)))

(defn absolutify-cover-url
  "Chaptarr returns cover paths like /MediaCoverProxy/.../...jpeg — relative to
  the Chaptarr server. Discord's embed API rejects non-absolute URLs with a
  50035 Invalid Form Body on the image field, so we have to either prepend a
  publicly-reachable Chaptarr base URL or drop the image entirely.

  Returns:
  - the URL unchanged if it's already absolute (http:// or https://)
  - CHAPTARR__PUBLIC_URL prepended (trailing slash trimmed) if the user has
    configured a publicly-reachable Chaptarr address and the path is relative
  - nil if the path is relative and no public URL is configured — the caller
    should omit :image from the embed entirely rather than sending a relative
    or null URL"
  [url]
  (when (string? url)
    (cond
      (re-find #"^https?://" url) url
      (str/starts-with? url "/")
      (when-let [public-url (:chaptarr/public-url @state/config)]
        (str (str/replace public-url #"/$" "") url))
      :else nil)))

(defn- trim-or-nil [s]
  (when (string? s)
    (let [trimmed (str/trim s)]
      (when-not (str/blank? trimmed) trimmed))))

(defn process-book-search-result
  "Normalize Chaptarr's /book/lookup output into the shape Doplarr expects.

  Chaptarr (Readarr fork) returns a book object with an embedded author. We
  flatten the pieces needed downstream. `existing-book-id` is set only when
  the book is already in the library (Chaptarr uses id=0 for not-yet-added
  books). Titles and author names are trimmed because Chaptarr's metadata
  source sometimes emits leading whitespace (observed with titles like
  \" The Book of Lost Hours\")."
  [result]
  (let [kebab (utils/from-camel result)
        existing-id (:id kebab)]
    {:title (trim-or-nil (:title kebab))
     :year (year-from-release-date (:release-date kebab))
     :foreign-book-id (:foreign-book-id kebab)
     :foreign-author-id (get-in kebab [:author :foreign-author-id])
     :author-name (trim-or-nil (get-in kebab [:author :author-name]))
     :title-slug (:title-slug kebab)
     :overview (:overview kebab)
     :remote-cover (cover-url kebab)
     :existing-book-id (when (and (number? existing-id) (pos? existing-id))
                         existing-id)}))

(defn lookup-book [term]
  (utils/request-and-process-body
   GET
   #(map process-book-search-result %)
   "/book/lookup"
   {:query-params {:term term}}))

(defn get-book-by-id [id]
  (utils/request-and-process-body
   GET
   utils/from-camel
   (str "/book/" id)))

(defn status
  "Determine whether a book already added to Chaptarr is available/processing
  for the requested format. Returns nil when the book is not yet added or not
  monitored for this format. Uses format-specific statistics only — the
  book-level hasFile field is format-agnostic in Readarr/Chaptarr (true when
  any format has a file) and would cross-contaminate the format-specific
  contract if consulted here."
  [details media-type]
  (let [audiobook? (= media-type :audiobook)
        monitored? (if audiobook?
                     (:audiobook-monitored details)
                     (:ebook-monitored details))
        stats      (if audiobook?
                     (:audiobook-statistics details)
                     (:ebook-statistics details))
        file-count (or (:book-file-count stats) 0)]
    (when monitored?
      (if (pos? file-count) :available :processing))))

(defn update-monitor-payload
  "Given an existing book record (kebab-cased, fetched via /book/{id}), return
  a modified copy with the requested format's monitor flag flipped on and the
  book-level monitored flag ensured true. Preserves any other format flag
  already set so we never clobber a monitor the user established earlier."
  [existing media-type]
  (-> existing
      (assoc :monitored true)
      (assoc (if (= media-type :audiobook)
               :audiobook-monitored
               :ebook-monitored)
             true)))

(defn search-book
  "Trigger Chaptarr's active BookSearch command for a book id. Used after a
  PUT that flips on a new format's monitor flag so Chaptarr actually grabs
  the newly-monitored format rather than waiting for RSS."
  [book-id]
  (a/go
    (->> (a/<! (POST "/command" {:form-params {:name "BookSearch"
                                               :bookIds [book-id]}
                                 :content-type :json}))
         (then (constantly nil)))))

(defn request-payload
  "Build the POST /api/v1/book body for Chaptarr.

  Chaptarr's author model has separate quality and metadata profile IDs for
  ebooks and audiobooks. The singular qualityProfileId / metadataProfileId
  accepted by Readarr and other *arrs is silently ignored — we must send all
  four per-format IDs explicitly or the author lands without usable config
  (verified against a Chaptarr 0.9.418 author record).

  Also sets the format-specific monitor flag (ebookMonitored or
  audiobookMonitored) and includes both ebook and audiobook root folder
  paths on the author so future requests for the other format land correctly.
  monitorNewItems=\"none\" and addOptions.monitor=\"specificBook\" prevent
  flooding the library with every book from the added author — per
  CHAPTARR_KNOWLEDGE.md §7."
  [payload]
  (let [{:keys [title foreign-book-id foreign-author-id author-name
                ebook-quality-profile-id audiobook-quality-profile-id
                ebook-metadata-profile-id audiobook-metadata-profile-id
                ebook-rootfolder-path audiobook-rootfolder-path
                media-type]} payload
        audiobook? (= media-type :audiobook)
        ebook?     (= media-type :book)
        chosen-rootfolder (if audiobook?
                            audiobook-rootfolder-path
                            ebook-rootfolder-path)]
    {:title title
     :foreign-book-id foreign-book-id
     :monitored true
     :ebook-monitored ebook?
     :audiobook-monitored audiobook?
     :root-folder-path chosen-rootfolder
     :ebook-quality-profile-id    ebook-quality-profile-id
     :audiobook-quality-profile-id audiobook-quality-profile-id
     :ebook-metadata-profile-id    ebook-metadata-profile-id
     :audiobook-metadata-profile-id audiobook-metadata-profile-id
     :author {:author-name author-name
              :foreign-author-id foreign-author-id
              :ebook-quality-profile-id    ebook-quality-profile-id
              :audiobook-quality-profile-id audiobook-quality-profile-id
              :ebook-metadata-profile-id    ebook-metadata-profile-id
              :audiobook-metadata-profile-id audiobook-metadata-profile-id
              :root-folder-path chosen-rootfolder
              :ebook-root-folder-path    ebook-rootfolder-path
              :audiobook-root-folder-path audiobook-rootfolder-path
              :ebook-monitor-future false
              :audiobook-monitor-future false
              :monitored true
              :monitor-new-items "none"
              :add-options {:monitor "specificBook"
                            :search-for-missing-books false}}
     :add-options {:search-for-new-book true}}))
