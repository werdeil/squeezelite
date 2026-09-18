/*
 *  Squeezelite Android
 *
 *  (c) Craig Drummond 2025-2026 <craig.p.drummond@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.lyrion.squeezelite;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * Watches whether Android Auto is projecting, as published by its own content provider.
 *
 * The end of a session - unplugging the phone, or switching the car off - is not reliably visible
 * as a Bluetooth disconnection. A wired session leaves the car's Bluetooth link up, and the
 * hands-free link that starts the player comes and goes during a session anyway. This provider is
 * the one signal that says what Android Auto itself is doing.
 */
public class CarConnection {
    // 0 is not connected, 1 is Android Automotive, 2 is a projected session.
    // See androidx.car.app.connection.CarConnection.
    private static final Uri URI = Uri.parse("content://androidx.car.app.connection");
    private static final String STATE = "CarConnectionState";
    public static final int NOT_CONNECTED = 0;
    // Ask once more, after this long, before believing a session has ended
    private static final long CONFIRM_DELAY = 3000;
    // Whilst a session is running, ask anyway this often. The provider is expected to tell us
    // when its state changes, but the player must not be left running if it does not.
    private static final long POLL_INTERVAL = 30000;

    private final Context context;
    private final Runnable onSessionEnded;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable confirmTask = this::confirm;
    private final Runnable pollTask = this::poll;
    private ContentObserver observer = null;
    // Only a session that was seen running can end
    private boolean seenConnected = false;

    /** Read the state Android Auto publishes. Queries a provider, so not for the main thread. */
    public static int state(Context context) {
        try (Cursor cursor = context.getContentResolver().query(URI, new String[]{STATE}, null, null, null)) {
            if (null == cursor) {
                return NOT_CONNECTED;
            }
            int col = cursor.getColumnIndex(STATE);
            return col < 0 || !cursor.moveToNext() ? NOT_CONNECTED : cursor.getInt(col);
        } catch (Exception e) {
            // Android Auto is not installed, or is too old to publish its state
            Utils.debug("Car connection state unavailable");
            return NOT_CONNECTED;
        }
    }

    public CarConnection(Context context, Runnable onSessionEnded) {
        this.context = context.getApplicationContext();
        this.onSessionEnded = onSessionEnded;
    }

    public void start() {
        if (null!=observer) {
            return;
        }
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                read(false);
            }
        };
        try {
            context.getContentResolver().registerContentObserver(URI, false, observer);
        } catch (Exception e) {
            Utils.error("Failed to watch car connection", e);
            observer = null;
            return;
        }
        // The player may have been started by the car connecting, before Android Auto itself is
        // up, so this first read usually reports nothing. The observer catches the session.
        read(false);
    }

    public void release() {
        handler.removeCallbacks(confirmTask);
        handler.removeCallbacks(pollTask);
        if (null!=observer) {
            try {
                context.getContentResolver().unregisterContentObserver(observer);
            } catch (Exception e) {
                Utils.error("Failed to stop watching car connection", e);
            }
            observer = null;
        }
    }

    private void read(boolean confirming) {
        new Thread(() -> {
            int state = state(context);
            handler.post(() -> onState(state, confirming));
        }).start();
    }

    private void onState(int state, boolean confirming) {
        if (null==observer) {
            // Released whilst the query was running
            return;
        }
        Utils.debug("state:"+state+", seenConnected:"+seenConnected+", confirming:"+confirming);
        if (NOT_CONNECTED!=state) {
            seenConnected = true;
            handler.removeCallbacks(confirmTask);
            handler.removeCallbacks(pollTask);
            handler.postDelayed(pollTask, POLL_INTERVAL);
            return;
        }
        if (!seenConnected) {
            return;
        }
        if (confirming) {
            Utils.info("Android Auto session ended");
            seenConnected = false;
            handler.removeCallbacks(pollTask);
            onSessionEnded.run();
        } else {
            // Ask again shortly, so that a blip during a session does not stop the player
            handler.removeCallbacks(confirmTask);
            handler.postDelayed(confirmTask, CONFIRM_DELAY);
        }
    }

    private void confirm() {
        read(true);
    }

    private void poll() {
        read(false);
    }
}
