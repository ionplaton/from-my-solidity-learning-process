"""Lead pipeline: fresh non-major releases -> enriched, scored artists and indie labels."""

import csv
import html
import re
import sqlite3
from dataclasses import asdict, dataclass, field
from datetime import date, timedelta
from urllib.parse import urljoin, urlparse

from . import deezer, musicbrainz

# Below this the artist has no audience and rarely a budget; above it they have a team.
FANS_SWEET_SPOT = (300, 50_000)
FANS_MAX = 250_000

EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
EMAIL_JUNK = ("example.", "sentry", "wixpress", ".png", ".jpg", ".webp", ".gif", "@2x",
              "domain.com", "noreply", "no-reply", "privacy", "abuse", "u003e")
EMAIL_PREFERRED = ("booking", "management", "mgmt", "contact", "info", "hello", "promo", "press")
LINK_RE = re.compile(r"""href=["']([^"'#]+)["']""", re.I)
CONTACT_HINTS = ("contact", "booking", "about", "management", "press", "info", "epk")


@dataclass
class Lead:
    artist_id: str
    artist_name: str
    kind: str = "artist"  # artist | label
    release_title: str = ""
    release_date: str = ""
    release_type: str = ""
    label: str = ""
    releases_in_window: int = 1
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
        why.append("US")
    if lead.kind == "label":
        pts += min(lead.releases_in_window, 5)
        why.append(f"indie label, {lead.releases_in_window} releases in window")
    elif not lead.label or lead.label.lower() in ("[no label]", lead.artist_name.lower()):
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


def extract_emails(page):
    found = []
    for m in EMAIL_RE.findall(html.unescape(page)):
        e = m.lower().rstrip(".")
        if not any(j in e for j in EMAIL_JUNK) and e not in found:
            found.append(e)
    return found


def best_email(emails):
    for hint in EMAIL_PREFERRED:
        for e in emails:
            if e.split("@")[0].startswith(hint):
                return e
    return emails[0] if emails else ""


def contact_links(page, base_url):
    """Same-site links that look like a contact/booking/about page."""
    host = urlparse(base_url).hostname
    out = []
    for href in LINK_RE.findall(page):
        url = urljoin(base_url, href)
        if urlparse(url).hostname != host or url in out:
            continue
        if any(h in url.lower() for h in CONTACT_HINTS):
            out.append(url)
    return out


