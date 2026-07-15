# XMRChat Tip API Requirements

This document describes what XMRPod needs from XMRChat to show a Monero tip
button for podcasts.

## MVP Goal

XMRPod should be able to determine whether the currently playing podcast has an
XMRChat tip page and, if so, open a Monero wallet or XMRChat page when the user
taps the tip icon.

The app does not need custody, transaction history, balances, or payment
confirmation for the first version.

## Current App Behavior

The current prototype uses existing podcast feed payment/funding links:

- `monero:` links open directly in a wallet app.
- Links containing `xmrchat` open in the browser.
- The tip button is hidden when no matching link exists.

The API should replace or supplement this feed-link lookup.

## Required Lookup

XMRPod needs a read-only endpoint that accepts a podcast identifier and returns
whether XMRChat tipping is available.

Recommended request:

```http
GET /api/v1/podcast-tip-target?feedUrl=https%3A%2F%2Fexample.com%2Ffeed.xml
```

The feed URL is the best first lookup key because XMRPod already has it for
subscribed podcasts.

Optional lookup keys that could be supported later:

- `podcastGuid`
- `feedId`
- `xmrchatUser`
- `podcastIndexId`

## Response

When tipping is available:

```json
{
  "enabled": true,
  "displayName": "Creator name",
  "xmrchatUrl": "https://xmrchat.com/creator",
  "moneroUri": "monero:84...abcd",
  "moneroAddress": "84...abcd",
  "supportsMessage": false
}
```

When tipping is not available:

```json
{
  "enabled": false
}
```

## Field Semantics

- `enabled`: Required. Controls whether XMRPod shows the tip button.
- `displayName`: Optional. Human-readable creator name for future dialogs.
- `xmrchatUrl`: Recommended. Used as a fallback when no wallet URI is available.
- `moneroUri`: Recommended. Full URI that XMRPod can pass to Android
  `ACTION_VIEW`.
- `moneroAddress`: Optional if `moneroUri` is provided. Useful for copy/fallback
  UI later.
- `supportsMessage`: Required if message tipping is planned. Must be `false` for
  the MVP unless XMRChat can track pending payments for messages.

If both `moneroUri` and `xmrchatUrl` are present, XMRPod should prefer
`moneroUri`.

## Error Handling

Use normal HTTP status codes:

- `200`: Lookup succeeded.
- `400`: Invalid or missing lookup parameter.
- `404`: Podcast or creator not found. XMRPod treats this like `enabled: false`.
- `429`: Rate limited. XMRPod should hide the button or use cached state.
- `5xx`: Temporary backend error. XMRPod should hide the button or use cached
  state.

The app should not show a user-facing error just because tipping lookup failed.

## Caching

The endpoint should be safe to cache. Suggested headers:

```http
Cache-Control: public, max-age=3600
```

XMRPod can cache positive and negative results locally to avoid repeated lookups
while browsing or playing episodes.

## Privacy

The MVP lookup should not require authentication. XMRPod should only send stable
podcast identifiers such as the feed URL. It should not send listener account
data, wallet data, device identifiers, or playback history.

## Future Message Tips

Tip-with-message should be a later API because it needs payment tracking.

Likely future flow:

1. XMRPod requests a pending tip intent with a message.
2. XMRChat returns a payment-specific Monero URI, probably using a generated
   subaddress.
3. User pays from their wallet.
4. XMRChat detects confirmation and publishes the message on the creator tip
   page.

Possible future endpoint:

```http
POST /api/v1/tip-intents
Content-Type: application/json

{
  "feedUrl": "https://example.com/feed.xml",
  "message": "Great episode",
  "displayName": "Listener"
}
```

Possible future response:

```json
{
  "tipIntentId": "abc123",
  "moneroUri": "monero:84...abcd?tx_amount=0.1",
  "expiresAt": "2026-07-15T18:00:00Z"
}
```

This is explicitly out of scope for the MVP.
