"""Minimal rate-limited JSON/HTML client on the standard library."""

import json
import time
import urllib.error
import urllib.parse
import urllib.request


class HttpClient:
    """GET with a fixed User-Agent, a minimum interval between requests and retries.

    MusicBrainz asks for at most 1 request/second and a User-Agent with contact info,
    so the default interval is 1.1s.
    """

    def __init__(self, user_agent, min_interval=1.1, timeout=30, retries=3):
        self.user_agent = user_agent
        self.min_interval = min_interval
        self.timeout = timeout
        self.retries = retries
        self._last = 0.0

    def _wait(self):
        delay = self.min_interval - (time.monotonic() - self._last)
        if delay > 0:
            time.sleep(delay)
        self._last = time.monotonic()

    def get_text(self, url, params=None):
        if params:
            url = f"{url}?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(url, headers={"User-Agent": self.user_agent})
        for attempt in range(self.retries):
            self._wait()
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    return resp.read().decode("utf-8", errors="replace")
            except urllib.error.HTTPError as e:
                # 503 = MusicBrainz rate limiting; back off and retry.
                if e.code in (429, 500, 502, 503, 504) and attempt + 1 < self.retries:
                    time.sleep(2 ** (attempt + 1))
                    continue
                raise
            except urllib.error.URLError:
                if attempt + 1 < self.retries:
                    time.sleep(2 ** (attempt + 1))
                    continue
                raise
        raise RuntimeError("unreachable")

    def get_json(self, url, params=None):
        return json.loads(self.get_text(url, params))
