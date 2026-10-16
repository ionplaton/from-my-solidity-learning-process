"""Daily lead pipeline: fresh indie releases -> enriched, scored, deduplicated artists."""

import csv
import re
import sqlite3
from dataclasses import asdict, dataclass, field
from datetime import date, timedelta

from . import deezer, musicbrainz

# Below this the artist has no audience and rarely a budget; above it they have a team.
FANS_SWEET_SPOT = (300, 50_000)
FANS_MAX = 250_000

EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
EMAIL_JUNK = ("example.", "sentry", "wixpress", ".png", ".jpg", "@2x", "domain.com")


@dataclass
class Lead:
    artist_id: str
    artist_name: str
    release_title: str = ""
    release_date: str = ""
    release_type: str = ""
    label: str = ""
    country: str = ""
    fans: int | None = None
    email: str = ""
    instagram: str = ""
    tiktok: str = ""
    youtube: str = ""
    spotify: str = ""
    homepage: str = ""
    other_links: str = ""
    source: str = "musicbrainz"
    score: int = 0
    reasons: list = field(default_factory=list)

    def row(self):
        d = asdict(self)
        d["reasons"] = "; ".join(self.reasons)
        d["fans"] = "" if self.fans is None else self.fans
        return d


FIELDS = list(Lead.__dataclass_fields__)


def score(lead, today):
    """Higher = more likely to buy a seeding package and easier to reach."""
    pts, why = 0, []
    if lead.fans is not None:
        lo, hi = FANS_SWEET_SPOT
        if lo <= lead.fans <= hi:
            pts += 3
            why.append(f"{lead.fans} fans: indie sweet spot")
        elif hi < lead.fans <= FANS_MAX:
            pts += 1
            why.append(f"{lead.fans} fans: growing")
    if lead.email:
        pts += 3
        why.append("email found")
    if lead.tiktok or lead.instagram:
        pts += 2
        why.append("active on TikTok/IG")
    if lead.homepage:
        pts += 1
    if lead.country == "US":
        pts += 2
        why.append("US artist")
    if not lead.label or lead.label.lower() in ("[no label]", lead.artist_name.lower()):
        pts += 2
        why.append("self-released")
    try:
        if (today - date.fromisoformat(lead.release_date)).days <= 3:
            pts += 1
            why.append("released in last 3 days")
    except ValueError:
        pass
    if lead.source != "musicbrainz":
        pts += 5
        why.append(f"already pays for promo ({lead.source})")
    lead.score, lead.reasons = pts, why
    return lead


def extract_emails(html):
    found = []
    for m in EMAIL_RE.findall(html):
        e = m.lower().rstrip(".")
        if not any(j in e for j in EMAIL_JUNK) and e not in found:
            found.append(e)
    return found


class SeenStore:
    """Remembers artists already surfaced so each lead shows up once per cooldown window."""

    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS seen (artist_id TEXT PRIMARY KEY, last_seen TEXT)"
        )

    def is_fresh(self, artist_id, today, cooldown_days):
        row = self.db.execute(
            "SELECT last_seen FROM seen WHERE artist_id = ?", (artist_id,)
        ).fetchone()
        return row is None or date.fromisoformat(row[0]) <= today - timedelta(days=cooldown_days)

    def mark(self, artist_id, today):
        self.db.execute(
            "INSERT INTO seen VALUES (?, ?) ON CONFLICT(artist_id) DO UPDATE SET last_seen = excluded.last_seen",
            (artist_id, today.isoformat()),
        )
        self.db.commit()


def load_manual_leads(path):
    """Artists seen running paid campaigns (Sound.me, Whop, SongPush...) entered by hand.

    CSV columns: artist_name, source, email, instagram, tiktok, homepage, notes
    """
    leads = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            name = (r.get("artist_name") or "").strip()
            if not name:
                continue
            leads.append(Lead(
                artist_id=f"manual:{name.lower()}",
                artist_name=name,
                source=(r.get("source") or "manual").strip(),
                email=(r.get("email") or "").strip(),
                instagram=(r.get("instagram") or "").strip(),
                tiktok=(r.get("tiktok") or "").strip(),
                homepage=(r.get("homepage") or "").strip(),
                other_links=(r.get("notes") or "").strip(),
            ))
    return leads


def collect(get_json, today, days=3, get_text=None, seen=None, cooldown_days=60,
            max_artists=150, manual=(), log=print):
    """Build today's scored lead list.

    get_json/get_text are HTTP callables (injected for testing). get_text, when given,
    is used to look for a contact email on the artist's official homepage.
    """
    start = (today - timedelta(days=days)).isoformat()
    by_artist = {}
    for rel in musicbrainz.recent_releases(get_json, start, today.isoformat()):
        if musicbrainz.is_major(rel["label"]):
            continue
        # Keep the newest release per artist.
        prev = by_artist.get(rel["artist_id"])
        if prev is None or (rel["date"] or "") > (prev["date"] or ""):
            by_artist[rel["artist_id"]] = rel
    log(f"{len(by_artist)} non-major artists released between {start} and {today}")

    leads = []
    for rel in list(by_artist.values())[:max_artists]:
        if seen and not seen.is_fresh(rel["artist_id"], today, cooldown_days):
            continue
        lead = Lead(
            artist_id=rel["artist_id"],
            artist_name=rel["artist_name"],
            release_title=rel["title"] or "",
            release_date=rel["date"] or "",
            release_type=rel["type"] or "",
            label=rel["label"],
        )
        try:
            prof = musicbrainz.artist_profile(get_json, rel["artist_id"])
        except Exception as e:  # one broken artist must not kill the daily run
            log(f"skip profile {lead.artist_name}: {e}")
            continue
        if prof["type"] in ("Orchestra", "Choir"):
            continue
        links = prof["links"]
        lead.country = prof["country"]
        for k in ("instagram", "tiktok", "youtube", "spotify", "homepage"):
            setattr(lead, k, links.get(k, ""))
        lead.other_links = " ".join(v for k, v in links.items() if not hasattr(lead, k))
        try:
            lead.fans = deezer.fan_count(get_json, lead.artist_name)
        except Exception as e:
            log(f"no deezer data for {lead.artist_name}: {e}")
        if lead.fans is not None and lead.fans > FANS_MAX:
            continue
        if get_text and lead.homepage:
            try:
                emails = extract_emails(get_text(lead.homepage))
                lead.email = emails[0] if emails else ""
            except Exception as e:
                log(f"homepage unreachable for {lead.artist_name}: {e}")
        # Nobody to talk to -> not a lead.
        if not (lead.email or lead.instagram or lead.tiktok or lead.homepage):
            continue
        leads.append(lead)

    leads.extend(m for m in manual if not seen or seen.is_fresh(m.artist_id, today, cooldown_days))
    for lead in leads:
        score(lead, today)
        if seen:
            seen.mark(lead.artist_id, today)
    # Artists already paying for promotion elsewhere are proven buyers: always on top.
    leads.sort(key=lambda l: (l.source != "musicbrainz", l.score), reverse=True)
    return leads


def write_csv(leads, path):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        for lead in leads:
            w.writerow(lead.row())
