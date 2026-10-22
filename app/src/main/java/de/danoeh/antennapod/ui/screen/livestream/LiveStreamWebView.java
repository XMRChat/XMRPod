package de.danoeh.antennapod.ui.screen.livestream;

import android.content.Context;
import android.webkit.WebView;

public class LiveStreamWebView extends WebView {
    public LiveStreamWebView(Context context) {
        super(context);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(VISIBLE);
    }
}
