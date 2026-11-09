package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * THE OPEN GROWTHTRACK SCREEN HEARS A SEND IT DID NOT START (the live GrowthTrack test, 9 Nov
 * 2026, finding 9). The background job sent a waiting session at 10:16, and the open status card
 * kept "1 waiting · sends when online" with Send now - and the open "Sent and waiting sessions"
 * kept "Waiting" - for minutes, until the screen was opened again: it read the connection when
 * it came to the front and on its own actions, never when a pass it had not started finished.
 *
 * So whoever shows the sync state watches while it is on screen, and is told once each time a
 * sync pass finishes, whoever started it. No polling: nothing happens between passes. Main
 * thread only (GrowthTrackManager tells it there); pure, so it is tested here.
 */
public final class GrowthTrackWatch {
    /** A screen showing the sync state. */
    public interface Watcher { void syncChanged(); }

    private final List<Watcher> watchers = new ArrayList<Watcher>();

    /** On screen: told of every pass that finishes from now on. Once, however often asked. */
    public void watch(Watcher w) {
        if (w != null && !watchers.contains(w)) watchers.add(w);
    }

    /** Off screen: told nothing more. */
    public void unwatch(Watcher w) {
        watchers.remove(w);
    }

    /** A sync pass has finished: each watcher is told once. A watcher may stop watching while
     *  it is told. */
    public void passFinished() {
        List<Watcher> now = new ArrayList<Watcher>(watchers);
        for (int i = 0; i < now.size(); i++) if (watchers.contains(now.get(i))) now.get(i).syncChanged();
    }

    /** How many are watching. */
    public int watching() { return watchers.size(); }
}
