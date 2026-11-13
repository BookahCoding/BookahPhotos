"""The tag labels (design D7): a fixed list of everyday categories, each with an English and a Greek name. A label's vector is the average of the model's
text vectors for an English and a Greek sentence. A photo gets a tag when its score passes the label's threshold; the thresholds are tuned on Chris's
sample and he reviews a page of examples per label before the tags are used. (slug, English, Greek, English prompt, Greek prompt)"""
from __future__ import annotations

import numpy as np

LABELS: list[tuple[str, str, str, str, str]] = [
    ("beach", "Beach", "Παραλία", "a photo of a beach", "φωτογραφία μιας παραλίας"),
    ("sea", "Sea", "Θάλασσα", "a photo of the sea", "φωτογραφία της θάλασσας"),
    ("mountain", "Mountain", "Βουνό", "a photo of a mountain", "φωτογραφία ενός βουνού"),
    ("forest", "Forest", "Δάσος", "a photo of a forest", "φωτογραφία ενός δάσους"),
    ("snow", "Snow", "Χιόνι", "a photo of snow", "φωτογραφία με χιόνι"),
    ("sunset", "Sunset", "Ηλιοβασίλεμα", "a photo of a sunset", "φωτογραφία ενός ηλιοβασιλέματος"),
    ("night", "Night", "Νύχτα", "a photo taken at night", "φωτογραφία τη νύχτα"),
    ("sky_clouds", "Sky and clouds", "Ουρανός και σύννεφα", "a photo of the sky with clouds", "φωτογραφία του ουρανού με σύννεφα"),
    ("flowers", "Flowers", "Λουλούδια", "a photo of flowers", "φωτογραφία με λουλούδια"),
    ("garden", "Garden", "Κήπος", "a photo of a garden", "φωτογραφία ενός κήπου"),
    ("city", "City", "Πόλη", "a photo of a city street", "φωτογραφία ενός δρόμου της πόλης"),
    ("village", "Village", "Χωριό", "a photo of a small village", "φωτογραφία ενός μικρού χωριού"),
    ("monument", "Monument", "Μνημείο", "a photo of an ancient monument or ruins", "φωτογραφία ενός αρχαίου μνημείου ή ερειπίων"),
    ("church", "Church", "Εκκλησία", "a photo of a church", "φωτογραφία μιας εκκλησίας"),
    ("building", "Building", "Κτίριο", "a photo of a building", "φωτογραφία ενός κτιρίου"),
    ("road", "Road", "Δρόμος", "a photo of a road", "φωτογραφία ενός δρόμου"),
    ("car", "Car", "Αυτοκίνητο", "a photo of a car", "φωτογραφία ενός αυτοκινήτου"),
    ("motorbike", "Motorbike", "Μηχανή", "a photo of a motorbike", "φωτογραφία μιας μηχανής"),
    ("boat", "Boat", "Βάρκα", "a photo of a boat or a ship", "φωτογραφία μιας βάρκας ή ενός πλοίου"),
    ("airplane", "Airplane", "Αεροπλάνο", "a photo of an airplane", "φωτογραφία ενός αεροπλάνου"),
    ("train", "Train", "Τρένο", "a photo of a train", "φωτογραφία ενός τρένου"),
    ("food", "Food", "Φαγητό", "a photo of a meal", "φωτογραφία ενός γεύματος"),
    ("coffee_drink", "Coffee and drinks", "Καφές και ποτά", "a photo of a coffee or a drink", "φωτογραφία ενός καφέ ή ενός ποτού"),
    ("fruit_veg", "Fruit and vegetables", "Φρούτα και λαχανικά", "a photo of fruit and vegetables", "φωτογραφία με φρούτα και λαχανικά"),
    ("restaurant", "Restaurant", "Εστιατόριο", "a photo inside a restaurant", "φωτογραφία μέσα σε εστιατόριο"),
    ("dog", "Dog", "Σκύλος", "a photo of a dog", "φωτογραφία ενός σκύλου"),
    ("animal", "Animals", "Ζώα", "a photo of an animal", "φωτογραφία ενός ζώου"),
    ("baby", "Baby", "Μωρό", "a photo of a baby", "φωτογραφία ενός μωρού"),
    ("child", "Children", "Παιδιά", "a photo of a child", "φωτογραφία ενός παιδιού"),
    ("group", "Group of people", "Παρέα", "a group photo of many people", "ομαδική φωτογραφία πολλών ανθρώπων"),
    ("selfie", "Selfie", "Selfie", "a selfie", "μια selfie"),
    ("portrait", "Portrait", "Πορτρέτο", "a portrait of a person", "πορτρέτο ενός ανθρώπου"),
    ("wedding", "Wedding", "Γάμος", "a photo of a wedding", "φωτογραφία ενός γάμου"),
    ("birthday", "Birthday", "Γενέθλια", "a photo of a birthday party", "φωτογραφία ενός πάρτι γενεθλίων"),
    ("party", "Party", "Πάρτι", "a photo of a party", "φωτογραφία ενός πάρτι"),
    ("christmas", "Christmas", "Χριστούγεννα", "a photo of Christmas decorations", "φωτογραφία με χριστουγεννιάτικη διακόσμηση"),
    ("playground", "Playground", "Παιδική χαρά", "a photo of a playground", "φωτογραφία μιας παιδικής χαράς"),
    ("sports", "Sports", "Αθλήματα", "a photo of people playing sports", "φωτογραφία ανθρώπων που παίζουν σπορ"),
    ("swimming", "Swimming", "Κολύμπι", "a photo of people swimming", "φωτογραφία ανθρώπων που κολυμπούν"),
    ("concert", "Concert", "Συναυλία", "a photo of a concert", "φωτογραφία μιας συναυλίας"),
    ("museum", "Museum", "Μουσείο", "a photo inside a museum", "φωτογραφία μέσα σε μουσείο"),
    ("indoors", "Indoors", "Εσωτερικός χώρος", "a photo of a room indoors", "φωτογραφία ενός δωματίου"),
    ("document", "Document", "Έγγραφο", "a photo of a paper document", "φωτογραφία ενός εγγράφου"),
    ("text", "Text and signs", "Κείμενο και πινακίδες", "a photo of a sign with text", "φωτογραφία μιας πινακίδας με κείμενο"),
    ("drawing", "Drawing", "Σχέδιο", "a drawing or an illustration", "ένα σχέδιο ή μια εικονογράφηση"),
    ("toys", "Toys", "Παιχνίδια", "a photo of toys", "φωτογραφία με παιχνίδια"),
    ("computer", "Computer and screens", "Υπολογιστής και οθόνες", "a photo of a computer or a screen", "φωτογραφία ενός υπολογιστή ή μιας οθόνης"),
    ("shop", "Shopping", "Ψώνια", "a photo of a shop or a market", "φωτογραφία ενός μαγαζιού ή μιας αγοράς"),
    ("home", "At home", "Στο σπίτι", "a photo of a living room at home", "φωτογραφία ενός σαλονιού στο σπίτι"),
]

