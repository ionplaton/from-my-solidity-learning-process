"""Pitch drafts for leads. Drafts only: a person reviews and sends them.

US cold email must follow CAN-SPAM: real sender identity, a postal address,
a working opt-out that is honoured, and no misleading subject lines.
"""

DEFAULT_PACKAGES = [
    {"name": "Test", "videos": 20, "days": 7, "price_usd": 149},
    {"name": "Release", "videos": 50, "days": 14, "price_usd": 349},
    {"name": "Push", "videos": 150, "days": 21, "price_usd": 899},
]

SUBJECT = "{videos} short videos using \"{title}\" on TikTok, Reels and Shorts"

BODY = """Hi {artist},

Congrats on "{title}"{date_part}. We run a network of US accounts on TikTok,
Instagram Reels and YouTube Shorts and post short videos that use your track
as the native sound, so every video counts toward your sound page.

Packages:
{packages}

You get a report with a link to every video. Posts stay up for at least 45 days.
Want us to start with the Test package for "{title}"?

{sender_name}
{company}
{reply_email}

{postal_address}
Not interested? Reply "no thanks" and we won't email you again.
"""


def format_packages(packages):
    return "\n".join(
        f"- {p['name']}: {p['videos']} videos over {p['days']} days - ${p['price_usd']}"
        for p in packages
    )


def draft(lead, sender, packages=DEFAULT_PACKAGES):
    title = lead.release_title or "your new release"
    entry = packages[0]
    subject = SUBJECT.format(videos=entry["videos"], title=title)
    body = BODY.format(
        artist=lead.artist_name,
        title=title,
        date_part=f" (out {lead.release_date})" if lead.release_date else "",
        packages=format_packages(packages),
        **sender,
    )
    return subject, body


def write_drafts(leads, path, sender, packages=DEFAULT_PACKAGES, limit=30):
    with open(path, "w", encoding="utf-8") as f:
        for lead in leads[:limit]:
            subject, body = draft(lead, sender, packages)
            contact = lead.email or lead.instagram or lead.tiktok or lead.homepage
            f.write(f"## {lead.artist_name} - score {lead.score}\n\n")
            f.write(f"Contact: {contact}\n\nWhy: {'; '.join(lead.reasons)}\n\n")
            f.write(f"Subject: {subject}\n\n```\n{body}```\n\n")
