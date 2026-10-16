"""Command line.

  leads    one-off CSV of fresh leads (no CRM)
  collect  find leads and add them to the CRM
  import   add contacts from CSV files (hand-collected or bought lists) to the CRM
  send     send due sequence emails within mailbox caps
  inbox    process replies, opt-outs and bounces
  dmqueue  export today's DM batch for manual sending
  stats    progress towards the outreach goal
  daily    collect + inbox + send + dmqueue + stats (run from cron once a day)
  plan     posting plan for paid orders
"""

import argparse
import csv
import json
import os
import sys
from datetime import date

from . import inbox as inbox_mod
from . import leads as leads_mod
from . import mailer, outreach, planner, templates
from .crm import CRM
from .http import HttpClient

GOAL = 5000


def log(msg):
    print(msg, file=sys.stderr)


def _today(args):
    return date.fromisoformat(args.date) if getattr(args, "date", None) else date.today()


def _settings(args):
    return planner.load_json(args.settings) if args.settings else {}


def _clients(args):
    contact = os.environ.get("MB_CONTACT") or args.contact
    if not contact:
        sys.exit("Set MB_CONTACT (an email or URL) - MusicBrainz requires contact info in the User-Agent.")
    ua = f"x20-music-leads/0.2 ( {contact} )"
    # MusicBrainz allows 1 request/second; Deezer and artist sites can go faster.
    return HttpClient(ua), HttpClient(ua, min_interval=0.3, timeout=15, retries=1)


def cmd_leads(args):
    mb, fast = _clients(args)
    today = _today(args)
    os.makedirs(args.out, exist_ok=True)
    seen = leads_mod.SeenStore(args.state) if args.state else None
    manual = leads_mod.load_manual_leads(args.manual) if args.manual and os.path.exists(args.manual) else []
    found = leads_mod.collect(
        mb.get_json, today, days=args.days,
        get_text=fast.get_text if args.fetch_emails else None, deezer_get_json=fast.get_json,
        seen=seen, max_artists=args.max_artists, max_labels=args.max_labels,
        max_pages=args.max_pages, manual=manual, types=tuple(args.types.split(",")), log=log,
    )
    csv_path = os.path.join(args.out, f"leads-{today}.csv")
    leads_mod.write_csv(found, csv_path)
    settings = _settings(args)
    drafts_path = os.path.join(args.out, f"drafts-{today}.md")
    outreach.write_drafts(found, drafts_path, settings.get("sender", {
        "sender_name": "<your name>", "company": "<company>",
        "reply_email": "<reply email>", "postal_address": "<postal address>",
    }), settings.get("packages", outreach.DEFAULT_PACKAGES))
    print(f"{len(found)} leads -> {csv_path}\ndrafts -> {drafts_path}")


def cmd_collect(args):
    crm = CRM(args.db)
    mb, fast = _clients(args)
    today = _today(args)
    manual = []
    for path in args.manual or []:
        if os.path.exists(path):
            manual += leads_mod.load_manual_leads(path)
    found = leads_mod.collect(
        mb.get_json, today, days=args.days, get_text=fast.get_text, deezer_get_json=fast.get_json,
        max_artists=args.max_artists, max_labels=args.max_labels, max_pages=args.max_pages,
        manual=manual, skip=crm.has, types=tuple(args.types.split(",")), log=log,
    )
    added = crm.add_leads(found, today)
    print(f"collect: {len(found)} leads found, {added} new in CRM "
          f"({sum(1 for l in found if l.email)} with email)")


def cmd_import(args):
    crm = CRM(args.db)
    today = _today(args)
    found = []
    for path in args.files:
        found += leads_mod.load_manual_leads(path)
    for lead in found:
        leads_mod.score(lead, today)
    added = crm.add_leads(found, today)
    print(f"import: {len(found)} rows, {added} new in CRM")


def _require_sender(settings):
    sender = settings.get("sender") or {}
    missing = [k for k in ("sender_name", "company", "postal_address") if not sender.get(k) or "<" in sender[k]]
    if missing:
        sys.exit(f"settings.sender is missing {missing}: CAN-SPAM requires a real sender and postal address.")
    return sender


def cmd_send(args):
    settings = _settings(args)
    sender = _require_sender(settings)
    crm = CRM(args.db)
    boxes = mailer.load_mailboxes(settings)
    if not boxes:
        sys.exit("No mailboxes in settings.")
    sent = mailer.send_due(crm, boxes, _today(args), sender, limit=args.limit,
                           dry_run_dir=args.dry_run, log=log,
                           delay=tuple(settings.get("send_delay_seconds", (25, 70))))
    print(f"send: {sent} emails {'written to ' + args.dry_run if args.dry_run else 'sent'}")


def cmd_inbox(args):
    settings = _settings(args)
    crm = CRM(args.db)
    replies = []
    for mb in mailer.load_mailboxes(settings):
        if not mb.imap_host:
            continue
        try:
            replies += inbox_mod.process_mailbox(crm, mb, log=log)
        except Exception as e:
            log(f"inbox {mb.name} failed: {e}")
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"replies-{_today(args)}.md")
    with open(path, "a", encoding="utf-8") as f:
        for r in replies:
            f.write(f"## {r['name']} <{r['email']}> via {r['mailbox']}\n\n{r['subject']}\n\n{r['text']}\n\n")
    print(f"inbox: {len(replies)} new replies" + (f" -> {path}" if replies else ""))


