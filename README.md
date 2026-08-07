# XMRPod

XMRPod is an open-source podcast app for Android with Monero tipping support for creators on XMRChat. No tracking, no analytics, no ads.

## Monero Tipping

XMRPod adds a `Tip` action to the playback screen for podcasts whose creators have an XMRChat page. When you send a tip, XMRPod creates a tip payment address with the listener name, amount, and optional message, then hands the payment URI to a compatible Monero wallet such as Cake Wallet.

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

On this FreeBSD workstation, use the local AAPT2 override:

```sh
JAVA_HOME=/usr/local/openjdk21 ./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=/home/wao/Android/Sdk/build-tools/35.0.0/aapt2
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
python3 scripts/update_changelog_release.py --version version --version-code code --date YYYY-MM-DD --write
```

## License

XMRPod is licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE).