_SLUG_INDEX = {l[0]: i for i, l in enumerate(LABELS)}
DEFAULT_THRESHOLD = 0.21     # cosine score; used only when a label has no tuned threshold (see docs/phase-4a-report.md)
MAX_TAGS = 5


EXCLUSIVE: list[tuple[str, str]] = []     # pairs of labels of which only the higher score keeps its tag (none needed now that cat is dropped)

# Chris's review (12 Nov 2026, two rounds, 12 passing examples per label): the share of the passing pictures to KEEP, by raising the threshold,
# applied to the first-guess thresholds. Labels he called good or ok are not listed.
# DROPPED after round 2 because they stayed wrong however strict: lake_river, bridge, cake, cat, bird, horse, receipt, screenshot, map, painting, game_art,
# book, art_statue. (Search by content does not need tags; screenshots are known from the file name, flag FLAG_SCREENSHOT; documents have the document tag.)
KEEP_AFTER_REVIEW: dict[str, float] = {
    "snow": 0.7, "city": 0.6, "car": 0.9, "motorbike": 0.9, "boat": 0.5, "train": 0.25, "coffee_drink": 0.5, "dog": 0.7, "selfie": 0.25, "birthday": 0.25,
}


def tighten(scores: np.ndarray, thresholds: np.ndarray, keep: dict[str, float]) -> np.ndarray:
    """New thresholds: for a label in `keep`, the threshold that keeps only that share of the pictures that pass now (rounded up to whole
    pictures, never fewer than the best one; a threshold only ever goes up)."""
    th = thresholds.copy()
    for i, lab in enumerate(LABELS):
        frac = keep.get(lab[0])
        if frac is None:
            continue
        passing = np.sort(scores[:, i][scores[:, i] >= th[i]])[::-1]          # best first
        if len(passing) == 0:
            continue
        n_keep = max(1, int(np.ceil(frac * len(passing))))
        cut = float(passing[n_keep - 1])                                       # the weakest picture still kept
        th[i] = cut
    return th


