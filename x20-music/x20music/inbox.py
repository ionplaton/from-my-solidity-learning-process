"""Reads each mailbox over IMAP: replies stop the sequence, opt-outs and bounces are suppressed.

Every reply is surfaced for a human to answer: replies are where the sales happen.
"""

import email
import imaplib
import re
from email.policy import default as default_policy
from email.utils import parseaddr

UNSUB_RE = re.compile(
    r"\b(unsubscribe|remove me|stop emailing|not interested|no thanks|take me off|opt[ -]?out)\b", re.I
)
BOUNCE_FROM = ("mailer-daemon", "postmaster")
BOUNCE_SUBJECT = re.compile(r"(undeliver|delivery status|delivery fail|returned mail|failure notice)", re.I)
ADDR_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
QUOTE_RE = re.compile(r"^(>|On .+wrote:)", re.M)


def default_imap(mb):
    conn = imaplib.IMAP4_SSL(mb.imap_host, mb.imap_port)
    conn.login(mb.username or mb.address, mb.password)
    return conn


def _text(msg):
    part = msg.get_body(preferencelist=("plain", "html")) if msg.is_multipart() else msg
    try:
        return part.get_content() if part else ""
    except Exception:
        return ""


def _fresh_text(body):
    """The reply itself, without the quoted original (which contains our own opt-out line)."""
    m = QUOTE_RE.search(body)
    return body[: m.start()] if m else body


def classify(msg, crm):
    """Return (kind, email) where kind is reply | unsubscribe | bounce | None."""
    sender = parseaddr(msg.get("From", ""))[1].lower()
    subject = msg.get("Subject", "")
    body = _text(msg)
    if sender.split("@")[0] in BOUNCE_FROM or BOUNCE_SUBJECT.search(subject):
        for addr in ADDR_RE.findall(body):
            if crm.get(addr):
                return "bounce", addr.lower()
        return None, None
    if not crm.get(sender):
        return None, None
    if UNSUB_RE.search(subject) or UNSUB_RE.search(_fresh_text(body)):
        return "unsubscribe", sender
    return "reply", sender


def process_mailbox(crm, mb, imap_factory=default_imap, log=print):
    """Handle messages that arrived since the last run. Returns a list of reply summaries."""
    conn = imap_factory(mb)
    replies = []
    try:
        conn.select("INBOX")
        last = int(crm.get_state(f"imap_uid:{mb.name}", "0"))
        _, data = conn.uid("search", None, f"UID {last + 1}:*")
        uids = [int(u) for u in (data[0] or b"").split() if int(u) > last]
        for uid in uids:
            _, parts = conn.uid("fetch", str(uid), "(RFC822)")
            raw = next((p[1] for p in parts if isinstance(p, tuple)), None)
            if raw is None:
                continue
            msg = email.message_from_bytes(raw, policy=default_policy)
            kind, addr = classify(msg, crm)
            if kind == "bounce":
                crm.suppress(addr, "bounce")
            elif kind == "unsubscribe":
                crm.suppress(addr, "unsubscribe")
            elif kind == "reply":
                crm.set_status(addr, "replied")
                contact = crm.get(addr)
                replies.append({
                    "mailbox": mb.name, "email": addr, "name": contact["name"],
                    "subject": msg.get("Subject", ""), "text": _fresh_text(_text(msg)).strip()[:500],
                })
            if kind:
                log(f"{mb.name}: {kind} {addr}")
            crm.set_state(f"imap_uid:{mb.name}", uid)
    finally:
        try:
            conn.logout()
        except Exception:
            pass
    return replies
