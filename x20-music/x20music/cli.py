"""Command line: `python -m x20music leads ...` and `python -m x20music plan ...`."""

import argparse
import os
import sys
from datetime import date

from . import leads as leads_mod
from . import outreach, planner
from .http import HttpClient


def cmd_leads(args):
    contact = os.environ.get("MB_CONTACT") or args.contact
    if not contact:
        sys.exit("Set MB_CONTACT (an email or URL) - MusicBrainz requires contact info in the User-Agent.")
    http = HttpClient(f"x20-music-leads/0.1 ( {contact} )")
    today = date.fromisoformat(args.date) if args.date else date.today()
    os.makedirs(args.out, exist_ok=True)
    seen = leads_mod.SeenStore(args.state) if args.state else None
    manual = leads_mod.load_manual_leads(args.manual) if args.manual and os.path.exists(args.manual) else []

    found = leads_mod.collect(
        http.get_json, today, days=args.days,
        get_text=http.get_text if args.fetch_emails else None,
        seen=seen, max_artists=args.max_artists, manual=manual,
        log=lambda m: print(m, file=sys.stderr),
    )
    csv_path = os.path.join(args.out, f"leads-{today}.csv")
    leads_mod.write_csv(found, csv_path)

    settings = planner.load_json(args.settings) if args.settings else {}
    sender = settings.get("sender", {
        "sender_name": "<your name>", "company": "<company>",
        "reply_email": "<reply email>", "postal_address": "<postal address>",
    })
    packages = settings.get("packages", outreach.DEFAULT_PACKAGES)
    drafts_path = os.path.join(args.out, f"drafts-{today}.md")
    outreach.write_drafts(found, drafts_path, sender, packages)
    print(f"{len(found)} leads -> {csv_path}\ndrafts -> {drafts_path}")


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


def main(argv=None):
    p = argparse.ArgumentParser(prog="x20music")
    sub = p.add_subparsers(dest="cmd", required=True)

    pl = sub.add_parser("leads", help="find indie artists with fresh releases")
    pl.add_argument("--days", type=int, default=3, help="look back this many days")
    pl.add_argument("--date", help="run as of YYYY-MM-DD (default today)")
    pl.add_argument("--out", default="out")
    pl.add_argument("--state", help="sqlite file remembering already-surfaced artists")
    pl.add_argument("--manual", help="CSV of artists seen running paid campaigns")
    pl.add_argument("--settings", help="settings JSON with sender and packages")
    pl.add_argument("--contact", help="contact for User-Agent (or env MB_CONTACT)")
    pl.add_argument("--max-artists", type=int, default=150)
    pl.add_argument("--fetch-emails", action="store_true",
                    help="look for a contact email on each artist's official homepage")
    pl.set_defaults(func=cmd_leads)

    pp = sub.add_parser("plan", help="build a posting plan for paid orders")
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
    args.func(args)