def find_email(get_text, urls, max_subpages=2):
    """Look for a public contact email on the given pages and their contact subpages."""
    for url in urls:
        if not url:
            continue
        try:
            page = get_text(url)
        except Exception:
            continue
        emails = extract_emails(page)
        if emails:
            return best_email(emails)
        for sub in contact_links(page, url)[:max_subpages]:
            try:
                emails = extract_emails(get_text(sub))
            except Exception:
                continue
            if emails:
                return best_email(emails)
    return ""


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
    """Contacts entered by hand or imported from a bought/collected list.

    CSV columns: artist_name, source, email, instagram, tiktok, homepage, notes [, kind]
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
                kind=(r.get("kind") or "artist").strip(),
                source=(r.get("source") or "manual").strip(),
                email=(r.get("email") or "").strip().lower(),
                instagram=(r.get("instagram") or "").strip(),
                tiktok=(r.get("tiktok") or "").strip(),
                homepage=(r.get("homepage") or "").strip(),
                other_links=(r.get("notes") or "").strip(),
            ))
    return leads


def _apply_links(lead, links):
    for k in ("instagram", "tiktok", "youtube", "spotify", "homepage"):
        setattr(lead, k, links.get(k, ""))
    lead.other_links = " ".join(v for k, v in links.items() if not hasattr(lead, k))


def _email_pages(lead, links):
    return [links.get("homepage"), links.get("linktree"), links.get("bandcamp")]


def collect(get_json, today, days=3, get_text=None, seen=None, cooldown_days=60,
            max_artists=150, max_labels=50, max_pages=10, manual=(), skip=None,
            deezer_get_json=None, types=("single", "ep"), log=print):
    """Build a scored lead list of artists and indie labels with releases in the window.

    get_json is the (rate-limited) MusicBrainz client; deezer_get_json and get_text may be
    faster clients. get_text, when given, is used to find a public contact email on the
    artist's/label's homepage, link page or Bandcamp.
    skip(ref) -> True drops a lead before enrichment (e.g. already in the CRM).
    """
    deezer_get_json = deezer_get_json or get_json
    start = (today - timedelta(days=days)).isoformat()
    by_artist, by_label, label_count = {}, {}, {}
    for rel in musicbrainz.recent_releases(get_json, start, today.isoformat(), types=types,
                                           max_pages=max_pages):
        if musicbrainz.is_major(rel["label"]):
            continue
        prev = by_artist.get(rel["artist_id"])
        if prev is None or (rel["date"] or "") > (prev["date"] or ""):
            by_artist[rel["artist_id"]] = rel
        lid, lname = rel["label_id"], rel["label"]
        if lid and lname.lower() not in ("[no label]", (rel["artist_name"] or "").lower()):
            label_count[lid] = label_count.get(lid, 0) + 1
            prev = by_label.get(lid)
            if prev is None or (rel["date"] or "") > (prev["date"] or ""):
                by_label[lid] = rel
    log(f"{len(by_artist)} non-major artists, {len(by_label)} indie labels between {start} and {today}")

    def fresh(ref):
        if skip and skip(ref):
            return False
        return not seen or seen.is_fresh(ref, today, cooldown_days)

    leads = []
    for rel in [r for r in by_artist.values() if fresh(r["artist_id"])][:max_artists]:
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
        except Exception as e:  # one broken artist must not kill the run
            log(f"skip profile {lead.artist_name}: {e}")
            continue
        if prof["type"] in ("Orchestra", "Choir"):
            continue
        lead.country = prof["country"]
        _apply_links(lead, prof["links"])
        try:
            lead.fans = deezer.fan_count(deezer_get_json, lead.artist_name)
        except Exception as e:
            log(f"no deezer data for {lead.artist_name}: {e}")
        if lead.fans is not None and lead.fans > FANS_MAX:
            continue
        if get_text:
            lead.email = find_email(get_text, _email_pages(lead, prof["links"]))
        # Nobody to talk to -> not a lead.
        if not (lead.email or lead.instagram or lead.tiktok or lead.homepage):
            continue
        leads.append(lead)

    labels = sorted(by_label.items(), key=lambda kv: label_count[kv[0]], reverse=True)
    for lid, rel in [kv for kv in labels if fresh(f"label:{kv[0]}")][:max_labels]:
        lead = Lead(
            artist_id=f"label:{lid}",
            artist_name=rel["label"],
            kind="label",
            release_title=rel["title"] or "",
            release_date=rel["date"] or "",
            release_type=rel["type"] or "",
            label=rel["label"],
            releases_in_window=label_count[lid],
        )
        try:
            prof = musicbrainz.label_profile(get_json, lid)
        except Exception as e:
            log(f"skip label {lead.artist_name}: {e}")
            continue
        lead.country = prof["country"]
        _apply_links(lead, prof["links"])
        if get_text:
            lead.email = find_email(get_text, _email_pages(lead, prof["links"]))
        if not (lead.email or lead.instagram or lead.tiktok or lead.homepage):
            continue
        leads.append(lead)

    leads.extend(m for m in manual if fresh(m.artist_id))
    for lead in leads:
        score(lead, today)
        if seen:
            seen.mark(lead.artist_id, today)
    # Contacts already paying for promotion elsewhere are proven buyers: always on top.
    leads.sort(key=lambda l: (l.source != "musicbrainz", l.score), reverse=True)
    return leads


def write_csv(leads, path):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        for lead in leads:
            w.writerow(lead.row())
