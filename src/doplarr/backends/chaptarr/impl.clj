(ns doplarr.backends.chaptarr.impl
  (:require
   [clojure.core.async :as a]
   [clojure.string :as str]
   [doplarr.state :as state]
   [doplarr.utils :as utils]
   [fmnoise.flow :refer [then]]
   [hato.client :as hc]
   [taoensso.timbre :refer [info warn]]))

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

(defn cover-url
  "Pull a cover URL out of a Chaptarr book-shape map (lookup result or
  resolved row). Prefers explicit coverType='cover' at the book level,
  then the book's first image, then any edition cover, then legacy
  remoteCover. Lookup results carry relative /MediaCoverProxy/... paths;
  resolved rows usually carry absolute upstream URLs. Callers decide
  how to handle either shape."
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
  "Discord rejects relative image URLs with a 50035. Returns the URL
  unchanged if already absolute, prepends CHAPTARR__PUBLIC_URL if
  configured, or nil if neither applies (caller should omit :image)."
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
  "Remove HTML tags and decode common entities. Chaptarr's upstream
  metadata occasionally emits malformed markup (unclosed <i>, etc.) —
  regex stripping is more forgiving than a strict HTML parser and
  produces plain text that Discord embeds render cleanly."
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
  "Normalize Chaptarr's /book/lookup output into the shape Doplarr
  expects. Flattens the embedded author, builds a \"Title — Author\"
  display title for the dropdown, and strips HTML from the overview.

  `:existing-book-id` is populated when the book is already indexed
  (Chaptarr uses id=0 for not-yet-added books). `:existing-author-id`
  is populated when lookup includes author.id directly — rare,
  because lookup usually queries the external metadata source, so
  `find-existing-author` is the usual route for existing-author
  detection."
  [result]
  (let [kebab (utils/from-camel result)
        existing-id (:id kebab)
        existing-author (:id (:author kebab))
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
                         existing-id)
     :existing-author-id (when (and (number? existing-author) (pos? existing-author))
                           existing-author)}))

(def ^:private junk-title-phrases
  "Case-insensitive substring markers that tag a lookup result as a
  study guide / summary / unauthorized companion. Chaptarr's upstream
  pulls these in alongside real editions, crowding out real results
  in the dropdown. Deliberately conservative — only multi-word
  phrases that essentially never appear in legitimate book titles,
  to avoid false positives (a bare 'guide' or 'summary' matches too
  many real works)."
  #{"study guide"
    "study guides"
    "sparknotes"
    "cliffsnotes"
    "cliff notes"
    "cliff's notes"
    "bookrags"
    "supersummary"
    "summary and analysis"
    "summary & analysis"
    "summary of"
    "reader's companion"
    "readers companion"
    "reading companion"
    "novel companion"
    "unauthorized companion"
    "unofficial companion"
    "an unauthorized"
    "an unofficial"
    "conversation starters"
    "quizzes and"
    "discussion questions"
    "reading guide"
    "reader's guide"
    "lesson plans"
    "key takeaways"
    "unofficial summary"
    "deep analysis"})