def thresholds_text() -> str:
    from pathlib import Path
    return Path(__file__).with_name("label_thresholds.json").read_text(encoding="utf-8")


def load_thresholds() -> np.ndarray:
    """The thresholds agreed with Chris (label_thresholds.json: numbers only, no photos); DEFAULT_THRESHOLD for a label that is missing."""
    import json
    from pathlib import Path
    saved = json.loads((Path(__file__).with_name("label_thresholds.json")).read_text(encoding="utf-8"))
    return np.array([saved.get(l[0], DEFAULT_THRESHOLD) for l in LABELS], np.float32)


def label_vectors(text_encoder) -> np.ndarray:
    """[len(LABELS),512] normalised: the mean of the English and the Greek sentence vectors of each label."""
    en = text_encoder.encode([l[3] for l in LABELS])
    el = text_encoder.encode([l[4] for l in LABELS])
    m = en + el
    return m / np.linalg.norm(m, axis=1, keepdims=True)


def cached_label_vectors(cache_file, text_encoder_factory, model_name: str) -> np.ndarray:
    """The label vectors, made once and kept in a small file (keyed by the label texts and the model), so building a snapshot does not load the
    1.1 GB text model every time."""
    import hashlib
    import json
    from pathlib import Path
    cache_file = Path(cache_file)
    key = hashlib.sha1(json.dumps([LABELS, model_name], ensure_ascii=False).encode("utf-8")).hexdigest()
    if cache_file.exists():
        z = np.load(cache_file, allow_pickle=False)
        if str(z["key"]) == key:
            return z["v"]
    enc = text_encoder_factory()
    v = label_vectors(enc)
    try:
        enc.close()
    except Exception:
        pass
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    np.savez(cache_file, key=np.array(key), v=v.astype(np.float32))
    return v


def assign_tags(vecs: np.ndarray, labvecs: np.ndarray, thresholds: np.ndarray | None = None, max_tags: int = MAX_TAGS) -> list[list[tuple[int, float]]]:
    """For each picture vector the labels whose score passes the label's threshold, best first, at most max_tags: [(label index, score)]."""
    th = thresholds if thresholds is not None else np.full(len(labvecs), DEFAULT_THRESHOLD, np.float32)
    S = vecs @ labvecs.T
    out = []
    for row in S:
        idx = [i for i in np.argsort(-row)[:max_tags] if row[i] >= th[i]]
        for a, b in EXCLUSIVE:
            ia, ib = _SLUG_INDEX[a], _SLUG_INDEX[b]
            if ia in idx and ib in idx:
                idx.remove(ia if row[ia] < row[ib] else ib)
        out.append([(int(i), float(row[i])) for i in idx])
    return out
