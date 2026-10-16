"""Cold email sequence and DM text.

Plain text, no links and no images in the first email: that is what lands in the inbox
from a new mailbox. Each step has variants; a contact always gets the same variant
(chosen by a hash of the email) so follow-ups read as one conversation.
"""

import hashlib

FOOTER = """
--
{sender_name}, {company}
{postal_address}
If this isn't relevant, reply "unsubscribe" and you won't hear from us again."""

# One paragraph per line: mail clients wrap it themselves.
ARTIST = [
    [  # step 0
        ("{subj_title} on TikTok",
         """Hi {name},

Saw you just put out {release_phrase}. We run 50 US accounts across TikTok, Reels and Shorts and post short videos that use an artist's track as the native sound, so the sound page shows real videos made with it.

For a new release we'd do 20 videos in a week for $149, with a link to every post.

Want me to send over a couple of examples?"""),
        ("Videos with {subj_title}",
         """Hey {name},

Quick one: we seed new tracks on TikTok, Instagram Reels and YouTube Shorts from our own network of US accounts, and every video uses your song as the original sound.

Starter run is 20 videos over 7 days for $149. Should I put {title_ref} in next week's slots?"""),
    ],
    [  # step 1
        ("Re: {subject}",
         """Hi {name}, bumping this in case it got buried. We still have slots this week for {title_ref}. Happy to share examples of sound pages we've seeded."""),
    ],
    [  # step 2
        ("Re: {subject}",
         """Hi {name}, last note from me. If you'd like videos made with {title_ref} later on, just reply to this thread and we'll set it up."""),
    ],
]

LABEL = [
    [
        ("Sound seeding for {name} releases",
         """Hi {name} team,

{label_hook} We run 50 US accounts on TikTok, Reels and Shorts and post short videos that use a release as the native sound, so every video counts toward the sound page.

Labels use us per release: 50 videos over 14 days for $349, or 150 for $899, with a report linking every post. Open to a test run on one of your upcoming tracks?"""),
        ("TikTok/Reels videos for your roster",
         """Hi {name} team,

We seed sounds for indie labels: our own network of US accounts posts videos on TikTok, Instagram Reels and YouTube Shorts with your track as the original audio.

A first test is 20 videos in a week for $149. Which upcoming release could we try it on?"""),
    ],
    [
        ("Re: {subject}",
         """Hi, following up on this. We have room for one or two of your releases this month if you want to test it."""),
    ],
    [
        ("Re: {subject}",
         """Last note from me. If you'd like to seed a release later, reply here anytime."""),
    ],
]

DM = {
    "artist": ("Hey {name}! Congrats on {title_ref}. We post TikTok/Reels/Shorts videos using new tracks as "
               "the sound across 50 US accounts. 20 videos for $149. Want examples?"),
    "label": ("Hi {name}! We seed indie releases on TikTok/Reels/Shorts from 50 US accounts, "
              "each video uses your track as the sound. Open to a test on an upcoming release?"),
}


def variant(email, n):
    return int(hashlib.sha256(email.encode()).hexdigest(), 16) % n


def _ctx(contact):
    title = contact["release_title"] or ""
    short = title if len(title) <= 40 else title[:37] + "..."
    return {
        "name": contact["name"],
        "title_ref": f'"{title}"' if title else "your latest release",
        "release_phrase": f'"{title}"' if title else "new music",
        "subj_title": f'"{short}"' if title else "Your new release",
        "label_hook": f'Saw "{title}" among your recent releases.' if title
                      else "Came across your recent releases.",
        "subject": contact["subject"] or "",
    }


def render(contact, step, sender):
    """Return (subject, body) for this contact's next step."""
    seq = LABEL if contact["kind"] == "label" else ARTIST
    options = seq[step]
    subj, body = options[variant(contact["email"], len(options))]
    ctx = _ctx(contact)
    return subj.format(**ctx), body.format(**ctx) + "\n" + FOOTER.format(**sender)


def render_dm(contact):
    return DM.get(contact["kind"], DM["artist"]).format(**_ctx(contact))
