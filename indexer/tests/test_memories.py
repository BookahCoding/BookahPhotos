"""Phase 4c: memories and trips by rules, with generated photos (never real ones)."""
from datetime import date, datetime, timedelta, timezone

from bookahphotos_indexer import memories
from bookahphotos_indexer.places import Place, PlaceIndex
from bookahphotos_indexer.state import State

HOME = (37.98, 23.73)          # a made-up home
FAR = (37.78, 20.90)           # about 250 km away
PLACES = PlaceIndex([Place(111, "Faraway", "Μακρινό", "R", "GR", FAR[0], FAR[1]), Place(222, "Homeville", "Σπιτοχώρι", "R", "GR", HOME[0], HOME[1])])


class World:
    def __init__(self, tmp_path):
        self.st = State(tmp_path / "m.db")
        self.n = 0

    def add(self, day: date, lat=None, lon=None, hour=12, flags=0, sharp=100.0, place=None, w=4000, h=3000) -> int:
        self.n += 1
        fid = self.n
        t = int(datetime(day.year, day.month, day.day, hour, tzinfo=timezone.utc).timestamp())
        self.st.upsert_listed(fid, f"P/{fid}.jpg", f"e{fid}", 10, "image/jpeg", 1)
        self.st.set_fields(fid, {"taken_utc": t, "taken_offset_min": 0, "day_key": day.isoformat(), "latitude": lat, "longitude": lon, "place_id": place,
                                 "width": w, "height": h, "flags": flags}, level=1)
        self.st.save_content(fid, f"e{fid}", "ok", "preview1024", "m", b"\0" * 1024, sharp)
        return fid

    def home_days(self, start: date, n: int, per=1):
        for i in range(n):
            for _ in range(per):
                self.add(start + timedelta(days=i), HOME[0], HOME[1], hour=21, place=222)

    def trip(self, start: date, days: int, per: int):
        for i in range(days):
            for _ in range(per):
                self.add(start + timedelta(days=i), FAR[0] + 0.01, FAR[1], place=111)


def find(w, today=date(2026, 11, 13), home=HOME):
    return memories.find_memories(w.st, PLACES, home, today)[0]


def of(ms, typ):
    return [m for m in ms if m.type == typ]


def test_home_is_where_most_days_are_spent_not_where_the_trips_are(tmp_path):
    w = World(tmp_path)
    w.home_days(date(2025, 1, 1), 60)
    w.trip(date(2025, 8, 1), 6, 5)
    lat, lon = memories.find_home(memories.load_photos(w.st))
    assert abs(lat - HOME[0]) < 0.05 and abs(lon - HOME[1]) < 0.05


def test_a_trip_is_found_with_a_two_language_title_and_a_cover(tmp_path):
    w = World(tmp_path)
    w.trip(date(2024, 8, 10), 5, 6)
    t = of(find(w), "trip")
    assert len(t) == 1
    assert t[0].title_en == "Faraway, Aug 2024" and t[0].title_el == "Μακρινό, Αυγ 2024"
    assert (t[0].start_day, t[0].end_day) == ("2024-08-10", "2024-08-14") and t[0].total_photos == 30 and t[0].cover_asset in t[0].asset_ids


def test_trip_is_named_after_the_biggest_place_nearby_not_the_village(tmp_path):
    places = PlaceIndex([Place(1, "Tinyvillage", None, "R", "GR", FAR[0], FAR[1], 300), Place(2, "Bigcity", "Μεγαλούπολη", "R", "GR", FAR[0] + 0.1, FAR[1] + 0.1, 500_000),
                         Place(3, "Distantcity", None, "R", "GR", FAR[0] + 3, FAR[1], 900_000)])
    w = World(tmp_path)
    for i in range(4):
        for _ in range(6):
            w.add(date(2024, 8, 10) + timedelta(days=i), FAR[0], FAR[1], place=1)
    t = of(memories.find_memories(w.st, places, HOME, date(2026, 11, 13))[0], "trip")
    assert t[0].title_en == "Bigcity, Aug 2024" and t[0].title_el == "Μεγαλούπολη, Αυγ 2024"      # the far city (about 330 km away) is not used


def test_trip_needs_two_days_and_fifteen_photos(tmp_path):
    w = World(tmp_path)
    w.trip(date(2024, 3, 1), 1, 30)          # one day: a day trip, not a trip
    w.trip(date(2024, 5, 1), 3, 4)           # 12 photos: too few
    assert of(find(w), "trip") == []


def test_gaps_of_two_days_are_bridged_but_three_days_split_the_trip(tmp_path):
    w = World(tmp_path)
    for d in (date(2024, 6, 1), date(2024, 6, 2), date(2024, 6, 5)):                 # missing days: 3rd and 4th = a gap of two days
        for _ in range(7):
            w.add(d, FAR[0], FAR[1], place=111)
    assert len(of(find(w), "trip")) == 1