(defn- junk-lookup-result?
  "True when a processed lookup result's title contains any of the
  `junk-title-phrases` (case-insensitive). Used to drop study guides /
  summaries from the Discord search dropdown so they don't crowd out
  legitimate alternative editions."
  [result]
  (when-let [title-lower (some-> (:raw-title result) str/lower-case)]
    (some #(str/includes? title-lower %) junk-title-phrases)))

(defn lookup-book [term]
  (utils/request-and-process-body
   GET
   (fn [results]
     (let [processed (map process-book-search-result results)
           filtered (remove junk-lookup-result? processed)
           dropped (- (count processed) (count filtered))]
       (when (pos? dropped)
         (info (str "Chaptarr lookup: filtered " dropped
                    " study-guide/summary result(s) from "
                    (count processed) " total for '" term "'")))
       (vec filtered)))
   "/book/lookup"
   {:query-params {:term term}}))

(defn get-book-by-id [id]
  (utils/request-and-process-body
   GET
   utils/from-camel
   (str "/book/" id)))

(defn status
  "Return `:available` or `:processing` for a book already monitored
  in the requested format, nil otherwise. Reads format-specific
  statistics — the book-level hasFile field in Readarr/Chaptarr is
  format-agnostic (true when any format has a file) and would
  cross-contaminate this check."
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

(defn monitor-book
  "Flip a book's monitor flag on via the bulk `/book/monitor`
  endpoint. `PUT /book/{id}` looks like it should work but silently
  drops monitor changes. `/book/monitor` derives which per-format
  flag to flip from the row's mediaType, so the payload stays
  minimal: `{bookIds, monitored}`. Returns 202 with only a status
  snippet; callers must re-GET to verify the flip landed."
  [book-id]
  (a/go
    (let [payload {:bookIds [book-id] :monitored true}]
      (info (str "Chaptarr monitor: PUT /book/monitor " payload))
      (a/<! (PUT "/book/monitor"
                 {:form-params payload
                  :content-type :json})))))

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

(defn refresh-author
  "Fire Chaptarr's `RefreshAuthor` command to re-pull the author's
  full catalog from its upstream metadata source. Used to try to
  resolve skeleton placeholder rows into real editions. Mildly
  destructive — RefreshAuthor re-applies metadata profile language
  filters and can cull editions, so callers should only fire it when
  the target row is a placeholder (nothing useful to cull)."
  [author-id]
  (a/go
    (info (str "Chaptarr command: RefreshAuthor authorId=" author-id))
    (->> (a/<! (POST "/command" {:form-params {:name "RefreshAuthor"
                                               :authorId author-id}
                                 :content-type :json}))
         (then (constantly nil)))))

(defn get-author [author-id]
  (utils/request-and-process-body
   GET
   utils/from-camel
   (str "/author/" author-id)))

(defn authors
  "Fetch all authors currently indexed in Chaptarr. Used by
  `find-existing-author` because lookup results almost always return
  `author.id=0` (lookup queries the external metadata source, not
  the local library)."
  []
  (utils/request-and-process-body
   GET
   #(map utils/from-camel %)
   "/author"))

(defn- normalize-author-name
  "Fold an author name for equality comparison: lowercase, collapse
  whitespace, strip surrounding whitespace."
  [s]
  (when (string? s)
    (-> s
        str/lower-case
        (str/replace #"\s+" " ")
        str/trim)))

(defn find-existing-author
  "Match a lookup-result author against Chaptarr's indexed authors.
  Returns `{:id ..., :via <keyword>}` on match, nil on no-match or
  ambiguous multi-match.

  Strategy:
    1. foreignAuthorId equality (:foreign-author-id). Fails when
       lookup and stored library disagree on provider namespace —
       e.g. the lookup returns `gr:38550` but Chaptarr stored the
       author as `hc:204214` after Hardcover normalization.
    2. Normalized author-name, single match (:author-name). Bridges
       provider-namespace disagreement. Only fires when exactly one
       indexed author matches the name — multi-matches return nil
       rather than risk resolving to the wrong author on common names."
  [foreign-author-id author-name]
  (a/go
    (let [result (a/<! (authors))]
      (when (sequential? result)
        (let [by-fid (when (and foreign-author-id (string? foreign-author-id))
                       (first (filter #(= foreign-author-id (:foreign-author-id %))
                                      result)))]
          (cond
            by-fid
            {:id (:id by-fid) :via :foreign-author-id}

            :else
            (let [target (normalize-author-name author-name)
                  by-name (when (seq target)
                            (filter #(= target (normalize-author-name (:author-name %)))
                                    result))]
              (when (= 1 (count by-name))
                {:id (:id (first by-name)) :via :author-name}))))))))

(defn ensure-author-enabled-for-format
  "Flip the author's `*MonitorFuture` flag on for the requested format
  if it isn't already. Chaptarr silently drops per-book monitor PUTs
  when this flag is false, logging \"author is not monitored for ...\"
  server-side. Idempotent. Does real work on cross-format re-requests
  (e.g. an audiobook request against an author originally added for
  ebooks only)."
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
  "Build the `POST /api/v1/book` body. Creates the author and seeds
  its full catalog in an all-unmonitored state — the caller flips the
  requested format's monitor flag afterwards on the specific book row.

  Two non-obvious choices:

    - `monitored: false` everywhere on the body. If Chaptarr's
      AddBookService sees monitored=true on a POST it auto-flips
      format flags on every matching edition, cross-contaminating
      formats. Format-specific monitoring requires an unmonitored
      POST + a targeted PUT.
    - `addOptions.searchForNewBook: false`. Doesn't reliably trigger
      a release search; we fire BookSearch explicitly after the PUT.

  All four per-format profile ids must be populated — Chaptarr
  silently ignores the singular `qualityProfileId` and
  `metadataProfileId` fields."
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
              ;; Author-level gates for per-book monitoring; without
              ;; these, later `PUT /book/monitor` PUTs are silently
              ;; dropped.
              :ebook-monitor-future     (= media-type :book)
              :audiobook-monitor-future (= media-type :audiobook)
              :monitored true
              :monitor-new-items "none"
              :add-options {:monitor "none"
                            :search-for-missing-books false}}
     ;; Explicit false — the fork fires BookSearch directly after the PUT.
     :add-options {:search-for-new-book false}}))

(defn books-for-author
  "Fetch all book entities under a given author. Chaptarr returns one
  row per edition/language, so a single work typically has many rows
  with variant titles."
  [author-id]
  (utils/request-and-process-body
   GET
   #(map utils/from-camel %)
   "/book"
   {:query-params {:authorId author-id}}))

;; Forward declarations — these predicates are defined below with the ranking
;; helpers but `wait-for-resolved-book` needs to call them inside its go-loop.
(declare book-matches-format? book-row-complete? title-matches?)

(defn wait-for-resolved-book
  "Poll /book?authorId=... until the requested edition materializes
  or we hit the attempt cap. Chaptarr's POST /book returns before the
  metadata source has resolved the author's catalog; until then the
  only rows that exist are skeleton placeholders.

  Exit conditions, first wins:
    - `requested-title` provided AND a format-matching, complete row
      matches that title: return books. The title gate prevents early
      exits on big-catalog authors whose backlog entries resolve
      before the requested edition does.
    - No `requested-title`: any format-matching complete row is
      sufficient.
    - Attempt cap hit: return whatever we have with a WARN; the
      monitor-PUT verification in `chaptarr.clj/request` catches any
      silent failures downstream.

  Defaults (20 attempts × 1s) give a ~20s ceiling, well under
  Discord's 15-minute interaction-auth window."
  ([author-id media-type]
   (wait-for-resolved-book author-id media-type nil {}))
  ([author-id media-type requested-title]
   (wait-for-resolved-book author-id media-type requested-title {}))
  ([author-id media-type requested-title {:keys [max-attempts interval-ms]
                                          :or {max-attempts 20 interval-ms 1000}}]
   (a/go-loop [attempt 0]
     (let [books (a/<! (books-for-author author-id))
           format-resolved (filter #(and (book-matches-format? % media-type)
                                         (book-row-complete? %))
                                   books)
           title-resolved (when requested-title
                            (filter #(title-matches? % requested-title)
                                    format-resolved))
           ;; Exit as soon as the specific requested title is resolved.
           ;; If no title was supplied, any resolved row is fine.
           done? (if requested-title
                   (seq title-resolved)
                   (seq format-resolved))]
       (cond
         done?
         books

         (>= attempt max-attempts)
         (do (warn (str "wait-for-resolved-book: hit attempt cap ("
                        max-attempts ") for author " author-id " "
                        (name media-type)
                        (when requested-title
                          (str " (title='" requested-title "', "
                               (count format-resolved)
                               " format-resolved rows exist but none matched title)"))
                        " — Chaptarr may still be resolving metadata; "
                        "proceeding with whatever we have"))
             books)

         :else
         (do
           (a/<! (a/timeout interval-ms))
           (recur (inc attempt))))))))

(defn book-matches-format?
  "True when a book row's format discriminator matches the requested
  media-type. Prefers `mediaType`, falls back to `edition.isEbook`
  when absent."
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

(defn book-row-complete?
  "True when a book row looks like a resolved edition rather than a
  skeleton placeholder. A row counts as resolved when all of these
  hold:
    - releaseDate is set
    - images[] is non-empty
    - foreignEditionId is present and does not start with `default-`

  Chaptarr silently drops monitor PUTs against placeholders, so
  callers upstream of monitor-book must gate on this predicate."
  [book]
  (let [edition-id (:foreign-edition-id book)]
    (and (:release-date book)
         (seq (:images book))
         (string? edition-id)
         (not (str/starts-with? edition-id "default-")))))

(defn- normalize-title
  "Fold a title for comparison: lowercase, strip Unicode punctuation,
  collapse whitespace. Treats stylistic variants as equivalent (e.g.
  'Monk & Robot' vs 'Monk and Robot')."
  [s]
  (when (string? s)
    (-> s
        str/lower-case
        (str/replace #"[^\p{L}\p{N}\s]" " ")
        (str/replace #"\s+" " ")
        str/trim)))

(defn- title-matches?
  "True when a row's title plausibly matches the requested title:
  exact-after-normalization, or containment either direction.
  Containment handles subtitled editions (row 'The Women: A Novel'
  vs requested 'The Women') without over-matching wildly. Used as
  the widest filter — `exact-title-match?` breaks ties when multiple
  candidates pass this one."
  [row requested]
  (let [a (normalize-title (:title row))
        b (normalize-title requested)]
    (boolean
     (and (seq a) (seq b)
          (or (= a b)
              (str/includes? a b)
              (str/includes? b a))))))

(defn- exact-title-match?
  "Strict variant of `title-matches?` — only exact-after-normalization.
  Used to tier-prefer exact matches over substring matches. Matters
  when an author's catalog contains an anthology row whose title is
  `\"<requested title> By <author>, <other work>\"` — substring match
  would pass it, but an exact-match placeholder on the same work is
  a better pick because the indexer search uses the row's title
  verbatim and the anthology title won't match any release."
  [row requested]
  (let [a (normalize-title (:title row))
        b (normalize-title requested)]
    (boolean (and (seq a) (seq b) (= a b)))))

(defn- format-match-rank
  "Score a row on format confidence and resolved-vs-placeholder state.
  Higher is better.
    +10  resolved edition (per `book-row-complete?`)
     +2  explicit mediaType match
     +1  edition.isEbook fallback (when mediaType absent)
      0  otherwise
  Resolved rows always outrank placeholders with the same format match."
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

(defn- title-length-affinity
  "Tie-breaker for the substring-match tier: negative absolute
  distance between the row's normalized title length and the
  requested title's normalized length. Higher (closer to zero) is a
  better match.

  Marketing-heavy edition titles substring-match the request but add
  ~100 characters of publisher fluff (\"...by <author>: A Masterpiece
  of... (Publisher Edition)\"), which Chaptarr then feeds to the
  release indexer verbatim and poisons matching. A cleaner row with
  a shorter title wins the tie.

  Not a filter — a noisy row is still a legitimate candidate when
  nothing better exists. No-op within the exact-title tier (all
  normalized titles have the same length by construction). Nil or
  empty inputs collapse to zero."
  [row requested-title]
  (let [row-len (count (or (normalize-title (:title row)) ""))
        req-len (count (or (normalize-title requested-title) ""))]
    (if (and (pos? row-len) (pos? req-len))
      (- (Math/abs (- row-len req-len)))
      0)))

(defn preferred-book-for-format
  "Choose the best book row for a requested format when an author has
  multiple matching editions. Tier-filter by title match (exact >
  substring > no-match), then within the selected tier sort by:

    1. format-match-rank (resolved > placeholder, exact mediaType
       match > isEbook fallback)
    2. title-length-affinity (tighter titles beat marketing fluff)
    3. ratings.popularity
    4. ratings.votes
    5. releaseDate (newer wins)

  Falls back to all format-matching rows if no title match — better
  to target the author's most popular edition than to fail outright."
  ([books media-type]
   (preferred-book-for-format books media-type nil))
  ([books media-type requested-title]
   (let [format-filtered (filter #(book-matches-format? % media-type) books)
         ;; Tier-based candidate selection. When an author has both an
         ;; exact-title row AND a substring-match row (e.g. anthology
         ;; with combined title containing the requested title), we want
         ;; the exact match even if it's a placeholder. Without this,
         ;; Chaptarr searches MaM for the anthology's combined title
         ;; and finds nothing. See `exact-title-match?` for details.
         exact-matched (when requested-title
                         (seq (filter #(exact-title-match? % requested-title)
                                      format-filtered)))
         title-matched (when (and requested-title (nil? exact-matched))
                         (seq (filter #(title-matches? % requested-title)
                                      format-filtered)))
         candidates (or exact-matched title-matched format-filtered)
         tier (cond
                exact-matched  :exact
                title-matched  :substring
                :else          :format-only)]
     (when (and requested-title (nil? exact-matched) (nil? title-matched)
                (seq format-filtered))
       (warn (str "preferred-book-for-format: no " (name media-type)
                  " row matched requested title '" requested-title
                  "' — falling back to popularity-ranked row among "
                  (count format-filtered) " format-matching candidates.")))
     (let [winner (->> candidates
                       (sort-by (fn [book]
                                  [(format-match-rank book media-type)
                                   (title-length-affinity book requested-title)
                                   (or (get-in book [:ratings :popularity]) 0)
                                   (or (get-in book [:ratings :votes]) 0)
                                   (or (:release-date book) "")]))
                       last)]
       ;; One info line per selection. `winner-any-edition-ok` and
       ;; `winner-hardcover-book-id` are observability-only — logged
       ;; so operators can see whether those signals would have
       ;; changed a pick, but not currently consumed by the ranker.
       (when (and requested-title winner)
         (info (str "Chaptarr preferred-book-for-format: tier=" (name tier)
                    " exact-count=" (count (or exact-matched []))
                    " substring-count=" (count (or title-matched []))
                    " format-count=" (count format-filtered)
                    " winner-id=" (:id winner)
                    " winner-title=" (pr-str (:title winner))
                    " winner-length-affinity=" (title-length-affinity
                                                winner requested-title)
                    " winner-format-rank=" (format-match-rank winner media-type)
                    " winner-any-edition-ok=" (pr-str (:any-edition-ok winner))
                    " winner-foreign-edition-id=" (pr-str (:foreign-edition-id winner))
                    " winner-hardcover-book-id=" (pr-str (:hardcover-book-id winner)))))
       winner))))

(defn extract-author-id
  "Pull the newly-created author's id out of a POST /book response.
  Chaptarr echoes the created Book object, which carries its parent
  authorId at the top level."
  [post-response]
  (let [body (:body post-response)
        kebab (utils/from-camel body)]
    (or (:author-id kebab)
        (get-in kebab [:author :id]))))

(defn download-cover
  "Fetch cover-image bytes from Chaptarr's MediaCoverProxy endpoint.
  Used when CHAPTARR__PUBLIC_URL isn't configured: the bytes are
  attached directly to the Discord embed rather than asking Discord
  to fetch them over the public internet. Returns a channel yielding
  the byte array or an exception; failures should never block the
  request — the embed just renders coverless."
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
