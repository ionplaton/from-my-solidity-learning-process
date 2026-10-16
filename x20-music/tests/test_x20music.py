import json
import os
import tempfile
import unittest
from datetime import date

from x20music import leads, musicbrainz, outreach, planner

HERE = os.path.dirname(__file__)
CONFIG = os.path.join(HERE, "..", "config")

RELEASES = {
    "count": 4,
    "releases": [
        {"id": "r1", "title": "Night Drive", "date": "2026-10-15", "country": "XW",
         "release-group": {"primary-type": "Single"},
         "artist-credit": [{"name": "Indie Kid", "artist": {"id": "a1", "name": "Indie Kid"}}],
         "label-info": []},
        {"id": "r2", "title": "Big Hit", "date": "2026-10-14", "country": "US",
         "release-group": {"primary-type": "Single"},
         "artist-credit": [{"name": "Pop Star", "artist": {"id": "a2", "name": "Pop Star"}}],
         "label-info": [{"label": {"id": "l-major", "name": "Interscope Records"}}]},
        {"id": "r3", "title": "Older", "date": "2026-10-13", "country": "XW",
         "release-group": {"primary-type": "EP"},
         "artist-credit": [{"name": "Indie Kid", "artist": {"id": "a1", "name": "Indie Kid"}}],
         "label-info": []},
        {"id": "r4", "title": "Label Song", "date": "2026-10-12", "country": "US",
         "release-group": {"primary-type": "Single"},
         "artist-credit": [{"name": "Band X", "artist": {"id": "a3", "name": "Band X"}}],
         "label-info": [{"label": {"id": "l1", "name": "Tiny Records"}}]},
    ],
}

ARTIST = {
    "id": "a1", "country": "US", "type": "Person",
    "relations": [
        {"type": "social network", "url": {"resource": "https://www.instagram.com/indiekid"}},
        {"type": "social network", "url": {"resource": "https://www.tiktok.com/@indiekid"}},
        {"type": "official homepage", "url": {"resource": "https://indiekid.com"}},
        {"type": "bandcamp", "url": {"resource": "https://indiekid.bandcamp.com"}},
    ],
}

DEEZER = {"data": [{"name": "Indie Kid", "nb_fan": 4200}]}

LABEL = {"id": "l1", "country": "US", "type": "Original Production",
         "relations": [{"type": "official homepage", "url": {"resource": "https://tinyrecords.com"}}]}

# Band X has no links at all -> dropped as unreachable.
BAND_X = {"id": "a3", "country": "GB", "type": "Group", "relations": []}


def fake_get_json(url, params=None):
    if url.endswith("/release"):
        return RELEASES
    if url.endswith("/artist/a1"):
        return ARTIST
    if url.endswith("/artist/a3"):
        return BAND_X
    if url.endswith("/label/l1"):
        return LABEL
    if "deezer" in url:
        return DEEZER
    raise AssertionError(url)


PAGES = {
    "https://indiekid.com": '<a href="mailto:booking@indiekid.com">Booking</a> <img src="logo@2x.png">',
    # Label homepage has no email, but links to a contact page that does (HTML-escaped).
    "https://tinyrecords.com": '<a href="/about">About</a> <a href="https://other.com/contact">x</a>',
    "https://tinyrecords.com/about": "demos: demos&#64;tinyrecords.com, info@tinyrecords.com",
}


def fake_get_text(url):
    if url not in PAGES:
        raise OSError(url)
    return PAGES[url]


