"""Turn paid orders into a daily posting plan across phones and accounts.

Spreading rules keep a campaign from looking like one coordinated burst:
- an account posts at most `posts_per_account` music videos a day;
- an account uses a given sound at most `max_per_order` times per order;
- one phone posts the same sound at most `per_phone_per_sound` times a day;
- each order's videos are spread evenly over its days.
"""

import csv
import math
from collections import defaultdict
from datetime import date, timedelta


def load_json(path):
    import json
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def capacity(accounts, posts_per_account=1):
    by_platform = defaultdict(int)
    for a in accounts:
        if a.get("active", True):
            by_platform[a["platform"]] += posts_per_account
    return dict(by_platform)


def daily_quota(order, day):
    """Videos this order still needs on `day` so the remainder is spread over the rest of its days."""
    start = date.fromisoformat(order["start"])
    end = start + timedelta(days=order["days"] - 1)
    if not start <= day <= end:
        return 0
    remaining_days = (end - day).days + 1
    remaining = order["videos"] - order.get("_done", 0)
    return max(0, math.ceil(remaining / remaining_days))


def plan_day(orders, accounts, day, history=None, posts_per_account=1, per_phone_per_sound=2,
             max_per_order=3):
    """Return a list of posting tasks for `day`.

    history: {(account_id, order_id): uses} carried across days.
    Orders are mutated: `_done` counts videos planned so far.
    """
    history = history if history is not None else defaultdict(int)
    active = [a for a in accounts if a.get("active", True)]
    used = defaultdict(int)            # account_id -> posts today
    phone_sound = defaultdict(int)     # (phone, order_id) -> posts today
    tasks = []

    # Earliest deadline first, so short packages are never starved.
    for order in sorted(orders, key=lambda o: (date.fromisoformat(o["start"]) + timedelta(days=o["days"]))):
        need = daily_quota(order, day)
        platforms = order.get("platforms") or ["tiktok", "instagram", "youtube", "facebook"]
        # Round-robin over platforms, then over accounts with the fewest posts.
        pools = {
            p: [a for a in active
                if a["platform"] == p and history[(a["id"], order["id"])] < max_per_order]
            for p in platforms
        }
        progress = True
        while need > 0 and progress:
            progress = False
            for p in platforms:
                if need == 0:
                    break
                for a in sorted(pools[p], key=lambda a: used[a["id"]]):
                    if used[a["id"]] >= posts_per_account:
                        continue
                    if phone_sound[(a["phone"], order["id"])] >= per_phone_per_sound:
                        continue
                    sound = (order.get("sounds") or {}).get(p, "")
                    tasks.append({
                        "date": day.isoformat(),
                        "phone": a["phone"],
                        "account": a["id"],
                        "platform": p,
                        "handle": a.get("handle", ""),
                        "niche": a.get("niche", ""),
                        "order": order["id"],
                        "artist": order["artist"],
                        "track": order["track"],
                        "sound_url": sound,
                    })
                    used[a["id"]] += 1
                    phone_sound[(a["phone"], order["id"])] += 1
                    history[(a["id"], order["id"])] += 1
                    pools[p].remove(a)
                    order["_done"] = order.get("_done", 0) + 1
                    need -= 1
                    progress = True
                    break
    return tasks


def plan(orders, accounts, start, days, **kw):
    history, tasks = defaultdict(int), []
    for i in range(days):
        tasks.extend(plan_day(orders, accounts, start + timedelta(days=i), history, **kw))
    return tasks


def shortfall(orders):
    """Orders the current accounts cannot fully deliver."""
    return {o["id"]: o["videos"] - o.get("_done", 0) for o in orders if o.get("_done", 0) < o["videos"]}


TASK_FIELDS = ["date", "phone", "account", "platform", "handle", "niche",
               "order", "artist", "track", "sound_url"]


def write_csv(tasks, path):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=TASK_FIELDS)
        w.writeheader()
        w.writerows(tasks)
