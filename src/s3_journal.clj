(ns s3-journal
  (:require
    [clojure.tools.logging :as log]
    [clojure.repl :as repl]
    [byte-streams :as bs]
    [byte-transforms :as bt]
    [durable-queue :as q]
    [clojure.string :as str]
    [s3-journal.s3 :as s3]
    [clojure.java.io :as io])
  (:import
    [java.nio
     ByteBuffer]
    [java.util.concurrent
     LinkedBlockingQueue
     Semaphore]
    [java.io
     Closeable]
    [java.util.concurrent.atomic
     AtomicLong]
    [java.util
     ArrayList
     Date
     TimeZone]
    [java.text
     SimpleDateFormat]
    [java.lang.ref
     WeakReference]
    [java.lang.management
     ManagementFactory]
    [com.amazonaws.services.s3.model
     PartETag
     S3ObjectSummary
     PartSummary
     ListObjectsRequest
     ListPartsRequest
     ListMultipartUploadsRequest
     MultipartUpload
     UploadPartRequest
     InitiateMultipartUploadRequest
     CompleteMultipartUploadRequest
     AbortMultipartUploadRequest]
    [com.amazonaws.auth
     BasicAWSCredentials]
    [com.amazonaws.services.s3
     AmazonS3Client]
    [com.amazonaws
     AmazonServiceException]))

;;;

(defprotocol IExecutor
  (put! [_ x] "Enqueues an object for processing, returns true if successful, false if the journal is full.")
  (stats [_] "Returns a description of the state of the journal."))

(defn- batching-queue [max-size max-time callback]
  (assert (or max-size max-time))
  (let [q (LinkedBlockingQueue. (int (or max-size Integer/MAX_VALUE)))
        now #(System/currentTimeMillis)
        marker (atom (now))
        flush (fn [^LinkedBlockingQueue q]
                (let [c (ArrayList.)]
                  (.drainTo q c)
                  (locking callback
                    (if (.isEmpty c)
                      (callback nil)
                      (callback c)))))]

    ;; background loop which cleans itself up if the queue
    ;; is no longer in use
    (when max-time
      (let [q (WeakReference. q)]
        (future
          (loop []
            (when-let [q (.get q)]
              (let [m @marker]
                (try
                  (Thread/sleep (max 0 (- (+ m max-time) (now))))
                  (when (compare-and-set! marker m (now))
                    (flush q))
                  (catch Throwable e
                    (log/warn e "error in batching queue"))))
              (recur))))))

    (reify
      IExecutor
      (put! [_ x]
        (when-not (.offer q x)
          (reset! marker (now))
          (flush q)
          (recur x)))
      java.io.Closeable
      (close [_]
        (flush q)))))

;;;

