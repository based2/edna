(ns edna.core
  (:require [alda.lisp :as al]
            [alda.now :as now]
            [edna.parse :as parse]
            [clojure.string :as str]))

(def default-attrs {:octave 4 :length 1/4 :tempo 120
                    :pan 50 :quantize 90 :transpose 0
                    :volume 100 :parent-ids []})

(defmulti build-score (fn [val parent-attrs] (first val)))

(defmethod build-score :score [[_ {:keys [subscores] :as score}]
                               {:keys [sibling-id parent-ids] :as parent-attrs}]
  (let [id (inc (or sibling-id 0))
        {:keys [instrument] :as attrs} (merge parent-attrs (select-keys score [:instrument]))]
    [(al/part (if instrument (name instrument) {})
       (when sibling-id
         (al/at-marker (str/join "." (conj parent-ids sibling-id))))
       (first
         (reduce
           (fn [[subscores attrs] subscore]
             (let [attrs (assoc attrs :parent-ids
                           (conj parent-ids id))
                   [subscore attrs] (build-score subscore attrs)]
               [(conj subscores subscore) attrs]))
           [[] (dissoc attrs :sibling-id)]
           (vec subscores)))
       (al/marker (str/join "." (conj parent-ids id))))
     (assoc parent-attrs :sibling-id id)]))

(defmethod build-score :concurrent-score [[_ scores]
                                          {:keys [sibling-id parent-ids] :as parent-attrs}]
  (let [id (inc (or sibling-id 0))
        instruments (map :instrument scores)]
    (when (not= (count instruments) (count (set instruments)))
      (throw (Exception. (str
                           "Can't use the same instrument "
                           "multiple times in the same set "
                           "(this limitation my change eventually)"))))
    [(al/part {}
       (reduce
         (fn [scores score]
           (let [[score _] (build-score [:score score] parent-attrs)]
             (conj scores score)))
         []
         (vec scores))
       (al/marker (str/join "." (conj parent-ids id))))
     (assoc parent-attrs :sibling-id id)]))

(defmethod build-score :attrs [[_ {:keys [note] :as attrs}] parent-attrs]
  (if note
    [(first (build-score [:note note] (merge parent-attrs attrs)))
     parent-attrs]
    [nil (merge parent-attrs attrs)]))

(defmethod build-score :note [[_ note]
                              {:keys [instrument octave length tempo
                                      pan quantize transpose volume
                                      sibling-id parent-ids]
                               :as parent-attrs}]
  (when-not instrument
    (throw (Exception. (str "Can't play " note " without specifying an instrument"))))
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
    [[(when sibling-id
        (al/at-marker (str/join "." (conj parent-ids sibling-id))))
      (al/octave (+ octave octave-change))
      (al/tempo tempo)
      (al/pan pan)
      (al/quantize quantize)
      (al/transpose transpose)
      (al/volume volume)
      (al/note
        (or (some->> accidental (al/pitch note))
            (al/pitch note))
        (al/duration (al/note-length (/ 1 length))))
      (al/marker (str/join "." (conj parent-ids id)))]
     (assoc parent-attrs :sibling-id id)]))

(defmethod build-score :chord [[_ chord]
                               {:keys [sibling-id parent-ids] :as parent-attrs}]
  (let [id (inc (or sibling-id 0))
        attrs (-> parent-attrs
                  (assoc :parent-ids (conj parent-ids id))
                  (dissoc :sibling-id))]
    [[(when sibling-id
        (al/at-marker (str/join "." (conj parent-ids sibling-id))))
      (apply al/chord
        (map (fn [note]
               (first (build-score note attrs)))
          chord))
      (al/marker (str/join "." (conj parent-ids id)))]
     (assoc parent-attrs :sibling-id id)]))

(defmethod build-score :rest [[_ _] {:keys [length] :as parent-attrs}]
  [(al/pause (al/duration (al/note-length (/ 1 length))))
   parent-attrs])

(defmethod build-score :length [[_ length] parent-attrs]
  [nil (assoc parent-attrs :length length)])

(defmethod build-score :default [[subscore-name] parent-attrs]
  (throw (Exception. (str subscore-name " not recognized"))))

(defn edna->alda [content]
  (first (build-score (parse/parse content) default-attrs)))

(defn stop! [score]
  (some-> score now/tear-down!)
  nil)

(defn play! [content]
  (let [content (or (parse/find-focus content) content)]
    (now/with-new-score
      (now/play! (edna->alda content)))))

