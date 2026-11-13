"""Memories and trips by plain rules (design 3.5, no model). Pure functions over the indexer's private database: `find_memories` returns the memories,
`find_home` the home place (Chris sees it and can correct it with `--home`). Titles in English and Greek. Each memory has a stable `key` so that the op
`unwanted_memory` keeps it from ever coming back."""
from __future__ import annotations

import math
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from .places import PlaceIndex, haversine_km
from .state import State

# ---- the rules (first proposal; tuned on the real library) ----
TRIP_KM = 50.0
TRIP_GAP_DAYS = 2            # missing days that are bridged inside a trip
TRIP_MIN_DAYS = 2
TRIP_MIN_PHOTOS = 15
OTD_MIN_PHOTOS = 8
OTD_MIN_YEARS = 2
OTD_WINDOW_DAYS = 1
MONTH_MIN_PHOTOS = 60
PERSON_MIN_PHOTOS = 40
PERSON_MIN_YEARS = 3
CAP = {"on_this_day": 40, "trip": 60, "month": 40, "person": 40}
HOME_YEARS = 2
FLAG_SCREENSHOT = 2

MONTHS_EN = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
MONTHS_EL = ["Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος", "Ιούνιος", "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος"]
SHORT_EL = ["Ιαν", "Φεβ", "Μαρ", "Απρ", "Μάι", "Ιουν", "Ιουλ", "Αυγ", "Σεπ", "Οκτ", "Νοε", "Δεκ"]


@dataclass
class Photo:
    file_id: int
    day: date
    taken_utc: int
    hour: int | None
    lat: float | None
    lon: float | None
    place_id: int | None
    quality: float
    has_face: bool


@dataclass
class Memory:
    key: str
    type: str                      # on_this_day | trip | month | person
    title_en: str
    title_el: str
    start_day: str                 # YYYY-MM-DD, or --MM-DD for 'on this day'
    end_day: str
    asset_ids: list[int]           # chronological, capped
    cover_asset: int
    total_photos: int
    extra: dict = field(default_factory=dict)


