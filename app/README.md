# :app

The main application module that integrates all features and hosts app-specific UI screens not large enough for their own module.
`PodcastApp` initializes the app; `ClientConfigurator` registers service implementations (download, sync) at startup.

The miniplayer (the collapsed player bar at the bottom of the screen) is implemented in `ExternalPlayerFragment`.
It is hosted in `MainActivity` as a bottom sheet. `MainActivity` controls its visibility via `setPlayerVisible()` based on playback state.

XMRChat tip wallet launches use Cake Wallet's app-specific `cakewallet:` and `monerocom:` Monero send
URI formats before falling back to the standard `monero:` URI.

XMRChat live stream status comes from the generic `/live-streams` list in `XmrChatLiveStreams` and is matched to feeds
locally through `XmrChatPageDirectory`, so no per-podcast request reveals which feed the user is viewing.

`LiveStreamPlaybackService` owns the embedded live player independently of its fragment. Screens bind to the service
and attach/detach its WebView; playback and media controls remain active when the screen closes. Playback observation
and commands run inside the embed's origin using AndroidX WebKit document-start scripts and web messages.
Move the player to its background host in the fragment stop lifecycle, including Android Home and screen-off,
and restore it when the fragment starts again. While detached from a screen, the player stays attached to a service-owned private display so WebView keeps its
media event loop active. The private display only renders the live player and is released when the player returns
to the screen or the service stops.
Preserve the player's viewport when changing hosts. Some providers reset playback during reattachment, so the
service briefly retries an already playing stream; pause commands and player touches cancel these retries.
WebView requests and handles audio focus for embedded media itself; the service must not request it as well.

The XMRChat tip sheet lives in `XmrChatTipFragment`; screens that offer tipping extend it and call `openXmrChatTip()`.

## Wear OS Communication (play flavor only)

`WearListenerService` is a `WearableListenerService` that handles `DataLayer` messages from connected watches.
It responds to watch-initiated requests.
The service is kept alive by the Android framework while at least one watch is connected.
