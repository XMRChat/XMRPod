# Changelog

All notable changes to XMRPod are documented in this file.

## [0.1.8] - 2026-08-01

### Added

- Validate that XMRChat tip names are at least 2 characters before opening a wallet.

## [0.1.7] - 2026-07-31

### Changed

- Removed the Shownotes info icon from the playback controls to give the action row more space.

## [0.1.6] - 2026-07-31

### Added

- Show the USD value when entering XMR amounts in the XMRChat tip form.

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