def test_three_missing_days_make_two_trips(tmp_path):
    w = World(tmp_path)
    for d in (date(2024, 6, 1), date(2024, 6, 2), date(2024, 6, 6), date(2024, 6, 7)):
        for _ in range(8):
            w.add(d, FAR[0], FAR[1], place=111)
    assert len(of(find(w), "trip")) == 2


def test_months_need_sixty_photos_and_the_current_month_is_skipped(tmp_path):
    w = World(tmp_path)
    for i in range(61):
        w.add(date(2025, 3, 1 + i % 28))
    for i in range(59):
        w.add(date(2025, 4, 1 + i % 28))
    for i in range(70):
        w.add(date(2026, 11, 1 + i % 12))
    ms = of(find(w, today=date(2026, 11, 13)), "month")
    assert [m.title_en for m in ms] == ["March 2025"] and ms[0].title_el == "Μάρτιος 2025"
    assert len(ms[0].asset_ids) == 40 and ms[0].total_photos == 61


def test_on_this_day_needs_two_years_and_covers_neighbouring_days(tmp_path):
    w = World(tmp_path)
    for y in (2022, 2023, 2024):
        for _ in range(3):
            w.add(date(y, 8, 10))
    ms = of(find(w), "on_this_day")
    keys = sorted(m.key for m in ms)
    assert keys == ["otd:08-09", "otd:08-10", "otd:08-11"]                      # the +-1 day window


def test_on_this_day_one_year_only_is_not_a_memory(tmp_path):
    w = World(tmp_path)
    for _ in range(12):
        w.add(date(2024, 8, 10))
    assert of(find(w), "on_this_day") == []


def test_person_over_time_needs_a_name_forty_photos_and_three_years(tmp_path):
    w = World(tmp_path)
    ids = []
    for y in (2021, 2022, 2023, 2024):
        for i in range(12):
            ids.append(w.add(date(y, 5, 1 + i)))
    st = w.st
    st.db.execute("INSERT INTO persons(person_id, name, created) VALUES (1, 'Alex', 0)")
    st.db.execute("INSERT INTO persons(person_id, name, created) VALUES (2, NULL, 0)")
    for fid in ids:
        for pid in (1, 2):
            st.db.execute("INSERT INTO faces(file_id, x1, y1, x2, y2, score, width_1280, usable, emb, person_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
                          (fid, 0.1, 0.1, 0.3, 0.4, 0.9, 90.0, 1, b"\0" * 512, pid))
    st.db.commit()
    ms = of(find(w), "person")
    assert [m.title_en for m in ms] == ["Alex over the years"] and ms[0].title_el == "Alex μέσα στα χρόνια"      # the unnamed person has no memory


def test_screenshots_hidden_photos_and_unwanted_memories_are_left_out(tmp_path):
    w = World(tmp_path)
    shots = [w.add(date(2025, 3, 1 + i % 28), flags=2, sharp=9999.0) for i in range(30)]
    good = [w.add(date(2025, 3, 1 + i % 28)) for i in range(70)]
    w.st.db.execute("INSERT INTO hidden_files(file_id) VALUES (?)", (good[0],)); w.st.db.commit()
    m = of(find(w), "month")[0]
    assert m.total_photos == 69 and not set(m.asset_ids) & set(shots) and m.cover_asset not in shots and good[0] not in m.asset_ids
    w.st.db.execute("INSERT INTO unwanted_memories(memory_key) VALUES ('month:2025-03')"); w.st.db.commit()
    assert of(find(w), "month") == []


def test_pick_spreads_over_days_and_prefers_quality():
    d = date(2025, 1, 1)
    ph = [memories.Photo(i, d + timedelta(days=i // 10), 1000 + i, 12, None, None, None, float(i % 10), False) for i in range(50)]      # 5 days x 10
    chosen = memories.pick(ph, 10)
    assert len(chosen) == 10 and len({p.day for p in chosen}) == 5                  # two from each day, not ten from one
    assert all(p.quality >= 8 for p in chosen)                                       # the two best of every day
    assert [p.taken_utc for p in chosen] == sorted(p.taken_utc for p in chosen)      # in time order


def test_home_override_is_used(tmp_path):
    w = World(tmp_path)
    w.home_days(date(2025, 1, 1), 30)
    w.trip(date(2025, 6, 1), 4, 6)
    far_home = find(w, home=FAR)                                                      # Chris says home is the "far" place: the stays at HOME are trips
    assert of(far_home, "trip") and of(far_home, "trip")[0].title_en.startswith("Homeville")
