(ns doplarr.backends.chaptarr.impl
  (:require
   [clojure.core.async :as a]
   [clojure.string :as str]
   [doplarr.state :as state]
   [doplarr.utils :as utils]
   [fmnoise.flow :refer [then]]
   [hato.client :as hc]
   [taoensso.timbre :refer [warn]]))

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

(defn- strip-html
  "Remove HTML tags and decode a handful of common entities from a description
  string. Chaptarr's book overview comes from upstream metadata sources that
  occasionally emit malformed markup (e.g. unclosed <i> tags) — running it
  through a strict HTML parser would choke. A simple tag-stripping regex plus
  entity decode produces a predictable plain-text output that Discord embed
  descriptions render cleanly."
  [s]
  (when (string? s)
    (-> s
        (str/replace #"<[^>]+>" "")
        (str/replace #"&nbsp;" " ")
        (str/replace #"&amp;" "&")
        (str/replace #"&quot;" "\"")
        (str/replace #"&#39;" "'")
        (str/replace #"&apos;" "'")
        (str/replace #"&lt;" "<")
        (str/replace #"&gt;" ">")
        (str/replace #"\s+" " ")
        str/trim)))

(defn process-book-search-result
  "Normalize Chaptarr's /book/lookup output into the shape Doplarr expects.

  Chaptarr (Readarr fork) returns a book object with an embedded author. We
  flatten the pieces needed downstream.

  Post-processing applied here:
  - Trim leading/trailing whitespace on title and author-name (Chaptarr's
    metadata source occasionally emits leading spaces, e.g. \" The Book of
    Lost Hours\").
  - Build :title as \"Title — Author\" when the author is known, so the
    Discord search dropdown disambiguates same-title results without needing
    a separate template in discord.clj.
  - Strip HTML tags from the overview; Chaptarr sources can contain malformed
    markup that Discord embed descriptions would render literally.
  - `existing-book-id` is set only when the book is already in the library
    (Chaptarr uses id=0 for not-yet-added books)."
  [result]
  (let [kebab (utils/from-camel result)
        existing-id (:id kebab)
        raw-title (trim-or-nil (:title kebab))
        author-name (trim-or-nil (get-in kebab [:author :author-name]))
        display-title (if (and raw-title author-name)
                        (str raw-title " — " author-name)
                        raw-title)]
    {:title display-title
     :raw-title raw-title
     :year (year-from-release-date (:release-date kebab))
     :foreign-book-id (:foreign-book-id kebab)
     :foreign-author-id (get-in kebab [:author :foreign-author-id])
     :author-name author-name
     :title-slug (:title-slug kebab)
     :overview (strip-html (:overview kebab))
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

(defn get-author [author-id]
  (utils/request-and-process-body
   GET
   utils/from-camel
   (str "/author/" author-id)))

(defn ensure-author-enabled-for-format
  "Chaptarr refuses to enable per-book monitoring when the author-level
  *MonitorFuture flag for that format is false — the PUT succeeds over
  HTTP but the change is silently dropped, logged on the Chaptarr side
  as 'author is not monitored for ebooks' (or audiobooks). This helper
  fetches the author record and, if the relevant flag isn't already set,
  PUTs the author with it flipped to true before the caller proceeds to
  flip a specific book's monitor flag.

  Idempotent — no-op when the flag is already true, which is the common
  case for fresh author-adds (request-payload sets it based on the
  requested format). Does real work on cross-format re-requests
  (/request book then later /request audiobook on the same author)
  where the author was originally added with only one format enabled.

  See CHAPTARR_INTEGRATION.md §3.12."
  [author-id media-type]
  (a/go
    (let [author (a/<! (get-author author-id))
          flag-key (if (= media-type :audiobook)
                     :audiobook-monitor-future
                     :ebook-monitor-future)]
      (if (get author flag-key)
        nil
        (let [updated (assoc author
                             flag-key true
                             :monitored true)]
          (a/<! (PUT (str "/author/" author-id)
                     {:form-params (utils/to-camel updated)
                      :content-type :json})))))))

(defn request-payload
  "Build the POST /api/v1/book body for Chaptarr, used to create the author
  and its catalog of book entities in an all-unmonitored state.

  Two things this payload explicitly does NOT do, despite looking like it
  should:

  1. It does not set book-level monitor flags to true. Chaptarr's
     AddBookService, when given monitored=true on a POST, auto-sets the
     format-specific flag on every matching edition regardless of what the
     caller specified — a single POST ends up with both ebookMonitored and
     audiobookMonitored flipped on different book rows. See
     CHAPTARR_INTEGRATION.md §3.6. To get format-specific monitoring, the
     fork POSTs everything unmonitored, then PUTs the specific book entity
     for the requested format afterwards.
  2. It does not rely on addOptions.searchForNewBook. Observed to
     add-and-monitor without firing a release search in practice. The fork
     fires an explicit /command BookSearch after the PUT (see
     `search-book`).

  The author record still needs all four per-format profile IDs populated
  or it lands without usable config — the singular qualityProfileId /
  metadataProfileId fields are silently ignored. See
  CHAPTARR_INTEGRATION.md §3.2."
  [payload]
  (let [{:keys [title raw-title foreign-book-id foreign-author-id author-name
                ebook-quality-profile-id audiobook-quality-profile-id
                ebook-metadata-profile-id audiobook-metadata-profile-id
                ebook-rootfolder-path audiobook-rootfolder-path
                media-type]} payload
        chosen-rootfolder (if (= media-type :audiobook)
                            audiobook-rootfolder-path
                            ebook-rootfolder-path)]
    {:title (or raw-title title)
     :foreign-book-id foreign-book-id
     ;; All monitor flags false — the caller PUTs the specific book entity
     ;; afterwards to flip only the format the user actually requested.
     :monitored false
     :ebook-monitored false
     :audiobook-monitored false
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
              ;; *MonitorFuture is Chaptarr's author-level gate for per-book
              ;; monitoring of that format. Setting it false makes Chaptarr
              ;; silently reject book-level ebookMonitored=true PUTs later
              ;; (logged as "author is not monitored for ebooks"). Enable
              ;; it for the requested format so the post-POST PUT on the
              ;; specific book can actually flip its monitor flag. See
              ;; CHAPTARR_INTEGRATION.md §3.12.
              :ebook-monitor-future     (= media-type :book)
              :audiobook-monitor-future (= media-type :audiobook)
              :monitored true
              :monitor-new-items "none"
              :add-options {:monitor "none"
                            :search-for-missing-books false}}
     ;; Explicit false — the fork fires BookSearch directly after the PUT.
     :add-options {:search-for-new-book false}}))

(defn books-for-author
  "Fetch all book entities under a given author id. Chaptarr returns one book
  row per edition/language (see CHAPTARR_INTEGRATION.md §3.5), so one author
  can have multiple book ids for what looks like the same title."
  [author-id]
  (utils/request-and-process-body
   GET
   #(map utils/from-camel %)
   "/book"
   {:query-params {:authorId author-id}}))

(defn book-matches-format?
  "True when a book entity's format discriminator matches the requested
  media-type. Prefers Chaptarr's mediaType field, falling back to
  edition.isEbook. Used after POST /book to find the one row matching the
  user's format choice among a multi-edition author catalog."
  [book media-type]
  (let [target-mt (if (= media-type :audiobook) "audiobook" "ebook")
        media-type-field (:media-type book)
        first-edition (first (:editions book))]
    (cond
      media-type-field (= target-mt media-type-field)
      (some? (:is-ebook first-edition))
      (case media-type
        :book      (boolean (:is-ebook first-edition))
        :audiobook (not (:is-ebook first-edition)))
      :else false)))

(defn- book-row-complete?
  "True when a Chaptarr book row looks like a resolved edition rather than a
  skeleton/placeholder. Chaptarr creates placeholder rows during author-add
  before the metadata source has populated them — those rows have empty
  images, no releaseDate, and zero ratings, and Chaptarr silently rejects
  monitor-flag PUTs against them. Real editions have both a release date
  and at least one image populated. See CHAPTARR_INTEGRATION.md §3.13."
  [book]
  (and (:release-date book)
       (seq (:images book))))

(defn- format-match-rank
  "Score how confidently a Chaptarr book row matches the requested format, and
  how complete/resolved that row is. Higher is better. Scoring:

  +10 row is a resolved edition (not a placeholder)
   +2 explicit mediaType match
   +1 edition.isEbook fallback match (used when mediaType field absent)
    0 anything else

  Resolved editions always outrank placeholders with the same format match."
  [book media-type]
  (let [target-mt (if (= media-type :audiobook) "audiobook" "ebook")
        media-type-field (:media-type book)
        first-edition (first (:editions book))
        base-rank (cond
                    (= target-mt media-type-field) 2
                    (some? (:is-ebook first-edition))
                    (case media-type
                      :book      (if (:is-ebook first-edition) 1 0)
                      :audiobook (if (false? (:is-ebook first-edition)) 1 0)
                      0)
                    :else 0)]
    (+ base-rank (if (book-row-complete? book) 10 0))))

(defn preferred-book-for-format
  "Choose the best Chaptarr book row for a requested format when an author has
  multiple matching editions. Preference order:

  1. Resolved edition (has releaseDate + images) over placeholder row
  2. Explicit mediaType format match over edition.isEbook fallback
  3. Higher ratings.popularity
  4. Higher ratings.votes (more established edition)
  5. Newer releaseDate

  The popularity and votes fields live under a nested `ratings` object on
  Chaptarr book rows, not at the top level. Placeholder rows have no ratings
  and so collapse to all-zero for these tiebreaks, letting the resolved
  editions win cleanly.

  Avoids blindly taking the first row returned by /book?authorId=..., the
  ordering of which is not stable across metadata sources, and prevents
  targeting a placeholder row whose monitor PUT would be silently dropped."
  [books media-type]
  (->> books
       (filter #(book-matches-format? % media-type))
       (sort-by (fn [book]
                  [(format-match-rank book media-type)
                   (or (get-in book [:ratings :popularity]) 0)
                   (or (get-in book [:ratings :votes]) 0)
                   (or (:release-date book) "")]))
       last))

(defn extract-author-id
  "Pull the newly-created author's id out of the POST /book response body.
  Chaptarr's response is the created Book object, which includes its parent
  authorId at the top level."
  [post-response]
  (let [body (:body post-response)
        kebab (utils/from-camel body)]
    (or (:author-id kebab)
        (get-in kebab [:author :id]))))

(defn download-cover
  "Fetch cover image bytes from Chaptarr's internal MediaCoverProxy endpoint.
  Used when a cover URL is relative (i.e. CHAPTARR__PUBLIC_URL is not set)
  so the bytes can be attached directly to the Discord embed instead of
  asking Discord to fetch them over the public internet.

  Chaptarr is always reachable at CHAPTARR__URL from Doplarr's container
  (both live on the same Docker network), so this path works for any
  deployment regardless of whether Chaptarr is publicly exposed.

  Returns a channel yielding the byte array on success, or an exception on
  failure. The caller should handle the exception path gracefully — a
  failed download should not block the user's request; the confirmation
  embed just renders without a cover."
  [cover-path]
  (let [chan (a/promise-chan)]
    (hc/request
     {:method :get
      :url (str (:chaptarr/url @state/config) cover-path)
      :as :byte-array
      :async? true
      :version :http-1.1
      :throw-exceptions? true
      :headers {"X-API-Key" @api-key}
      :timeout 10000}
     (fn [resp]
       (if (and (bytes? (:body resp)) (pos? (alength (:body resp))))
         (a/put! chan (:body resp))
         (do (warn "Chaptarr returned empty cover bytes for" cover-path)
             (a/put! chan (ex-info "empty cover body" {:cover-path cover-path})))))
     (fn [exc]
       (warn "Cover download failed for" cover-path (.getMessage exc))
       (a/put! chan exc)))
    chan))