class LeadTests(unittest.TestCase):
    def test_collect_filters_majors_dedupes_and_scores(self):
        found = leads.collect(fake_get_json, date(2026, 10, 16), get_text=fake_get_text, log=lambda m: None)
        self.assertEqual([l.artist_name for l in found], ["Indie Kid", "Tiny Records"])
        lead = found[0]
        self.assertEqual(lead.release_title, "Night Drive")  # newest release wins
        self.assertEqual(lead.email, "booking@indiekid.com")
        self.assertEqual(lead.fans, 4200)
        self.assertIn("bandcamp", lead.other_links)
        # 3 fans + 3 email + 2 social + 1 homepage + 2 US + 2 self-released + 1 recent
        self.assertEqual(lead.score, 14)

    def test_seen_store_cooldown(self):
        with tempfile.TemporaryDirectory() as d:
            seen = leads.SeenStore(os.path.join(d, "s.sqlite"))
            first = leads.collect(fake_get_json, date(2026, 10, 16), seen=seen, log=lambda m: None)
            again = leads.collect(fake_get_json, date(2026, 10, 17), seen=seen, log=lambda m: None)
            self.assertEqual(len(first), 2)
            self.assertEqual(again, [])

    def test_manual_leads_rank_first(self):
        manual = leads.load_manual_leads(os.path.join(CONFIG, "manual_leads.example.csv"))
        found = leads.collect(fake_get_json, date(2026, 10, 16), manual=manual, log=lambda m: None)
        self.assertEqual(found[0].source, "sound.me")

    def test_query_and_url_classification(self):
        q = musicbrainz.build_release_query("2026-10-13", "2026-10-16")
        self.assertIn("date:[2026-10-13 TO 2026-10-16]", q)
        self.assertEqual(musicbrainz.classify_url("https://vm.tiktok.com/x"), "tiktok")
        self.assertIsNone(musicbrainz.classify_url("https://notiktok.com/x"))
        self.assertTrue(musicbrainz.is_major("Columbia Records"))

    def test_draft_has_opt_out_and_address(self):
        lead = leads.Lead(artist_id="a1", artist_name="Indie Kid", release_title="Night Drive")
        sender = {"sender_name": "A", "company": "B", "reply_email": "c@d.e", "postal_address": "1 Main St"}
        subject, body = outreach.draft(lead, sender)
        self.assertIn("Night Drive", subject)
        self.assertIn("1 Main St", body)
        self.assertIn("won't email you again", body)


class PlannerTests(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(CONFIG, "accounts.example.json")) as f:
            self.accounts = json.load(f)
        with open(os.path.join(CONFIG, "orders.example.json")) as f:
            self.orders = json.load(f)

    def test_capacity_matches_five_phones(self):
        self.assertEqual(planner.capacity(self.accounts),
                         {"tiktok": 15, "instagram": 15, "youtube": 15, "facebook": 5})

    def test_plan_delivers_orders_within_rules(self):
        tasks = planner.plan(self.orders, self.accounts, date(2026, 10, 20), 14)
        self.assertEqual(planner.shortfall(self.orders), {})
        per_order = {}
        for t in tasks:
            per_order[t["order"]] = per_order.get(t["order"], 0) + 1
        self.assertEqual(per_order, {"ord-001": 50, "ord-002": 20})

        daily, phone_sound, uses = {}, {}, {}
        for t in tasks:
            daily[(t["date"], t["account"])] = daily.get((t["date"], t["account"]), 0) + 1
            k = (t["date"], t["phone"], t["order"])
            phone_sound[k] = phone_sound.get(k, 0) + 1
            uses[(t["account"], t["order"])] = uses.get((t["account"], t["order"]), 0) + 1
        self.assertLessEqual(max(daily.values()), 1)
        self.assertLessEqual(max(phone_sound.values()), 2)
        self.assertLessEqual(max(uses.values()), 3)
        # ord-002 only runs on TikTok and Instagram
        self.assertTrue(all(t["platform"] in ("tiktok", "instagram") for t in tasks if t["order"] == "ord-002"))
        # no posts outside the order windows
        self.assertTrue(all(t["date"] <= "2026-10-26" for t in tasks if t["order"] == "ord-002"))

    def test_shortfall_reported_when_overbooked(self):
        big = [{"id": "x", "artist": "A", "track": "T", "start": "2026-10-20", "days": 2,
                "videos": 500, "platforms": ["tiktok"], "sounds": {}}]
        planner.plan(big, self.accounts, date(2026, 10, 20), 2)
        # 15 TikTok accounts, 1 post/day, max 2 per phone per sound -> 10/day
        self.assertEqual(planner.shortfall(big), {"x": 480})


if __name__ == "__main__":
    unittest.main()
