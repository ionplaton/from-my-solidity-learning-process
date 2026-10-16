import os
import tempfile
import unittest
from datetime import date
from email.message import EmailMessage

from x20music import inbox, leads, mailer, templates
from x20music.crm import CRM

from test_x20music import fake_get_json, fake_get_text

TODAY = date(2026, 11, 10)
SENDER = {"sender_name": "Alex", "company": "X20 Music", "reply_email": "a@x.example",
          "postal_address": "1 Main St, Austin, TX"}


def box(name, **kw):
    params = dict(name=name, address=f"{name}@out.example", display_name="Alex",
                  smtp_host="smtp.example", start_date="2026-11-03", ramp_start=10,
                  ramp_step=3, daily_max=40)
    params.update(kw)
    return mailer.Mailbox(**params)


class FakeSMTP:
    def __init__(self):
        self.sent = []

    def send_message(self, msg):
        self.sent.append(msg)

    def quit(self):
        pass


def lead(ref, email, kind="artist", score=5, instagram=""):
    return leads.Lead(artist_id=ref, artist_name=ref.title(), kind=kind, email=email,
                      release_title="Night Drive", score=score, instagram=instagram)


class OutreachTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.crm = CRM(os.path.join(self.tmp.name, "crm.sqlite"))

    def tearDown(self):
        self.crm.db.close()
        self.tmp.cleanup()

    def send(self, today, boxes, smtp):
        return mailer.send_due(self.crm, boxes, today, SENDER, smtp_factory=lambda mb: smtp,
                               sleep=lambda s: None, log=lambda m: None)

    def test_label_lead_found_via_contact_page(self):
        found = leads.collect(fake_get_json, date(2026, 10, 16), get_text=fake_get_text, log=lambda m: None)
        label = next(l for l in found if l.kind == "label")
        self.assertEqual(label.email, "info@tinyrecords.com")

    def test_dedupe_by_ref_and_email(self):
        added = self.crm.add_leads([lead("a", "x@a.com"), lead("a", "y@a.com"), lead("b", "X@a.com")], TODAY)
        self.assertEqual(added, 1)

    def test_warmup_cap(self):
        mb = box("mb1")
        self.assertEqual(mb.cap(date(2026, 11, 2)), 0)
        self.assertEqual(mb.cap(date(2026, 11, 3)), 10)
        self.assertEqual(mb.cap(TODAY), 31)
        self.assertEqual(mb.cap(date(2026, 12, 1)), 40)

    def test_sequence_caps_threading_and_compliance(self):
        self.crm.add_leads([lead(f"r{i}", f"p{i}@a.com", score=i) for i in range(50)], TODAY)
        smtp = FakeSMTP()
        boxes = [box("mb1", start_date="2026-11-10"), box("mb2", start_date="2026-11-10")]
        # day 1: both mailboxes at ramp_start=10
        self.assertEqual(self.send(TODAY, boxes, smtp), 20)
        self.assertEqual(self.send(TODAY, boxes, smtp), 0)  # caps respected on re-run
        first = smtp.sent[0]
        self.assertEqual(first["To"], "p49@a.com")  # best score first
        self.assertIn("unsubscribe", first["List-Unsubscribe"])
        self.assertIn("1 Main St", first.get_content())

        # 3 days later: follow-ups for the first 20 go first, from the same mailbox, threaded
        smtp.sent.clear()
        later = date(2026, 11, 13)
        self.send(later, boxes, smtp)
        follow = [m for m in smtp.sent if m["In-Reply-To"]]
        self.assertEqual(len(follow), 20)
        c = self.crm.get(follow[0]["To"])
        self.assertEqual(c["step"], 2)
        self.assertTrue(follow[0]["Subject"].startswith("Re: "))
        self.assertEqual(follow[0]["From"].split("<")[1][:3], c["mailbox"])

    def test_sequence_ends_after_three_emails(self):
        self.crm.add_leads([lead("a", "p@a.com")], TODAY)
        smtp, boxes = FakeSMTP(), [box("mb1")]
        for d in (10, 13, 17, 30):
            self.send(date(2026, 11, d), boxes, smtp)
        self.assertEqual(len(smtp.sent), 3)
        self.assertEqual(self.crm.get("p@a.com")["status"], "done")

    def test_suppressed_never_emailed(self):
        self.crm.suppress("p@a.com", "unsubscribe")
        self.crm.add_leads([lead("a", "p@a.com")], TODAY)
        self.assertEqual(self.send(TODAY, [box("mb1")], FakeSMTP()), 0)

    def test_dry_run_leaves_crm_untouched(self):
        self.crm.add_leads([lead("a", "p@a.com")], TODAY)
        out = os.path.join(self.tmp.name, "eml")
        n = mailer.send_due(self.crm, [box("mb1")], TODAY, SENDER, dry_run_dir=out, log=lambda m: None)
        self.assertEqual(n, 1)
        self.assertEqual(len(os.listdir(out)), 1)
        self.assertEqual(self.crm.get("p@a.com")["step"], 0)

    def _msg(self, frm, subject, body):
        m = EmailMessage()
        m["From"], m["Subject"] = frm, subject
        m.set_content(body)
        return m

    def test_inbox_classification(self):
        self.crm.add_leads([lead("a", "p@a.com"), lead("b", "q@b.com"), lead("c", "r@c.com")], TODAY)
        ours = '\n\nOn Mon, Alex wrote:\n> reply "unsubscribe" and you won\'t hear from us again'
        self.assertEqual(inbox.classify(self._msg("P <p@a.com>", "Re: hi", "Sounds good, price?" + ours), self.crm),
                         ("reply", "p@a.com"))
        self.assertEqual(inbox.classify(self._msg("q@b.com", "Re: hi", "please remove me"), self.crm),
                         ("unsubscribe", "q@b.com"))
        bounce = self._msg("MAILER-DAEMON@x.com", "Undelivered Mail", "could not deliver to r@c.com")
        self.assertEqual(inbox.classify(bounce, self.crm), ("bounce", "r@c.com"))
        self.assertEqual(inbox.classify(self._msg("z@z.com", "hi", "spam"), self.crm), (None, None))

    def test_inbox_processing_stops_sequence(self):
        self.crm.add_leads([lead("a", "p@a.com"), lead("b", "q@b.com")], TODAY)
        raw = {1: bytes(self._msg("p@a.com", "Re: x", "Interested!")),
               2: bytes(self._msg("q@b.com", "Re: x", "unsubscribe"))}

        class FakeIMAP:
            def select(self, box):
                pass

            def uid(self, cmd, *args):
                if cmd == "search":
                    return "OK", [b"1 2"]
                return "OK", [(b"1 (RFC822)", raw[int(args[0])]), b")"]

            def logout(self):
                pass

        replies = inbox.process_mailbox(self.crm, box("mb1"), imap_factory=lambda mb: FakeIMAP(),
                                        log=lambda m: None)
        self.assertEqual([r["email"] for r in replies], ["p@a.com"])
        self.assertEqual(self.crm.get("p@a.com")["status"], "replied")
        self.assertTrue(self.crm.is_suppressed("q@b.com"))
        self.assertEqual(self.crm.get_state("imap_uid:mb1"), "2")
        self.assertEqual(self.send(TODAY, [box("mb1")], FakeSMTP()), 0)

    def test_dm_queue_prefers_contacts_without_email(self):
        self.crm.add_leads([lead("a", "p@a.com", instagram="https://instagram.com/a"),
                            lead("b", "", instagram="https://instagram.com/b"),
                            lead("c", "", score=1)], TODAY)
        rows = self.crm.dm_candidates(10)
        self.assertEqual([r["ref"] for r in rows], ["b", "a"])
        self.assertIn("Night Drive", templates.render_dm(rows[0]))
        self.crm.mark_dm("b", TODAY)
        self.assertEqual(self.crm.stats()["reached"], 1)


if __name__ == "__main__":
    unittest.main()
