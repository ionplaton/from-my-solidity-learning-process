"""New releases and artist links from the open MusicBrainz API (no key required)."""

from urllib.parse import urlparse

MB = "https://musicbrainz.org/ws/2"

# Releases on these labels belong to artists with label marketing budgets
# that do not buy small seeding packages. Matched as lowercase substrings.
MAJOR_LABELS = (
    "universal", "sony music", "warner", "atlantic", "columbia", "interscope",
    "republic records", "rca records", "capitol", "def jam", "island records",
    "epic records", "geffen", "elektra", "parlophone", "polydor", "virgin",
    "motown", "arista", "astralwerks", "big machine", "300 entertainment",
)


def is_major(label):
    name = (label or "").lower()
    return any(m in name for m in MAJOR_LABELS)


def build_release_query(start, end, types=("single", "ep")):
    kinds = " OR ".join(f"primarytype:{t}" for t in types)
    return f"date:[{start} TO {end}] AND ({kinds}) AND status:official"


def parse_release(rel):
    credit = (rel.get("artist-credit") or [{}])[0]
    artist = credit.get("artist") or {}
    labels = [li.get("label", {}).get("name") for li in rel.get("label-info") or []]
    labels = [name for name in labels if name]
    return {
        "release_id": rel.get("id"),
        "title": rel.get("title"),
        "date": rel.get("date"),
        "country": rel.get("country"),
        "type": (rel.get("release-group") or {}).get("primary-type"),
        "label": labels[0] if labels else "",
        "artist_id": artist.get("id"),
        "artist_name": artist.get("name") or credit.get("name"),
    }


def recent_releases(get_json, start, end, types=("single", "ep"), page_size=100, max_pages=10):
    """Yield parsed releases dated between start and end (YYYY-MM-DD, inclusive)."""
    query = build_release_query(start, end, types)
    for page in range(max_pages):
        data = get_json(f"{MB}/release", {
            "query": query, "fmt": "json", "limit": page_size, "offset": page * page_size,
        })
        releases = data.get("releases") or []
        for rel in releases:
            parsed = parse_release(rel)
            if parsed["artist_id"]:
                yield parsed
        if len(releases) < page_size or (page + 1) * page_size >= data.get("count", 0):
            return


LINK_DOMAINS = {
    "instagram": ("instagram.com",),
    "tiktok": ("tiktok.com",),
    "youtube": ("youtube.com", "youtu.be"),
    "spotify": ("spotify.com",),
    "bandcamp": ("bandcamp.com",),
    "soundcloud": ("soundcloud.com",),
    "facebook": ("facebook.com",),
    "x": ("twitter.com", "x.com"),
    "linktree": ("linktr.ee", "lnk.bio", "beacons.ai"),
}


def classify_url(url):
    host = (urlparse(url).hostname or "").lower()
    for kind, domains in LINK_DOMAINS.items():
        if any(host == d or host.endswith("." + d) for d in domains):
            return kind
    return None


def artist_profile(get_json, mbid):
    data = get_json(f"{MB}/artist/{mbid}", {"inc": "url-rels", "fmt": "json"})
    links = {}
    for rel in data.get("relations") or []:
        url = (rel.get("url") or {}).get("resource")
        if not url:
            continue
        kind = classify_url(url)
        if kind is None and rel.get("type") == "official homepage":
            kind = "homepage"
        if kind and kind not in links:
            links[kind] = url
    return {
        "country": data.get("country") or "",
        "type": data.get("type") or "",
        "links": links,
    }