(defn- hostname []
  (let [process+host (.getName (ManagementFactory/getRuntimeMXBean))]
    (-> process+host
      (str/split #"@")
      second
      (str/replace #"/" "_"))))

(defn- position->file [id suffix [_ part dir]]
  (str dir "/" id "-" (format "%06d" (int (/ part s3/max-parts)))
    ".journal"
    (when suffix (str "." suffix))))

(defn- file->position [path]
  (when-let [[dir n] (rest (re-find #"(.*)/.*-(\d+)\.journal" path))]
    [0 (* s3/max-parts (Long/parseLong n)) dir]))

(defn- open-uploads
  ([client bucket prefix]
     (open-uploads ".*" client bucket prefix))
  ([id client bucket prefix]
     (let [re (re-pattern (str id "-(\\d+)\\.journal"))]
       (filter
         #(re-find re (second %))
         (s3/multipart-uploads client bucket prefix)))))

(defn- current-file-count
  "Returns the number of pre-existing complete and pending uploads in the directory for
   the given hostname."
  [q id ^AtomicLong enqueued-counter ^Semaphore semaphore client bucket dir]
  (let [prefix (str dir "/" id)]
    (max

      ;; writes already in AWS
      (let [uploads (distinct
                      (concat
                        (s3/complete-uploads client bucket prefix)
                        (map second (s3/multipart-uploads client bucket prefix))))]
        (count uploads))

      ;; pending writes
      (let [tasks (q/immediate-task-seq q :s3)
            highest-part (->> tasks
                           (map (fn [task]
                                  (try
                                    (let [[action _ count :as descriptor] @task]
                                      (when (= :conj action)
                                        (when-not (.tryAcquire semaphore count)
                                          (throw
                                            (IllegalStateException.
                                              "Insufficient queue size to handle uploads that are already pending")))
                                        (.addAndGet enqueued-counter count))
                                      descriptor)
                                    (catch Throwable e
                                      nil))))
                           (map second)
                           (filter #(= dir (last %)))
                           (map second)
                           (apply max 0))]
        (doseq [t tasks]
          (q/retry! t))
        (long (Math/ceil (/ highest-part s3/max-parts)))))))

(let [utc (TimeZone/getTimeZone "UTC")]
  (defn- format->directory
    "Returns the directory location for the current time."
    [directory-format]
    (.format
      (doto (SimpleDateFormat. directory-format)
        (.setTimeZone utc))
      (Date.))))

;;; utility functions for inner loop

(defn- cleanup-expired-uploads
  "Identifies any open uploads which are more than `expiration` milliseconds old, and closes them out."
  [client bucket date-format expiration]
  (let [date-format (doto (SimpleDateFormat. date-format)
                      (.setTimeZone (TimeZone/getTimeZone "UTC")))
        now (System/currentTimeMillis)
        descriptors (->> (open-uploads client bucket nil)
                      (filter
                        (fn [[_ ^String path _]]
                          (when-let [^String file (second (re-matches #".*(/.*?journal.*)" path))]
                            (let [path' (.substring path 0 (- (count path) (count file)))]
                              (try
                                (when (< expiration (- now (.getTime (.parse date-format path'))))
                                  true)
                                (catch Throwable e
                                  nil)))))))
        files (map second descriptors)
        parts (map #(s3/parts client %) descriptors)]

    (doseq [[d ps] (map list descriptors parts)]
      (try
        (s3/end-multipart client ps d)
        (catch AmazonServiceException e
          (case (.getStatusCode e)

            404
            nil

            403
            (try
              (s3/abort-multipart client d)
              (catch Throwable e
                (log/warn e "error cleaning up old uploads")))

            (log/warn e "error cleaning up old uploads")))
        (catch Throwable e
          (log/warn e "error cleaning up old uploads"))))))

(defn- advance
  "Given a new chunk, returns the location for where it should be appended, and any additional
   actions that should be performed."
  [[bytes part directory :as pos] directory-format chunk-size]
  (let [actions (atom [])
        add-actions! #(apply swap! actions conj %&)
        directory' (format->directory directory-format)
        pos' (if (not= directory directory')

               ;; we've moved to a new directory, so close up previous upload and roll over
               (let [pos' [chunk-size 0 directory']]
                 (add-actions! [:end pos] [:start pos'])
                 pos')

               (let [part' (if (> bytes s3/min-part-size)
                             (inc part)
                             part)
                     bytes' (if (= part part')
                              (+ bytes chunk-size)
                              chunk-size)
                     pos' [bytes' part' directory]]

                 ;; we've hit the maximum part size, so create a new file
                 (when (and
                         (not= part part')
                         (zero? (rem part' s3/max-parts)))
                   (add-actions! [:end pos] [:start pos']))

                 ;; we've hit the minimum part size threshold, upload the part
                 (when (> bytes' s3/min-part-size)
                   (add-actions! [:upload pos']))

                 pos'))]
    [pos' @actions]))

(defn- get-in-state
  "A `get-in` function for the upload-state."
  [upload-state part dir ks]
  (let [file-number (long (/ part s3/max-parts))
        part' (* file-number s3/max-parts)]
    (get-in upload-state (cons [part' dir] ks))))

(defn- assoc-in-state
  "An `assoc-in` function for the upload-state."
  [upload-state part dir ks v]
  (let [file-number (long (/ part s3/max-parts))
        part' (* file-number s3/max-parts)]
    (assoc-in upload-state (cons [part' dir] ks) v)))

(defn- update-in-state
  "An `update-in` function for the upload-state."
  [upload-state part dir ks f & args]
  (let [file-number (long (/ part s3/max-parts))
        part' (* file-number s3/max-parts)]
    (apply update-in upload-state (cons [part' dir] ks) f args)))

(defn- dissoc-state
  "Removes the pending upload described by `[part, dir]`"
  [upload-state part dir]
  (let [file-number (long (/ part s3/max-parts))
        part' (* file-number s3/max-parts)]
    (dissoc upload-state [part' dir])))

(defn- upload-descriptor
  [upload-state part dir]
  (get-in-state upload-state part dir [:descriptor]))

(defn- initial-upload-state [id client bucket prefix]
  (loop [retries 0]
    (if-let [vals (try
                    (let [descriptors (open-uploads id client bucket prefix)
                          files (map second descriptors)]
                      (zipmap
                        (->> files
                          (map file->position)
                          (map rest))
                        (map
                          (fn [descriptor]
                            {:descriptor descriptor
                             :parts (s3/parts client descriptor)})
                          descriptors)))
                    (catch Throwable e
                      nil))]
      vals
      (recur (inc retries)))))

(defn- upload-part
  [client ^AtomicLong upload-counter ^Semaphore semaphore upload-state part dir last?]
  (if (get-in-state upload-state part dir [:parts part :uploaded?])
    upload-state
    (let [tasks (get-in-state upload-state part dir [:parts part :tasks])
          task-descriptors (map deref tasks)
          counts (map #(nth % 2) task-descriptors)
          bytes (map #(nth % 3) task-descriptors)
          descriptor (upload-descriptor upload-state part dir)]
      (try

        (let [rsp (s3/upload-part client
                    descriptor
                    (inc (rem part s3/max-parts))
                    bytes
                    last?)]

          (doseq [t tasks]
            (q/complete! t))

          (let [num-entries (reduce + counts)]
            (.addAndGet upload-counter num-entries)
            (.release semaphore num-entries))

          (assoc-in-state upload-state part dir [:parts part] rsp))

        (catch Throwable e

          (log/info e "error uploading part")

          upload-state)))))

(defn- start-consume-loop
  [id              ; journal identifier
   q               ; durable queue
   client          ; s3 client
   bucket          ; s3 bucket
   prefix          ; the unique prefix for this journal (typically only used when sharding)
   suffix          ; the file suffix (nil by default)
   upload-counter  ; atomic long for tracking entry uploading
   semaphore       ; semaphore for controlling maximum pending entries
   cleanup         ; nil, or a function which is periodically called to clean up dangling uploads
   close-latch     ; an atom which marks whether the loop should be closed
   ]
  (let [upload-state (initial-upload-state id client bucket prefix)
        last-cleanup (atom 0)]

    (doseq [upload (keys upload-state)]
      (q/put! q :s3 [:end (cons 0 upload)]))

    (loop [upload-state upload-state]

      ;; if there's a cleanup function, check if an hour has elapsed since
      ;; the last time we called it
      (when cleanup
        (let [now (System/currentTimeMillis)]
          (when (> (- now @last-cleanup) (* 1000 60 60))
            (reset! last-cleanup now)
            (cleanup client))))

      (let [task (try
                   (if @close-latch
                     (q/take! q :s3 5000 ::exhausted)
                     (q/take! q :s3)))]
        (when-not (= ::exhausted task)
          (let [[action [bytes part dir] & params] (try
                                                     @task
                                                     (catch Throwable e
                                                       ;; something got corrupted, all we
                                                       ;; can do is move past it
                                                       (log/warn e "error deserializing task")
                                                       [:skip]))
                descriptor (when part (upload-descriptor upload-state part dir))]

            (recur
              (try
                (if-not (or (#{:start :flush} action) descriptor)

                  ;; the upload this is for no longer is valid, just drop it
                  (do
                    (q/complete! task)
                    upload-state)

                  (case action

                    :flush
                    (do
                      (doseq [[part dir] (keys upload-state)]
                        (q/put! q :s3 [:end [0 part dir]]))
                      (q/complete! task)
                      upload-state)

                    ;; new batch of bytes for the part
                    :conj
                    (let [[cnt bytes] params]
                      (if (zero? cnt)
                        upload-state
                        (update-in-state upload-state part dir [:parts part :tasks]
                          #(conj (or % []) task))))

                    ;; actually upload the part
                    :upload
                    (let [upload-state' (upload-part client upload-counter semaphore upload-state part dir false)]
                      (if (get-in-state upload-state' part dir [:parts part :uploaded?])
                        (q/complete! task)
                        (q/retry! task))
                      upload-state')

                    ;; start a new multipart upload
                    :start
                    (let [descriptor (or
                                       (get-in-state upload-state part dir [:descriptor])
                                       (loop []
                                         (or
                                           (try
                                             (s3/init-multipart client bucket
                                               (position->file id suffix [0 part dir]))
                                             (catch Throwable e
                                               ;; we can't proceed until this succeeds, so
                                               ;; retrying isn't a valid option
                                               (Thread/sleep 1000)
                                               nil))
                                           (recur))))]
                      (q/complete! task)
                      (assoc-in-state upload-state part dir [:descriptor] descriptor))

                    ;; close out the multipart upload, but only if all the parts have been
                    ;; successfully uploaded
                    :end
                    (let [parts (get-in-state upload-state part dir [:parts])
                          non-uploaded (remove #(:uploaded? (val %)) parts)
                          upload-state' (or
                                          (and
                                            (empty? non-uploaded)
                                            upload-state)
                                          (and
                                            (= 1 (count non-uploaded))
                                            (let [part' (-> non-uploaded first key)]
                                              (and
                                                (= (rem part' s3/max-parts) (dec (count parts)))
                                                (upload-part client upload-counter semaphore upload-state part' dir true))))
                                          upload-state)
                          parts' (get-in-state upload-state' part dir [:parts])]

                      (if upload-state'

                        ;; we only had one remaining part, check if it was uploaded
                        (if (->> parts' vals (every? :uploaded?))

                          ;; all the parts have been uploaded, close it out
                          (do
                            (s3/end-multipart client
                              (zipmap
                                (map #(rem % s3/max-parts) (keys parts'))
                                (vals parts'))
                              descriptor)
                            (q/complete! task)
                            (dissoc-state upload-state' part dir))

                          (do
                            (q/retry! task)
                            (Thread/sleep 1000)
                            upload-state'))

                        ;; wait until we're in a position to close it out
                        (do
                          (q/retry! task)
                          (Thread/sleep 1000)
                          upload-state)))

                    ))
                (catch Throwable e
                  (log/info e "error in task consumption")
                  (Thread/sleep 1000)
                  (q/retry! task)
                  upload-state)))))))))

;;;

(defmacro int->bytes
  [i]
  `(-> (ByteBuffer/allocate Integer/BYTES)
       (.putInt ~i)
       .array))

(defn- journal-
  [{:keys
    [s3-access-key
     s3-secret-key
     s3-bucket
     s3-directory-format
     local-directory
     encoder
     compressor
     delimiter
     sized?
     fsync?
     suffix
     max-queue-size
     max-batch-latency
     max-batch-size
     expiration
     id]
    :or {delimiter "\n"
         sized? false
         encoder bs/to-byte-array
         id (hostname)
         compressor identity
         fsync? true
         max-queue-size Integer/MAX_VALUE
         max-batch-latency (* 1000 60)
         s3-directory-format "yyyy/MM/dd"}}]

  (assert local-directory "must define :local-directory for buffering the journal")
  (assert (or (nil? delimiter) (string? delimiter)) "delimiter must be nil or a string")

  (when-not (or delimiter sized?)
    (log/info "no delimiter or sized? specified; records may be difficult to consume!"))

  (.mkdirs (io/file local-directory))

  (let [prefix (second (re-find #"^'(.*)'" s3-directory-format))
        suffix (or suffix
                 (case compressor
                   :gzip "gz"
                   :snappy "snappy"
                   :bzip2 "bz2"
                   :lzo "lzo"
                   nil))
        delimiter (when delimiter (bs/to-byte-array delimiter))
        compressor (if (keyword? compressor)
                     #(bt/compress % compressor)
                     compressor)
        packer (cond
                 (and delimiter sized?) #(vector (int->bytes (alength %)) (bs/to-byte-array %) delimiter)
                 delimiter #(vector (bs/to-byte-array %) delimiter)
                 sized? #(vector (int->bytes (alength %)) (bs/to-byte-array %))
                 :else #(vector (bs/to-byte-array %)))
        ->bytes (fn [s]
                  (if (nil? s)
                    (byte-array 0)
                    (->> s
                         (map encoder)
                         (mapcat packer)
                         vec
                         bs/to-byte-array
                         compressor
                         bs/to-byte-array)))
        c (s3/client s3-access-key s3-secret-key)
        q (q/queues local-directory
            {:fsync-put? fsync?})
        initial-directory (format->directory s3-directory-format)
        enqueued-counter (AtomicLong. 0)
        uploaded-counter (AtomicLong. 0)
        pending-semaphore (Semaphore. max-queue-size)
        pos (atom
              [0
               (* s3/max-parts (current-file-count q id enqueued-counter pending-semaphore c s3-bucket initial-directory))
               initial-directory])
        pre-action? #(#{:start} (first %))
        pre-q (batching-queue
                max-batch-size
                max-batch-latency
                (fn [s]
                  (let [bytes (or (->bytes s) (byte-array 0))
                        cnt (count s)
                        [pos' actions] (advance @pos s3-directory-format (count bytes))]
                    (.addAndGet enqueued-counter cnt)
                    (reset! pos pos')
                    (doseq [a (filter pre-action? actions)]
                      (q/put! q :s3 a))
                    (q/put! q :s3 [:conj pos' cnt bytes])
                    (doseq [a (remove pre-action? actions)]
                      (q/put! q :s3 a)))))
        close-latch (atom false)]

    (q/put! q :s3 [:start @pos])

    (let [consume-loop (future
                         (try
                           (start-consume-loop
                             id
                             q
                             c
                             s3-bucket
                             prefix
                             suffix
                             uploaded-counter
                             pending-semaphore
                             (when expiration #(cleanup-expired-uploads % s3-bucket s3-directory-format expiration))
                             close-latch)
                           (catch Throwable e
                             (log/warn e "error in journal loop"))))]

      ;; consumer loop
      (reify IExecutor
        (stats [_]
          (let [uploaded (.get uploaded-counter)
                enqueued (.get enqueued-counter)]
            {:enqueued enqueued
             :uploaded uploaded
             :queue (get (q/stats q) "s3")}))
        (put! [_ x]
          (if @close-latch
            (throw (IllegalStateException. "attempting to write to a closed journal"))
            (boolean
              (and
                (.tryAcquire pending-semaphore)
                (put! pre-q x)))))
        Closeable
        (close [_]
          (.close ^java.io.Closeable pre-q)
          (q/put! q :s3 [:flush])
          (reset! close-latch true)
          @consume-loop
          nil)))))

(def ^:private shard-ids
  (concat
    (range 10)
    (map char (range (int \a) (inc (int \z))))))

(defn journal
  [{:keys
    [s3-access-key
     s3-secret-key
     s3-bucket
     s3-directory-format
     local-directory
     encoder
     compressor
     delimiter
     sized?
     fsync?
     max-batch-latency
     max-batch-size
     expiration
     id
     max-queue-size
     suffix
     shards]
    :or {delimiter "\n"
         sized? false
         encoder bs/to-byte-array
         id (hostname)
         compressor identity
         fsync? true
         max-batch-latency (* 1000 60)
         max-queue-size Integer/MAX_VALUE
         s3-directory-format "yyyy/MM/dd"}
    :as options}]
  "Creates a journal that will write to S3."
  (if shards

    ;; we want to shard the streams
    (do
      (assert (<= shards 36))
      (let [journals (zipmap
                       (range shards)
                       (map
                         (fn [shard]
                           (journal-
                             (-> options
                               (update-in [:max-queue-size]
                                 #(if % (/ % shards) Integer/MAX_VALUE))
                               (update-in [:s3-directory-format]
                                 #(str \' (nth shard-ids shard) "'/" %))
                               (update-in [:local-directory]
                                 #(when % (str local-directory "/" (nth shard-ids shard)))))))
                         (range shards)))
            counter (AtomicLong. 0)]
        (reify IExecutor
          (stats [_]
            (let [stats (->> journals vals (map stats))]
              (merge
                (->> stats (map #(dissoc % :queue)) (apply merge-with +))
                {:queue (->> stats (map :queue) (apply merge-with +))})))
          (put! [_ x]
            (put!
              (journals (rem (.getAndIncrement counter) shards))
              x))
          java.io.Closeable
          (close [_]
            (doseq [^Closeable j (vals journals)]
              (.close j))))))

    (journal- options)))
