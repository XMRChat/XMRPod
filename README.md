# XMRPod

XMRPod is an open-source podcast app for Android with Monero tipping support for creators on XMRChat. No tracking, no analytics, no ads.

## Monero Tipping

XMRPod adds a `Tip` action to the playback screen for podcasts whose creators have an XMRChat page. When you send a tip, XMRPod creates a tip payment address with the listener name, amount, and optional message, then hands the payment URI to a compatible Monero wallet such as Cake Wallet.

## Making Your Podcast Tippable

XMRPod detects tippable podcasts by matching URLs locally on the listener's device — no special tags are added to your feed. Register one or both of these links on your XMRChat page:

### 1. `podcast-rss` (recommended)

Add your feed URL, for example `https://example.com/feed.xml`. XMRPod compares it against the URL your feed is served from, so this is the most reliable match.

### 2. `website` (fallback)

Add your site URL, for example `https://example.com`. XMRPod compares it against the website link declared inside your feed:

RSS 2.0 — the channel-level `<link>` tag:

```xml
<channel>
  <link>https://example.com</link>
</channel>
```

Atom — a feed-level alternate HTML link:

```xml
<link rel="alternate" type="text/html" href="https://example.com"/>
```

If the `podcast-rss` link matches, it wins and the website check is skipped.

Matching ignores case, `www.` prefixes, query strings, `#fragments`, and trailing slashes — everything else must match exactly. A website URL claimed by more than one XMRChat page is ignored, so listeners never tip the wrong creator. The page list is cached and refreshed at most every 15 minutes, so new registrations can take a moment to appear.

## Privacy

Discovery happens entirely on your device. XMRPod downloads a single generic list of public XMRChat creator pages and matches your subscribed feeds against it locally. Your podcast titles, feed URLs, and listening activity are never transmitted.

The only network calls XMRPod makes:

- One generic download of the public creator page list (cached, refreshed at most daily).
- A public Monero price fetch when you open the tip form.
- The tip request — only when you explicitly send a tip. It carries just the tip details plus a `source=xmrpod` marker.

## Package Name

Release builds use:

```text
com.xmrchat.xmrpod
```

Debug builds use:

```text
com.xmrchat.xmrpod.debug
```

## Building

Build a debug APK with:

```sh
./gradlew :app:assembleDebug
```

Debug APKs are written under:

```text
app/build/outputs/apk/
```

## Releases

Release notes come from [CHANGELOG.md](CHANGELOG.md), not from generated commit summaries.

To publish a new XMRPod release, add a new topmost `## [version] - YYYY-MM-DD` section to `CHANGELOG.md`, run the release metadata step locally, commit the result, and push it to `develop`.
GitHub Actions will read the latest changelog version, create the `xmrpod-vversion` tag if it does not exist, build the Free release APK, and publish a GitHub release that links back to the full changelog.

You can run the release metadata step locally with:

```sh
python3 scripts/update_changelog_release.py
```

## License

XMRPod is licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE).
