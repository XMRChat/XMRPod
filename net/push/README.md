# :net:push

Optional UnifiedPush registration, live notifications, and hourly live-list fallback. The application installs a feed-to-creator matcher during ClientConfigurator initialization so subscription matching stays local. Settings depend on this module instead of depending on the application. The connector and installation credentials are excluded from Android backups.

Setup entry points open the notification settings with `NotificationPreferencesFragment.EXTRA_START_PUSH_SETUP`. Setup then enables push and calls `useCurrentOrDefaultProvider`, which reuses the acknowledged or system default distributor and only shows a chooser or install help when none resolves. Connector registration uses ordinary background broadcasts, so a congested broadcast queue on the device delays it.

Every podcast bell defaults off. Push setup and delivery testing are independent of podcast bells; disabling the transport removes its server registration. The database stores when the listener enabled alerts so an already-running broadcast is not announced during setup. Push and polling share persisted broadcast deduplication. Ordinary RSS refresh scheduling is independent.

Broadcast claims and installation credential creation use synchronous preference writes on workers so they survive process termination. Avoid scheduling work during ClientConfigurator initialization; the activity and push callbacks schedule work after application startup.
