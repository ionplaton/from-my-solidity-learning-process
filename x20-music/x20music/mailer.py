"""Sends due sequence emails through several SMTP mailboxes with warm-up caps.

Deliverability rules built in:
- each mailbox has a daily cap that ramps up from `ramp_start` by `ramp_step` a day
  to `daily_max` (new mailboxes that send hundreds on day one go straight to spam);
- follow-ups go from the same mailbox, threaded under the first email;
- random pause between emails;
- every email carries a List-Unsubscribe header and the opt-out footer;
- suppressed (unsubscribed/bounced) addresses are never emailed.
"""

import os
import random
import smtplib
import time
from dataclasses import dataclass
from datetime import date
from email.message import EmailMessage
from email.utils import formataddr, formatdate, make_msgid

from . import templates


@dataclass
class Mailbox:
    name: str
    address: str
    display_name: str
    smtp_host: str
    smtp_port: int = 465
    imap_host: str = ""
    imap_port: int = 993
    username: str = ""
    password_env: str = ""
    start_date: str = ""     # first sending day; the ramp counts from here
    ramp_start: int = 10
    ramp_step: int = 3
    daily_max: int = 40

    @property
    def password(self):
        return os.environ.get(self.password_env, "")

    def cap(self, today):
        start = date.fromisoformat(self.start_date) if self.start_date else today
        days = (today - start).days
        if days < 0:
            return 0
        return min(self.daily_max, self.ramp_start + self.ramp_step * days)


def load_mailboxes(settings):
    return [Mailbox(**m) for m in settings.get("mailboxes", [])]


def default_smtp(mb):
    if mb.smtp_port == 465:
        conn = smtplib.SMTP_SSL(mb.smtp_host, mb.smtp_port, timeout=60)
    else:
        conn = smtplib.SMTP(mb.smtp_host, mb.smtp_port, timeout=60)
        conn.starttls()
    conn.login(mb.username or mb.address, mb.password)
    return conn


def build_message(contact, mailbox, sender, step):
    subject, body = templates.render(contact, step, sender)
    msg = EmailMessage()
    msg["From"] = formataddr((mailbox.display_name, mailbox.address))
    msg["To"] = contact["email"]
    msg["Subject"] = subject
    msg["Date"] = formatdate(localtime=True)
    msg["Message-ID"] = make_msgid(domain=mailbox.address.split("@")[1])
    msg["List-Unsubscribe"] = f"<mailto:{mailbox.address}?subject=unsubscribe>"
    if step > 0 and contact["thread_id"]:
        msg["In-Reply-To"] = contact["thread_id"]
        msg["References"] = contact["thread_id"]
    msg.set_content(body)
    return msg


def send_due(crm, mailboxes, today, sender, smtp_factory=default_smtp, sleep=time.sleep,
             delay=(25, 70), limit=None, dry_run_dir=None, log=print):
    """Send every due step that fits into today's mailbox caps. Returns number sent.

    dry_run_dir writes .eml files there instead of sending and leaves the CRM untouched.
    """
    remaining = {mb.name: max(0, mb.cap(today) - crm.sent_today(mb.name, today)) for mb in mailboxes}
    by_name = {mb.name: mb for mb in mailboxes}
    conns, sent = {}, 0
    budget = sum(remaining.values()) if limit is None else min(limit, sum(remaining.values()))
    log(f"capacity today: {remaining}")
    try:
        for contact in crm.due(today):
            if sent >= budget:
                break
            # Follow-ups stay in the original mailbox's thread.
            if contact["mailbox"] in by_name:
                name = contact["mailbox"]
                if remaining[name] <= 0:
                    continue
            else:
                name = max(remaining, key=remaining.get) if remaining else None
                if not name or remaining[name] <= 0:
                    continue
            mb = by_name[name]
            msg = build_message(contact, mb, sender, contact["step"])
            if dry_run_dir:
                os.makedirs(dry_run_dir, exist_ok=True)
                path = os.path.join(dry_run_dir, f"{today}-{contact['email']}-step{contact['step']}.eml")
                with open(path, "wb") as f:
                    f.write(bytes(msg))
            else:
                if name not in conns:
                    conns[name] = smtp_factory(mb)
                try:
                    conns[name].send_message(msg)
                except smtplib.SMTPRecipientsRefused:
                    crm.suppress(contact["email"], "bounce")
                    log(f"refused: {contact['email']}")
                    continue
            if not dry_run_dir:
                crm.record_send(contact, name, today, msg["Message-ID"], msg["Subject"])
            remaining[name] -= 1
            sent += 1
            log(f"step {contact['step'] + 1} -> {contact['email']} via {name}")
            if not dry_run_dir and sent < budget:
                sleep(random.uniform(*delay))
    finally:
        for c in conns.values():
            try:
                c.quit()
            except Exception:
                pass
    return sent
