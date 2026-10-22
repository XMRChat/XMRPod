(() => {
    const id = Math.random().toString(36).slice(2);
    let lastState;
    let blocked = false;
    let activeMedia;
    const media = () => Array.from(document.querySelectorAll('video,audio'));
    const report = () => {
        const playing = media().some(item => !item.paused && !item.ended);
        if (playing !== lastState) {
            lastState = playing;
            xmrpodPlayback.postMessage(id + ':' + playing);
        }
    };
    window.addEventListener('message', event => {
        if (event.source !== window.parent && event.source !== window) {
            return;
        }
        const command = event.data && event.data.xmrpodPlayback;
        if (command !== 'play' && command !== 'pause' && command !== 'unblock') {
            return;
        }
        blocked = command === 'pause';
        if (blocked) {
            media().forEach(item => item.pause());
        } else if (command === 'play') {
            const item = activeMedia && activeMedia.isConnected ? activeMedia : media()[0];
            if (item) {
                item.play().catch(() => {});
            }
        }
        document.querySelectorAll('iframe').forEach(frame => {
            frame.contentWindow.postMessage(event.data, '*');
        });
        report();
    });
    document.addEventListener('play', event => {
        if (event.target instanceof HTMLMediaElement && !blocked) {
            activeMedia = event.target;
        }
        if (blocked && event.target instanceof HTMLMediaElement) {
            event.target.pause();
        }
        report();
    }, true);
    ['playing', 'pause', 'ended', 'emptied'].forEach(name => {
        document.addEventListener(name, report, true);
    });
    window.addEventListener('pagehide', () => xmrpodPlayback.postMessage(id + ':false'));
    setInterval(report, 1000);
})();
