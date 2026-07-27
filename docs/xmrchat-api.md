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

> **API prefix:** All paths in this document use an `/api/v1` prefix. The
> xmrchat backend does not currently set a global API prefix (`server/src/main.ts`
> never calls `setGlobalPrefix`, and the existing tip route is `POST /tips`), so
> adopting this contract requires either adding a global `/api/v1` prefix in the
> backend or dropping it from these paths.

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
- `supportsMessage`: Required. Indicates whether the creator's page can accept
  message tips. When `true`, XMRPod shows the message field and uses the
  per-tip `POST /api/v1/tip-intents` flow (see "Per-Tip Address Flow" below);
  when `false`, XMRPod uses the static `moneroUri` directly.

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

## Per-Tip Address Flow (Tip Intents)

The static MVP address above is a single primary address reused for every tip.
To support message tips (or any tip whose amount/payment must be reconciled to a
specific listener), XMRPod needs a **per-tip address**: a Monero address that is
specific to one tip and carries a `payment_id` and the tip amount.

Because each such address is amount-specific and single-use, XMRPod must request
a fresh address **when the user enters an amount and taps "Open in Wallet"**,
rather than resolving once up front. It cannot be cached or reused.

### Mechanism: integrated address + payment_id

The backend generates a Monero **integrated address** (primary address + an
8-byte `payment_id`), not a subaddress. This is implemented today by
`makeIntegratedAddress()` in `xmrchat/server/src/shared/utils/monero.ts` and is
already called per tip by `PaymentFlowService.create()` in
`server/src/payment-flow/payment-flow.service.ts`, which also registers a
monero-lws `tx-confirmation` webhook keyed on that `payment_id`. The tip amount
travels in the `tx_amount` query param of the returned `monero:` URI.

### Flow

1. The user enters an amount (and optional message/display name) in XMRPod and
   taps "Open in Wallet".
2. XMRPod calls `POST /api/v1/tip-intents` with the amount, the podcast
   identifier, and (optionally) the message.
3. XMRChat runs `PaymentFlowService.create()` for that amount, producing a fresh
   integrated address + `payment_id` and an LWS webhook. It returns a
   `tipIntentId` and a payment-specific `monero:` URI (address + `tx_amount`).
4. XMRPod hands the `monero:` URI to Android `ACTION_VIEW`; the user pays from
   their wallet.
5. monero-lws confirms the transaction on the `payment_id`; XMRChat marks the
   intent paid and publishes the tip (and message) on the creator's tip page.

Unpaid intents are reaped by xmrchat's existing expired-tip/webhook cleanup
(`deleteExpiredTips` / `deleteExpiredWebhooks`), so an abandoned "Open in Wallet"
must not publish a ghost tip on the creator page.

### Endpoint

```http
POST /api/v1/tip-intents
Content-Type: application/json

{
  "feedUrl": "https://example.com/feed.xml",
  "amount": "0.1",
  "message": "Great episode",
  "displayName": "Listener"
}
```

`amount` is required because the returned address is amount-specific. `message`
and `displayName` are optional. `feedUrl` is the lookup key into the creator's
xmrchat page; alternative keys (`podcastGuid`, `feedId`, `xmrchatUser`,
`podcastIndexId`) may be supported later, subject to xmrchat actually indexing
pages by those identifiers.

### Response

```json
{
  "tipIntentId": "abc123",
  "moneroUri": "monero:84...abcd?tx_amount=0.1",
  "expiresAt": "2026-07-15T18:00:00Z"
}
```

`moneroUri` is the integrated address with the tip amount as `tx_amount`; XMRPod
passes it straight to `ACTION_VIEW`. `expiresAt` is when xmrchat will discard the
unpaid intent and its webhook.

### Scope note

This per-tip flow is the primary XMRPod↔XMRChat integration and supersedes the
static-address MVP for apps that need payment reconciliation. The read-only
`GET /api/v1/podcast-tip-target` remains useful for deciding whether to show the
tip button at all before the user has entered an amount.
