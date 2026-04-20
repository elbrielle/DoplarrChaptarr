(ns doplarr.backends.chaptarr.impl
  (:require
   [doplarr.state :as state]
   [doplarr.utils :as utils]))

(def base-url (delay (str (:chaptarr/url @state/config) "/api/v1")))
(def api-key  (delay (:chaptarr/api @state/config)))

(defn GET [endpoint & [params]]
  (utils/http-request :get (str @base-url endpoint) @api-key params))

(defn POST [endpoint & [params]]
  (utils/http-request :post (str @base-url endpoint) @api-key params))

(defn PUT [endpoint & [params]]
  (utils/http-request :put (str @base-url endpoint) @api-key params))

(defn quality-profiles []
  (utils/request-and-process-body
   GET
   #(map utils/process-profile %)
   "/qualityprofile"))

(defn metadata-profiles []
  (utils/request-and-process-body
   GET
   #(map utils/process-profile %)
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

(defn process-book-search-result
  "Normalize Chaptarr's /book/lookup output into the shape Doplarr expects.

  Chaptarr (Readarr fork) returns a book object with an embedded author. We flatten
  the pieces needed downstream. `existing-book-id` is set only when the book is
  already in the library (Chaptarr uses id=0 for not-yet-added books)."
  [result]
  (let [kebab (utils/from-camel result)
        existing-id (:id kebab)]
    {:title (:title kebab)
     :year (year-from-release-date (:release-date kebab))
     :foreign-book-id (:foreign-book-id kebab)
     :foreign-author-id (get-in kebab [:author :foreign-author-id])
     :author-name (or (:author-title kebab)
                      (get-in kebab [:author :author-name]))
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
  monitored for this format — the caller should then POST to add it."
  [details media-type]
  (let [audiobook? (= media-type :audiobook)
        monitored? (if audiobook?
                     (:audiobook-monitored details)
                     (:ebook-monitored details))
        stats      (if audiobook?
                     (:audiobook-statistics details)
                     (:ebook-statistics details))
        has-file?  (boolean
                    (or (pos? (or (:book-file-count (or stats {})) 0))
                        (:has-file details)))]
    (when monitored?
      (if has-file? :available :processing))))

(defn request-payload
  "Build the POST /api/v1/book body for Chaptarr.

  Sets the format-specific monitor flag (ebookMonitored or audiobookMonitored)
  and includes both ebook and audiobook root folder paths on the author so
  future requests for the other format land correctly. monitorNewItems=\"none\"
  and addOptions.monitor=\"specificBook\" prevent flooding the library with
  every book from the added author — per CHAPTARR_KNOWLEDGE.md §7."
  [payload]
  (let [{:keys [title foreign-book-id foreign-author-id author-name
                quality-profile-id metadata-profile-id
                ebook-rootfolder-path audiobook-rootfolder-path
                media-type]} payload
        audiobook? (= media-type :audiobook)
        ebook?     (= media-type :book)
        chosen-rootfolder (if audiobook?
                            audiobook-rootfolder-path
                            ebook-rootfolder-path)]
    {:title title
     :foreign-book-id foreign-book-id
     :quality-profile-id quality-profile-id
     :metadata-profile-id metadata-profile-id
     :monitored true
     :ebook-monitored ebook?
     :audiobook-monitored audiobook?
     :root-folder-path chosen-rootfolder
     :author {:author-name author-name
              :foreign-author-id foreign-author-id
              :quality-profile-id quality-profile-id
              :metadata-profile-id metadata-profile-id
              :root-folder-path chosen-rootfolder
              :ebook-root-folder-path ebook-rootfolder-path
              :audiobook-root-folder-path audiobook-rootfolder-path
              :ebook-monitor-future false
              :audiobook-monitor-future false
              :monitored true
              :monitor-new-items "none"
              :add-options {:monitor "specificBook"
                            :search-for-missing-books false}}
     :add-options {:search-for-new-book true}}))
