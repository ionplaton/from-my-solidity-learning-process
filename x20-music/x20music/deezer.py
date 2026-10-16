"""Audience size from the public Deezer API (no key required)."""

DEEZER = "https://api.deezer.com"


def fan_count(get_json, artist_name):
    """Return Deezer nb_fan for an exact (case-insensitive) name match, else None."""
    data = get_json(f"{DEEZER}/search/artist", {"q": artist_name, "limit": 5})
    wanted = artist_name.strip().lower()
    for hit in data.get("data") or []:
        if (hit.get("name") or "").strip().lower() == wanted:
            return hit.get("nb_fan")
    return None
