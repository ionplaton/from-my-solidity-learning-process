"""SQLite CRM: every contact, every email sent, opt-outs and the DM queue.

A contact is keyed by its source ref (MusicBrainz artist/label id or manual:<name>).
Emails are unique, so two artists sharing a manager's address get one sequence.
"""

import sqlite3
from datetime import date, datetime, timedelta, timezone

# Days to wait before each step of the sequence (step 0 = first email).
SEQUENCE_GAPS = (0, 3, 4)

SCHEMA = """
CREATE TABLE IF NOT EXISTS contacts (
    ref TEXT PRIMARY KEY,
    email TEXT UNIQUE,
    name TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'artist',
    source TEXT,
    release_title TEXT,
    country TEXT,
    instagram TEXT,
    tiktok TEXT,
    homepage TEXT,
    score INTEGER DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'new',   -- new | active | done | replied | unsubscribed | bounced
    step INTEGER NOT NULL DEFAULT 0,      -- emails sent so far
    next_due TEXT,
    mailbox TEXT,
    thread_id TEXT,                       -- Message-ID of the first email, for follow-up threading
    subject TEXT,
    dm_queued TEXT,
    added TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sends (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL,
    step INTEGER NOT NULL,
    mailbox TEXT NOT NULL,
    sent_on TEXT NOT NULL,
    message_id TEXT
);
CREATE INDEX IF NOT EXISTS sends_day ON sends (sent_on, mailbox);
CREATE TABLE IF NOT EXISTS suppression (
    email TEXT PRIMARY KEY,
    reason TEXT,
    added TEXT
);
CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT);
"""


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class CRM:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)

    # --- contacts -------------------------------------------------------

    def has(self, ref):
        return self.db.execute("SELECT 1 FROM contacts WHERE ref = ?", (ref,)).fetchone() is not None

    def add_leads(self, leads, today):
        """Insert new leads; returns how many were added. Duplicate refs/emails are skipped."""
        added = 0
        for l in leads:
            email = (l.email or "").lower() or None
            if email and self.is_suppressed(email):
                email = None
            try:
                self.db.execute(
                    """INSERT INTO contacts (ref, email, name, kind, source, release_title, country,
                       instagram, tiktok, homepage, score, next_due, added)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (l.artist_id, email, l.artist_name, l.kind, l.source, l.release_title,
                     l.country, l.instagram, l.tiktok, l.homepage, l.score,
                     today.isoformat() if email else None, today.isoformat()),
                )
                added += 1
            except sqlite3.IntegrityError:
                # Same ref or same email already known: keep the first one.
                continue
        self.db.commit()
        return added

    def get(self, email):
        return self.db.execute("SELECT * FROM contacts WHERE email = ?", (email.lower(),)).fetchone()

    def due(self, today, limit=None):
        """Contacts with an email whose next step is due. Follow-ups first, then best score."""
        q = """SELECT * FROM contacts
               WHERE email IS NOT NULL AND status IN ('new', 'active') AND next_due <= ?
               ORDER BY step DESC, score DESC, added"""
        rows = self.db.execute(q, (today.isoformat(),)).fetchall()
        rows = [r for r in rows if not self.is_suppressed(r["email"])]
        return rows[:limit] if limit else rows

    def record_send(self, contact, mailbox, today, message_id, subject):
        step = contact["step"] + 1
        if step >= len(SEQUENCE_GAPS):
            status, next_due = "done", None
        else:
            status, next_due = "active", (today + timedelta(days=SEQUENCE_GAPS[step])).isoformat()
        self.db.execute(
            """UPDATE contacts SET step = ?, status = ?, next_due = ?, mailbox = ?,
               thread_id = COALESCE(thread_id, ?), subject = COALESCE(subject, ?)
               WHERE email = ?""",
            (step, status, next_due, mailbox, message_id, subject, contact["email"]),
        )
        self.db.execute(
            "INSERT INTO sends (email, step, mailbox, sent_on, message_id) VALUES (?,?,?,?,?)",
            (contact["email"], contact["step"], mailbox, today.isoformat(), message_id),
        )
        self.db.commit()

    def sent_today(self, mailbox, today):
        return self.db.execute(
            "SELECT COUNT(*) FROM sends WHERE mailbox = ? AND sent_on = ?", (mailbox, today.isoformat())
        ).fetchone()[0]

    def set_status(self, email, status):
        self.db.execute("UPDATE contacts SET status = ?, next_due = NULL WHERE email = ?",
                        (status, email.lower()))
        self.db.commit()

    # --- opt-outs and bounces ------------------------------------------

    def suppress(self, email, reason):
        email = email.lower()
        self.db.execute("INSERT OR IGNORE INTO suppression VALUES (?,?,?)", (email, reason, now_iso()))
        status = "unsubscribed" if reason == "unsubscribe" else "bounced" if reason == "bounce" else None
        if status:
            self.set_status(email, status)
        self.db.commit()

    def is_suppressed(self, email):
        return self.db.execute(
            "SELECT 1 FROM suppression WHERE email = ?", (email.lower(),)
        ).fetchone() is not None

    # --- DM queue -------------------------------------------------------

    def dm_candidates(self, limit):
        """Contacts with a social handle that were never queued for a DM and haven't opted out.

        Contacts without email come first: DM is the only way to reach them.
        """
        return self.db.execute(
            """SELECT * FROM contacts
               WHERE dm_queued IS NULL AND (instagram <> '' OR tiktok <> '')
                 AND status NOT IN ('replied', 'unsubscribed')
               ORDER BY (email IS NOT NULL), score DESC LIMIT ?""",
            (limit,),
        ).fetchall()

    def mark_dm(self, ref, today):
        self.db.execute("UPDATE contacts SET dm_queued = ? WHERE ref = ?", (today.isoformat(), ref))
        self.db.commit()

    # --- misc -----------------------------------------------------------

    def get_state(self, key, default=None):
        row = self.db.execute("SELECT value FROM state WHERE key = ?", (key,)).fetchone()
        return row[0] if row else default

    def set_state(self, key, value):
        self.db.execute("INSERT OR REPLACE INTO state VALUES (?, ?)", (key, str(value)))
        self.db.commit()

    def stats(self):
        q = lambda sql: self.db.execute(sql).fetchone()[0]
        by_status = dict(self.db.execute("SELECT status, COUNT(*) FROM contacts GROUP BY status").fetchall())
        return {
            "contacts": q("SELECT COUNT(*) FROM contacts"),
            "with_email": q("SELECT COUNT(*) FROM contacts WHERE email IS NOT NULL"),
            "emailed": q("SELECT COUNT(*) FROM contacts WHERE step > 0"),
            "dm_queued": q("SELECT COUNT(*) FROM contacts WHERE dm_queued IS NOT NULL"),
            # Unique people reached by at least one channel: the 5000 goal counts these.
            "reached": q("SELECT COUNT(*) FROM contacts WHERE step > 0 OR dm_queued IS NOT NULL"),
            "emails_sent": q("SELECT COUNT(*) FROM sends"),
            "by_status": by_status,
        }
