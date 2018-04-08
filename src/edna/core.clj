(ns edna.core
  (:require [edna.parse :as parse]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.codec.base64 :as base64]
            ; require every alda.lisp.* namespace
            ; so all the defmethods are run
            [alda.lisp.attributes :as ala]
            alda.lisp.code
            [alda.lisp.events :as ale]
            alda.lisp.events.barline
            alda.lisp.events.chord
            alda.lisp.events.cram
            alda.lisp.events.fn
            alda.lisp.events.note
            alda.lisp.events.rest
            alda.lisp.events.sequence
            alda.lisp.events.variable
            alda.lisp.events.voice
            alda.lisp.instruments.midi
            alda.lisp.model.attribute
            [alda.lisp.model.duration :as almd]
            alda.lisp.model.event
            alda.lisp.model.global-attribute
            alda.lisp.model.marker
            alda.lisp.model.offset
            [alda.lisp.model.pitch :as almp]
            [alda.lisp.score :as als]
            alda.lisp.score.part
            [alda.sound :as sound]
            [alda.sound.midi :as midi])
  (:import [javax.sound.midi MidiSystem]
           [javax.sound.sampled AudioSystem AudioFormat AudioFileFormat$Type]
           [meico.midi Midi2AudioRenderer]
           [meico.audio Audio]))

(def ^:private default-soundbank (try (MidiSystem/getSoundbank (io/resource "Aspirin_160_GMGS_2015.sf2"))
                                    (catch Exception _)))
(def ^:private default-format (AudioFormat. 44100 16 2 true false))
(def ^:private default-attrs {:octave 4 :length 1/4 :tempo 120
                              :pan 50 :quantize 90 :transpose 0
                              :volume 100 :parent-ids [] :play? true})

(defmulti edna->alda*
  "The underlying multimethod for converting edna to alda. You probably don't need to use this."
  (fn [val parent-attrs] (first val)))

(defmethod edna->alda* :score [[_ {:keys [subscores] :as score}]
                               {:keys [sibling-id parent-ids] :as parent-attrs}]
  (let [id (inc (or sibling-id 0))
        {:keys [instrument] :as attrs} (merge parent-attrs (select-keys score [:instrument]))]
    [(ale/part (if instrument (name instrument) {})
       (when sibling-id
         (ale/at-marker (str/join "." (conj parent-ids sibling-id))))
       (first
         (reduce
           (fn [[subscores attrs] subscore]
             (let [attrs (assoc attrs :parent-ids
                           (conj parent-ids id))
                   [subscore attrs] (edna->alda* subscore attrs)]
               [(conj subscores subscore) attrs]))
           [[] (dissoc attrs :sibling-id)]
           (vec subscores)))
       (ale/marker (str/join "." (conj parent-ids id))))
     (assoc parent-attrs :sibling-id id)]))

(defmethod edna->alda* :concurrent-score [[_ scores]
                                          {:keys [sibling-id parent-ids] :as parent-attrs}]
  (let [id (inc (or sibling-id 0))
        instruments (map :instrument scores)]
    (when (not= (count instruments) (count (set instruments)))
      (throw (Exception. (str
                           "Can't use the same instrument "
                           "multiple times in the same set "
                           "(this limitation my change eventually)"))))
    [(ale/part {}
       (reduce
         (fn [scores score]
           (let [[score _] (edna->alda* [:score score] parent-attrs)]
             (conj scores score)))
         []
         (vec scores))
       (ale/marker (str/join "." (conj parent-ids id))))
     (assoc parent-attrs :sibling-id id)]))

(defmethod edna->alda* :attrs [[_ {:keys [note] :as attrs}] parent-attrs]
  (if note
    (let [[note {:keys [sibling-id]}] (edna->alda* [:note note] (merge parent-attrs attrs))]
      [note (assoc parent-attrs :sibling-id sibling-id)])
    [nil (merge parent-attrs attrs)]))

(defmethod edna->alda* :note [[_ note]
                              {:keys [instrument octave length tempo
                                      pan quantize transpose volume
                                      sibling-id parent-ids play?]
                               :as parent-attrs}]
  (when-not instrument
    (throw (Exception. (str "Can't play " note " without specifying an instrument"))))
  (if-not play?
    [nil parent-attrs]
    (let [id (inc (or sibling-id 0))
          {:keys [note accidental octave-op octaves]} (parse/parse-note note)
          note (keyword (str note))
          accidental (case accidental
                       \# :sharp
                       \= :flat
                       \_ :natural
                       nil)
          octaves (or octaves
                      (if octave-op [\1] [\0]))
          octave-change (cond-> (Integer/valueOf (str/join octaves))
                                (= \- octave-op) (* -1))]
      [(ale/part (name instrument)
         (when sibling-id
           (ale/at-marker (str/join "." (conj parent-ids sibling-id))))
         (ala/octave (+ octave octave-change))
         (ala/tempo tempo)
         (ala/pan pan)
         (ala/quantize quantize)
         (ala/transpose transpose)
         (ala/volume volume)
         (ale/note
           (or (some->> accidental (almp/pitch note))
               (almp/pitch note))
           (almd/duration (almd/note-length (/ 1 length))))
         (ale/marker (str/join "." (conj parent-ids id))))
       (assoc parent-attrs :sibling-id id)])))