def load_photos(state: State) -> list[Photo]:
    """Dated, live photos and videos that are not screenshots and not hidden; with the best-known quality (sharpness, a face, size)."""
    faced = {r[0] for r in state.db.execute("SELECT DISTINCT file_id FROM faces WHERE usable=1 AND rejected=0")}
    hidden = {r[0] for r in state.db.execute("SELECT file_id FROM hidden_files")}
    out = []
    for r in state.db.execute("SELECT f.file_id, f.taken_utc, f.taken_offset_min, f.day_key, f.latitude, f.longitude, f.place_id, f.width, f.height, f.flags, c.sharp "
                              "FROM files f LEFT JOIN content c ON c.file_id=f.file_id AND c.etag=f.etag AND c.status='ok' "
                              "WHERE f.deleted=0 AND f.level>=1 AND f.taken_utc IS NOT NULL AND f.day_key IS NOT NULL AND (f.flags & ?)=0", (FLAG_SCREENSHOT,)):
        if r["file_id"] in hidden:
            continue
        try:
            day = date.fromisoformat(r["day_key"])
        except ValueError:
            continue
        hour = int(((r["taken_utc"] + (r["taken_offset_min"] or 0) * 60) // 3600) % 24) if r["taken_offset_min"] is not None else None
        px = (r["width"] or 0) * (r["height"] or 0)
        q = math.log1p(r["sharp"] or 0.0) + (1.5 if r["file_id"] in faced else 0.0) + min(px / 12e6, 1.0)
        out.append(Photo(r["file_id"], day, r["taken_utc"], hour, r["latitude"], r["longitude"], r["place_id"], q, r["file_id"] in faced))
    return out


# ---- home ----
def find_home(photos: list[Photo]) -> tuple[float, float] | None:
    """The place where most days' photos were taken in the last two years (at night if enough nights are known): the densest 0.1 degree cell."""
    gps = [p for p in photos if p.lat is not None and p.lon is not None]
    if not gps:
        return None
    newest = max(p.day for p in gps)
    recent = [p for p in gps if (newest - p.day).days <= 365 * HOME_YEARS] or gps
    night = [p for p in recent if p.hour is not None and (p.hour >= 19 or p.hour < 7)]
    use = night if len({p.day for p in night}) >= 20 else recent
    days: dict[tuple[float, float], set[date]] = defaultdict(set)
    for p in use:
        days[(round(p.lat, 1), round(p.lon, 1))].add(p.day)
    cell = max(days, key=lambda c: (len(days[c]), c))
    members = [p for p in use if (round(p.lat, 1), round(p.lon, 1)) == cell]
    return (sum(p.lat for p in members) / len(members), sum(p.lon for p in members) / len(members))


# ---- choosing pictures ----
def pick(photos: list[Photo], cap: int) -> list[Photo]:
    """Up to `cap` photos: the best of each day first, then the second best of each day, and so on; returned in time order."""
    by_day: dict[date, list[Photo]] = defaultdict(list)
    for p in photos:
        by_day[p.day].append(p)
    for v in by_day.values():
        v.sort(key=lambda p: (-p.quality, p.file_id))
    chosen: list[Photo] = []
    rank = 0
    while len(chosen) < cap and any(len(v) > rank for v in by_day.values()):
        layer = [v[rank] for d, v in sorted(by_day.items()) if len(v) > rank]
        layer.sort(key=lambda p: (-p.quality, p.file_id))
        chosen += layer[: cap - len(chosen)]
        rank += 1
    return sorted(chosen, key=lambda p: (p.taken_utc, p.file_id))


def _memory(key: str, typ: str, en: str, el: str, start: str, end: str, group: list[Photo], extra: dict | None = None) -> Memory:
    chosen = pick(group, CAP[typ])
    cover = max(chosen, key=lambda p: (p.quality, -p.file_id))
    return Memory(key, typ, en, el, start, end, [p.file_id for p in chosen], cover.file_id, len(group), extra or {})


def _place_names(places: PlaceIndex | None, pid: int | None) -> tuple[str, str]:
    if places is not None and pid in places.by_id:
        p = places.largest_near(places.by_id[pid])
        return p.name, p.name_el or p.name
    return "Trip", "Ταξίδι"


# ---- the four kinds ----
def trips(photos: list[Photo], home: tuple[float, float], places: PlaceIndex | None) -> list[Memory]:
    away: dict[date, list[Photo]] = defaultdict(list)
    for p in photos:
        if p.lat is not None and p.lon is not None and haversine_km(home[0], home[1], p.lat, p.lon) > TRIP_KM:
            away[p.day].append(p)
    out: list[Memory] = []
    spans: list[list[date]] = []
    for d in sorted(away):
        if spans and (d - spans[-1][-1]).days <= TRIP_GAP_DAYS + 1:
            spans[-1].append(d)
        else:
            spans.append([d])
    for span in spans:
        start, end = span[0], span[-1]
        if (end - start).days + 1 < TRIP_MIN_DAYS:
            continue
        group = [p for p in photos if start <= p.day <= end and (p.lat is None or p.lon is None or haversine_km(home[0], home[1], p.lat, p.lon) > TRIP_KM)]
        if len(group) < TRIP_MIN_PHOTOS:
            continue
        pid = Counter(p.place_id for d in span for p in away[d] if p.place_id is not None).most_common(1)
        name_en, name_el = _place_names(places, pid[0][0] if pid else None)
        en = f"{name_en}, {MONTHS_EN[start.month - 1][:3]} {start.year}"
        el = f"{name_el}, {SHORT_EL[start.month - 1]} {start.year}"
        out.append(_memory(f"trip:{start.isoformat()}:{pid[0][0] if pid else 0}", "trip", en, el, start.isoformat(), end.isoformat(), group,
                           {"days": (end - start).days + 1}))
    return out


def months(photos: list[Photo], today: date | None = None) -> list[Memory]:
    by_month: dict[tuple[int, int], list[Photo]] = defaultdict(list)
    for p in photos:
        by_month[(p.day.year, p.day.month)].append(p)
    today = today or date.today()
    out = []
    for (y, m), group in sorted(by_month.items()):
        if len(group) < MONTH_MIN_PHOTOS:
            continue
        if (y, m) == (today.year, today.month):
            continue                                             # the month that is still going on is not a review yet
        first = date(y, m, 1)
        last = (date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1))
        out.append(_memory(f"month:{y}-{m:02d}", "month", f"{MONTHS_EN[m - 1]} {y}", f"{MONTHS_EL[m - 1]} {y}", first.isoformat(), last.isoformat(), group))
    return out


def on_this_day(photos: list[Photo]) -> list[Memory]:
    """One memory for every calendar day that has at least OTD_MIN_PHOTOS photos from at least OTD_MIN_YEARS different years within +-1 day.
    The app shows the one for today's date (and hides it when all its photos are from the current year)."""
    by_md: dict[tuple[int, int], list[Photo]] = defaultdict(list)
    for p in photos:
        if p.day.month == 2 and p.day.day == 29:
            continue
        by_md[(p.day.month, p.day.day)].append(p)
    out = []
    for month in range(1, 13):
        for day in range(1, 32):
            try:
                centre = date(2001, month, day)                  # a non-leap year just to walk the calendar
            except ValueError:
                continue
            group: list[Photo] = []
            for off in range(-OTD_WINDOW_DAYS, OTD_WINDOW_DAYS + 1):
                d = centre + timedelta(days=off)
                group += by_md.get((d.month, d.day), [])
            if len(group) >= OTD_MIN_PHOTOS and len({p.day.year for p in group}) >= OTD_MIN_YEARS:
                out.append(_memory(f"otd:{month:02d}-{day:02d}", "on_this_day", "On this day", "Σαν σήμερα", f"--{month:02d}-{day:02d}", f"--{month:02d}-{day:02d}", group,
                                   {"years": sorted({p.day.year for p in group})}))
    return out


def person_over_time(state: State, photos: list[Photo]) -> list[Memory]:
    by_id = {p.file_id: p for p in photos}
    rows = state.db.execute("SELECT DISTINCT f.person_id, p.name, f.file_id FROM faces f JOIN persons p ON p.person_id=f.person_id "
                            "WHERE p.merged_into IS NULL AND p.hidden=0 AND p.name IS NOT NULL AND f.rejected=0 AND f.usable=1").fetchall()
    by_person: dict[int, tuple[str, list[Photo]]] = {}
    for pid, name, fid in rows:
        if fid in by_id:
            by_person.setdefault(pid, (name, []))[1].append(by_id[fid])
    out = []
    for pid, (name, group) in sorted(by_person.items()):
        if len(group) >= PERSON_MIN_PHOTOS and len({p.day.year for p in group}) >= PERSON_MIN_YEARS:
            years = sorted({p.day.year for p in group})
            out.append(_memory(f"person:{pid}", "person", f"{name} over the years", f"{name} μέσα στα χρόνια", f"{years[0]}-01-01", f"{years[-1]}-12-31", group,
                               {"person_id": pid, "years": years}))
    return out


def find_memories(state: State, places: PlaceIndex | None, home: tuple[float, float] | None = None, today: date | None = None) -> tuple[list[Memory], tuple[float, float] | None]:
    photos = load_photos(state)
    if home is None:
        kv = state.kv_get("home")
        home = tuple(float(x) for x in kv.split(",")) if kv else find_home(photos)       # type: ignore[assignment]
    unwanted = {r[0] for r in state.db.execute("SELECT memory_key FROM unwanted_memories")}
    found = (trips(photos, home, places) if home else []) + months(photos, today) + on_this_day(photos) + person_over_time(state, photos)
    return [m for m in found if m.key not in unwanted], home