def cmd_dmqueue(args):
    crm = CRM(args.db)
    today = _today(args)
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"dm-{today}.csv")
    rows = crm.dm_candidates(args.limit)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["name", "kind", "instagram", "tiktok", "has_email", "message"])
        for c in rows:
            w.writerow([c["name"], c["kind"], c["instagram"], c["tiktok"],
                        "yes" if c["email"] else "no", templates.render_dm(c)])
            crm.mark_dm(c["ref"], today)
    print(f"dmqueue: {len(rows)} DMs -> {path}")


def cmd_stats(args):
    s = CRM(args.db).stats()
    print(json.dumps(s, indent=1))
    print(f"goal: {s['reached']}/{GOAL} reached ({100 * s['reached'] // GOAL}%)")


def cmd_daily(args):
    settings = _settings(args)
    if not args.skip_collect:
        cmd_collect(args)
    if any(mb.imap_host for mb in mailer.load_mailboxes(settings)):
        cmd_inbox(args)          # first, so nobody who replied gets a follow-up
    cmd_send(args)
    args.limit = settings.get("dm_per_day", 40)
    cmd_dmqueue(args)
    cmd_stats(args)


def cmd_plan(args):
    accounts = planner.load_json(args.accounts)
    orders = planner.load_json(args.orders)
    start = date.fromisoformat(args.start) if args.start else date.today()
    tasks = planner.plan(
        orders, accounts, start, args.days,
        posts_per_account=args.posts_per_account,
        per_phone_per_sound=args.per_phone_per_sound,
        max_per_order=args.max_per_order,
    )
    planner.write_csv(tasks, args.out)
    cap = planner.capacity(accounts, args.posts_per_account)
    print(f"capacity per day: {cap} (total {sum(cap.values())})")
    print(f"{len(tasks)} posting tasks -> {args.out}")
    for order_id, missing in planner.shortfall(orders).items():
        print(f"WARNING: order {order_id} is {missing} videos short within its window")


def _source_args(p):
    p.add_argument("--days", type=int, default=3, help="look back this many days")
    p.add_argument("--contact", help="contact for User-Agent (or env MB_CONTACT)")
    p.add_argument("--max-artists", type=int, default=300)
    p.add_argument("--max-labels", type=int, default=100)
    p.add_argument("--max-pages", type=int, default=10, help="MusicBrainz pages of 100 releases")
    p.add_argument("--types", default="single,ep", help="release types, e.g. single,ep,album")


def main(argv=None):
    p = argparse.ArgumentParser(prog="x20music")
    sub = p.add_subparsers(dest="cmd", required=True)

    def common(sp):
        sp.add_argument("--date", help="run as of YYYY-MM-DD (default today)")
        sp.add_argument("--db", default="state/crm.sqlite")
        sp.add_argument("--settings", default="config/settings.json")
        sp.add_argument("--out", default="out")

    pl = sub.add_parser("leads")
    common(pl)
    _source_args(pl)
    pl.add_argument("--state", help="sqlite file remembering already-surfaced artists")
    pl.add_argument("--manual", help="CSV of hand-collected leads")
    pl.add_argument("--fetch-emails", action="store_true")
    pl.set_defaults(func=cmd_leads, settings=None)

    pc = sub.add_parser("collect")
    common(pc)
    _source_args(pc)
    pc.add_argument("--manual", nargs="*", default=["config/manual_leads.csv"])
    pc.set_defaults(func=cmd_collect)

    pm = sub.add_parser("import")
    common(pm)
    pm.add_argument("files", nargs="+", help="CSV: artist_name,source,email,instagram,tiktok,homepage,notes[,kind]")
    pm.set_defaults(func=cmd_import)

    ps = sub.add_parser("send")
    common(ps)
    ps.add_argument("--limit", type=int)
    ps.add_argument("--dry-run", metavar="DIR", help="write .eml files instead of sending")
    ps.set_defaults(func=cmd_send)

    pi = sub.add_parser("inbox")
    common(pi)
    pi.set_defaults(func=cmd_inbox)

    pq = sub.add_parser("dmqueue")
    common(pq)
    pq.add_argument("--limit", type=int, default=40)
    pq.set_defaults(func=cmd_dmqueue)

    pt = sub.add_parser("stats")
    common(pt)
    pt.set_defaults(func=cmd_stats)

    pd = sub.add_parser("daily")
    common(pd)
    _source_args(pd)
    pd.add_argument("--manual", nargs="*", default=["config/manual_leads.csv"])
    pd.add_argument("--skip-collect", action="store_true")
    pd.add_argument("--limit", type=int)
    pd.add_argument("--dry-run", metavar="DIR")
    pd.set_defaults(func=cmd_daily)

    pp = sub.add_parser("plan")
    pp.add_argument("--accounts", required=True)
    pp.add_argument("--orders", required=True)
    pp.add_argument("--start", help="YYYY-MM-DD (default today)")
    pp.add_argument("--days", type=int, default=7)
    pp.add_argument("--out", default="plan.csv")
    pp.add_argument("--posts-per-account", type=int, default=1)
    pp.add_argument("--per-phone-per-sound", type=int, default=2)
    pp.add_argument("--max-per-order", type=int, default=3)
    pp.set_defaults(func=cmd_plan)

    args = p.parse_args(argv)
    if getattr(args, "db", None):
        os.makedirs(os.path.dirname(args.db) or ".", exist_ok=True)
    args.func(args)
