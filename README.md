# XMRPod

XMRPod is an open-source podcast app for Android with a Monero tipping proof of concept for podcasts that publish compatible XMRChat tip information.

## Monero Tipping

The current MVP adds a `Tip` action to the playback screen. When the active podcast exposes a supported Monero or XMRChat funding link, XMRPod opens a compatible wallet or browser so the listener can send a tip.

### XMRChat Discovery and Network Requests

To discover a matching XMRChat tip page, XMRPod automatically sends HTTPS
search requests to the public XMRChat instance at `nest.xmrchat.com`.

Up to four requests may use the following podcast metadata as search terms:

- podcast title
- podcast author
- podcast feed hostname
- episode title

In the current release, this lookup can occur automatically, including during
app startup before the playback screen or Tip action is opened. It can also
occur for a podcast that does not publish a Monero or XMRChat funding link.

The lookup is used to find an XMRChat creator page that can be offered through
the Tip action.

The XMRChat web client and server/API are free and open-source software licensed
under Apache-2.0. The source code and self-hosting configuration are available at:

https://github.com/XMRChat/xmrchat

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
