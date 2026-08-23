# Changelog

All notable changes to XMRPod are documented in this file.

## [0.1.17] - 2026-08-23

### Fixed

- Fix XMRPod updates re-running inherited preference migrations and crashing on launch after the feed refresh interval was multiplied repeatedly.

## [0.1.16] - 2026-08-22

### Fixed

- Make long XMRChat tip messages easier to review by expanding the tip sheet and allowing the message field to scroll while typing.

## [0.1.15] - 2026-08-21

### Fixed

- Refresh XMRChat tip tier preset amounts when switching between fiat and XMR entry modes.

## [0.1.14] - 2026-08-15

### Changed

- Refresh the XMRChat creator directory at most once every 15 minutes while the app is in use, instead of only once per day at app start, so newly registered podcast pages show up sooner.

## [0.1.13] - 2026-08-13

### Fixed

- Fix release builds crashing immediately on launch by keeping WorkManager's database implementation from being removed during minification.

## [0.1.12] - 2026-08-12

### Added

- Show XMRChat tip tiers in the Monero tipping form.
- Enforce XMRChat page minimum tip amounts and tier-specific message lengths before opening a wallet.
- Support creator page fiat currencies when previewing tip amounts.

### Fixed

- Open tips with the documented universal Monero URI format and omit unsupported transaction descriptions.
- Launch wallet deep links with browser-style intent metadata so Cake Wallet opens the send screen reliably.
- Show clearer XMRChat tip creation errors for network failures and server responses.

## [0.1.11] - 2026-08-08

### Fixed

- Enforce the 255-character limit for XMRChat tip messages.

## [0.1.10] - 2026-08-08

### Added

- Add a compact XMRChat page link icon to the tipping modal.

## [0.1.9] - 2026-08-08

### Changed

- Replaced remaining localized inherited brand mentions with XMRPod.

## [0.1.8] - 2026-08-07

### Changed

- Replaced podcast-metadata-based XMRChat search with privacy-preserving local matching. XMRPod now downloads a generic list of public XMRChat creator pages that have a registered podcast RSS link and matches your subscribed feeds against it on your device. The app no longer makes any search requests that send podcast titles, authors, feed hosts, or episode titles to the server.
- Creator discovery now also matches subscribed podcasts by their declared website URL (full URL, path-aware), in addition to RSS feed URL, so creators who registered a website link on XMRChat are also discoverable. Website URLs claimed by more than one creator are skipped rather than guessed.

### Added

- Send a `source=xmrpod` marker when creating an XMRChat tip so the server can distinguish tips sent from XMRPod, without tracking what you listen to.
- First-launch privacy disclosure explaining how XMRChat creator discovery works and that no podcast metadata leaves the device.

## [0.1.7] - 2026-08-02

### Changed

- Disable generated ART profiles in release builds so release APKs can be reproduced reliably.

### Fixed

- Make the displayed commit hash deterministic across release and reproducible builds.

## [0.1.6] - 2026-08-01

### Added

- Show the USD value when entering XMR amounts in the XMRChat tip form.
- Validate that XMRChat tip names are at least 2 characters before opening a wallet.

### Changed

- Removed the Shownotes info icon from the playback controls to give the action row more space.

### Fixed

- Open XMRChat tips in Cake Wallet and Monero.com using their app-specific Monero send URI formats.
- Keep the Shownotes button height consistent with the Monero Tip and Chapters controls.

## [0.1.5] - 2026-07-30

### Changed

- Sign release APKs with the persistent XMRPod release key.

## [0.1.4] - 2026-07-30

### Added

- Added a USD/XMR amount toggle to the XMRChat tip form.

### Fixed

- Show XMRChat tip creation errors from the server instead of a generic failure message.
- Keep the tip form open when XMRChat rejects a tip address request.
- Omit blank optional messages from XMRChat tip requests so empty messages are accepted.

## [0.1.3] - 2026-07-30

### Changed

- Regenerated XMRPod launcher, splash, and notification icons from the updated JPEG artwork.
- Added Fastlane/F-Droid icon and feature graphic assets.
- Updated the store description for the current XMRPod tipping flow.

## [0.1.2] - 2026-07-29

### Added

- Added a message field to XMRChat tips and send it when creating the tip payment address.

### Changed

- Replaced XMRPod launcher and notification icons with the updated transparent artwork.
- Made the playback Monero Tip action compact so it fits beside Shownotes.
- Shortened the playback Chapters label to `Ch.`.
- Use a bottom sheet for XMRChat tips so the form stays usable with the soft keyboard.

### Fixed

- Keep discovered XMRChat tip targets visible during playback state transitions.
- Improve XMRChat page discovery for currently playing podcast metadata.

## [0.1.1] - 2026-07-28

### Fixed

- Show the playback Tip action when an episode or feed description includes an XMRChat link.
- Discover matching public XMRChat creator pages from podcast metadata when the feed does not publish funding tags.

## [0.1.0] - 2026-07-28

### Added

- Initial XMRPod fork release with the `com.xmrchat.xmrpod` application ID.
- Added a playback-screen Tip action for Monero-compatible podcast funding links.
- Added support for XMRChat profile links through the existing public `POST /tips` API.
- Added an automated GitHub release workflow that builds and publishes the Free release APK.

### Changed

- Rebranded the app and release metadata for XMRPod.
- Reset app versioning to start fresh at `0.1.0`.

### Removed

- Removed inherited release tags from the fork so XMRPod versioning starts cleanly.
- Removed the local XMRChat API draft document now that the existing API is used directly.
