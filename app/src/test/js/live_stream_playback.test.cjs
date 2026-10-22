const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const script = fs.readFileSync(path.resolve(__dirname, '../../main/assets/live_stream_playback.js'), 'utf8');

function player() {
    const events = {};
    const media = [];
    const frames = [];
    const messages = [];
    const window = { parent: {}, addEventListener: (name, listener) => { events[name] = listener; } };
    const document = {
        querySelectorAll: selector => selector === 'iframe' ? frames : media,
        addEventListener: (name, listener) => { events[name] = listener; },
    };
    class Media {
        paused = true;
        ended = false;
        isConnected = true;
        playCount = 0;
        play() {
            this.playCount++;
            this.paused = false;
            events.play({ target: this });
            return Promise.resolve();
        }
        pause() {
            this.paused = true;
            events.pause({ target: this });
        }
    }
    let poll;
    vm.runInNewContext(script, {
        window,
        document,
        HTMLMediaElement: Media,
        xmrpodPlayback: { postMessage: message => { messages.push(message.split(':')[1]); } },
        setInterval: listener => { poll = listener; },
    });
    return {
        media,
        frames,
        messages,
        window,
        addMedia() {
            const item = new Media();
            media.push(item);
            return item;
        },
        command(command, source = window.parent) {
            events.message({ source, data: { xmrpodPlayback: command } });
        },
        poll: () => poll(),
        unload: () => events.pagehide(),
    };
}

test('reports playback changes without duplicating state while buffering', () => {
    const frame = player();
    const video = frame.addMedia();
    frame.poll();
    video.play();
    frame.poll();
    frame.poll();
    video.pause();
    assert.deepEqual(frame.messages, ['false', 'true', 'false']);
});

test('pauses all media and resumes only the previously active player', () => {
    const frame = player();
    const ad = frame.addMedia();
    const video = frame.addMedia();
    video.play();
    frame.command('pause');
    assert.equal(video.paused, true);
    frame.command('play');
    assert.equal(video.paused, false);
    assert.equal(ad.playCount, 0);
});

test('blocks provider autoplay after pause until the listener resumes', () => {
    const frame = player();
    const video = frame.addMedia();
    video.play();
    frame.command('pause');
    video.play();
    assert.equal(video.paused, true);
    frame.command('unblock');
    assert.equal(video.paused, true);
    video.play();
    assert.equal(video.paused, false);
});

test('forwards commands into nested embeds and ignores messages from other frames', () => {
    const frame = player();
    const commands = [];
    frame.frames.push({ contentWindow: { postMessage: message => commands.push(message.xmrpodPlayback) } });
    const video = frame.addMedia();
    video.play();
    frame.command('pause', {});
    assert.equal(video.paused, false);
    assert.deepEqual(commands, []);
    frame.command('pause');
    frame.command('play', frame.window);
    assert.deepEqual(commands, ['pause', 'play']);
    assert.equal(video.paused, false);
});

test('clears playback state when an embed unloads', () => {
    const frame = player();
    frame.addMedia().play();
    frame.unload();
    assert.deepEqual(frame.messages, ['true', 'false']);
});