(defmethod edna->alda* :chord [[_ chord]
                               {:keys [instrument sibling-id parent-ids play?]
                                :as parent-attrs}]
  (when-not instrument
    (throw (Exception. (str "Can't play "
                         (set (map second chord))
                         " without specifying an instrument"))))
  (if-not play?
    [nil parent-attrs]
    (let [id (inc (or sibling-id 0))
          attrs (-> parent-attrs
                    (assoc :parent-ids (conj parent-ids id))
                    (dissoc :sibling-id))]
      [(ale/part (name instrument)
         (when sibling-id
           (ale/at-marker (str/join "." (conj parent-ids sibling-id))))
         (apply ale/chord
           (map (fn [note]
                  (first (edna->alda* note attrs)))
             chord))
         (ale/marker (str/join "." (conj parent-ids id))))
       (assoc parent-attrs :sibling-id id)])))

(defmethod edna->alda* :rest [[_ _] {:keys [length sibling-id parent-ids]
                                     :as parent-attrs}]
  (let [id (inc (or sibling-id 0))]
    [[(when sibling-id
        (ale/at-marker (str/join "." (conj parent-ids sibling-id))))
      (ale/pause (almd/duration (almd/note-length (/ 1 length))))
      (ale/marker (str/join "." (conj parent-ids id)))]
     (assoc parent-attrs :sibling-id id)]))

(defmethod edna->alda* :length [[_ length] parent-attrs]
  [nil (assoc parent-attrs :length length)])

(defmethod edna->alda* :default [[subscore-name] parent-attrs]
  (throw (Exception. (str subscore-name " not recognized"))))

(defn edna->alda
  "Converts from edna to alda format."
  [content]
  (->> default-attrs
       (edna->alda* (parse/parse content))
       first))

(defn stop!
  "Stops the given score from playing. The `score` should be what was returned by `play!`."
  [score]
  (some-> score sound/tear-down!)
  nil)

(defn play!
  "Takes edna content and plays it. Returns a score map, which can be used to stop it later."
  [content]
  (binding [midi/*midi-synth* (midi/new-midi-synth)
            sound/*play-opts* {:async? true
                               :one-off? true}]
    (-> content edna->alda als/score sound/play! :score)))

(defmulti export!
  "Takes edna content and exports it, returning the value of :out. The `opts` map can contain:
 
  :type      - :midi, :wav, :mp3, or :mp3-data-uri (required)
  :out       - A java.io.OutputStream or java.io.File object (optional, defaults to a ByteArrayOutputStream)
  :soundbank - A javax.sound.midi.Soundbank object (optional, defaults to a built-in soundbank)
  :format    - A javax.sound.sampled.AudioFormat object (optional, defaults to one with 44100 Hz)"
  (fn [content opts]
    (:type opts)))

(defmethod export! :midi [content {:keys [out]
                                   :or {out (java.io.ByteArrayOutputStream.)}}]
  (binding [midi/*midi-synth* (midi/new-midi-synth)
            sound/*play-opts* {:async? false
                               :one-off? true}]
    (-> content edna->alda als/score sound/create-sequence!
        (MidiSystem/write 0 out)))
  out)

(defmethod export! :wav [content {:keys [out soundbank format]
                                  :or {out (java.io.ByteArrayOutputStream.)
                                       soundbank default-soundbank
                                       format default-format}}]
  (binding [midi/*midi-synth* (midi/new-midi-synth)
            sound/*play-opts* {:async? false
                               :one-off? true}]
    (let [renderer (Midi2AudioRenderer.)
          midi->input-stream #(.renderMidi2Audio renderer % soundbank format)]
      (-> content edna->alda als/score sound/create-sequence!
          midi->input-stream
          (AudioSystem/write AudioFileFormat$Type/WAVE out))))
  out)

(defmethod export! :mp3 [content {:keys [out soundbank format]
                                  :or {out (java.io.ByteArrayOutputStream.)
                                       soundbank default-soundbank
                                       format default-format}}]
  (binding [midi/*midi-synth* (midi/new-midi-synth)
            sound/*play-opts* {:async? false
                               :one-off? true}]
    (let [renderer (Midi2AudioRenderer.)
          midi->input-stream #(.renderMidi2Audio renderer % soundbank format)]
      (with-open [fos (if (instance? java.io.File out)
                        (java.io.FileOutputStream. out)
                        out)]
        (.write fos
          (-> content edna->alda als/score sound/create-sequence!
              midi->input-stream
              Audio/convertAudioInputStream2ByteArray
              (Audio/encodePcmToMp3 format))))))
  out)

(defn edna->data-uri
  "Turns the edna content into a data URI for use in browsers."
  [content]
  (str
    "data:audio/mp3;base64,"
    (-> (binding [*out* (java.io.StringWriter.)]
          (export! content {:type :mp3}))
        .toByteArray
        (base64/encode)
        (String. "UTF-8"))))

