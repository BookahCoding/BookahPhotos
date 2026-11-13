"""Offline nearest-place lookup from the GeoNames `cities500` file (no network lookups).

GeoNames data is licensed Creative Commons Attribution 4.0 (https://www.geonames.org/). The app's About screen must credit it.
Greek names: GeoNames `alternatenames` carries names in many languages without language tags; the first one written in Greek letters is used.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path

DEFAULT_FILE = Path(r"C:\BookahPC\bookahphotos\data\cities500.txt")


@dataclass(frozen=True)
class Place:
    id: int
    name: str
    name_el: str | None
    region: str
    country: str
    lat: float
    lon: float
    pop: int = 0


def _is_greek(s: str) -> bool:
    return any("\u0370" <= c <= "\u03ff" or "\u1f00" <= c <= "\u1fff" for c in s)


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 6371.0 * 2 * math.asin(math.sqrt(a))


class PlaceIndex:
    def __init__(self, places: list[Place], max_km: float = 25.0):
        self.max_km = max_km
        self.by_id = {p.id: p for p in places}
        self.grid: dict[tuple[int, int], list[Place]] = {}
        for p in places:
            self.grid.setdefault((math.floor(p.lat), math.floor(p.lon)), []).append(p)

    @classmethod
    def load(cls, path: Path | str = DEFAULT_FILE, max_km: float = 25.0) -> "PlaceIndex":
        places: list[Place] = []
        with open(path, encoding="utf-8") as f:
            for line in f:
                c = line.rstrip("\n").split("\t")
                if len(c) < 15:
                    continue
                if c[7] == "PPLX":          # a section of a town (for example a city district): the town itself is the better name
                    continue
                greek = next((a for a in c[3].split(",") if a and _is_greek(a)), None)
                places.append(Place(int(c[0]), c[2] or c[1], greek, c[10], c[8], float(c[4]), float(c[5]), int(c[14]) if c[14].isdigit() else 0))
        return cls(places, max_km)

    def largest_near(self, place: "Place", km: float = 25.0) -> "Place":
        """The most populous place within km of `place` (a trip is called after the city, not after the village or district the photos happen to be in)."""
        best = place
        cy, cx = math.floor(place.lat), math.floor(place.lon)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for p in self.grid.get((cy + dy, cx + dx), ()):
                    if p.pop > best.pop and haversine_km(place.lat, place.lon, p.lat, p.lon) <= km:
                        best = p
        return best

    def nearest(self, lat: float, lon: float) -> Place | None:
        # look at the 3x3 neighbouring one-degree cells (about 110 km), which covers the default 25 km radius
        best: tuple[float, Place] | None = None
        cy, cx = math.floor(lat), math.floor(lon)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for p in self.grid.get((cy + dy, cx + dx), ()):
                    d = haversine_km(lat, lon, p.lat, p.lon)
                    if d <= self.max_km and (best is None or d < best[0]):
                        best = (d, p)
        return best[1] if best else None
