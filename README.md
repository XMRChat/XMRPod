# XMRPod

XMRPod is an open-source podcast app for Android with a Monero tipping proof of concept for podcasts that publish compatible XMRChat tip information.

## Monero Tipping

The current MVP adds a `Tip` action to the playback screen. When the active podcast exposes a supported Monero or XMRChat funding link, XMRPod opens a compatible wallet or browser so the listener can send a tip.

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

## License

XMRPod is licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE).
