# Changelog

All notable changes to XMRPod are documented in this file.

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
