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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Holds Android audio focus whilst the player is outputting sound, and pauses the player when
 * another app takes it.
 *
 * Android only routes the media of the app holding audio focus to some sinks - most notably
 * Android Auto, which shows the player as active but leaves the car silent if focus was never
 * requested. Holding focus also makes Android pause/duck other apps whilst we play, as they
 * expect from a media player.
 *
 * Android Auto takes permanent focus for itself when it starts, before any app has asked to
 * play. That is indistinguishable from another player taking over, so a permanent loss is not
 * acted upon immediately - the car connection is checked first, and focus reclaimed if it was
 * only Android Auto starting up.
 */
public class AudioFocus {
    // How long to wait after a permanent loss before deciding what caused it
    private static final long RECLAIM_DELAY = 1500;

    private final Context context;
    private final AudioManager audioManager;
    private final Library lib;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioFocusRequest request = null;
    private boolean haveFocus = false;
    // Playback was paused because focus was lost, so should be resumed if it is regained
    private boolean pausedByLoss = false;

    private final AudioManager.OnAudioFocusChangeListener listener = this::onFocusChange;
    private final Runnable reclaimTask = this::reclaim;

    public AudioFocus(Context context, Library lib) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.lib = lib;
    }

    private void onFocusChange(int focusChange) {
        Utils.debug("focusChange:" + focusChange);
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                haveFocus = true;
                handler.removeCallbacks(reclaimTask);
                if (pausedByLoss) {
                    pausedByLoss = false;
                    lib.play();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Android has dropped our request, so it can only come back by asking again
                haveFocus = false;
                pausedByLoss = true;
                lib.pause();
                handler.removeCallbacks(reclaimTask);
                handler.postDelayed(reclaimTask, RECLAIM_DELAY);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // e.g. phone call, or navigation prompt. Request stays registered, and Android
                // sends AUDIOFOCUS_GAIN once the other app has finished.
                pausedByLoss = true;
                lib.pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Android (8+) lowers our volume itself, so carry on playing
                break;
            default:
                break;
        }
    }

    /** Decide whether a permanent loss was Android Auto starting up, or a real takeover. */
    private void reclaim() {
        if (!pausedByLoss) {
            return;
        }
        new Thread(() -> {
            int state = CarConnection.state(context);
            handler.post(() -> onCarConnectionState(state));
        }).start();
    }

    private void onCarConnectionState(int state) {
        if (!pausedByLoss) {
            return;
        }
        pausedByLoss = false;
        if (CarConnection.NOT_CONNECTED == state) {
            Utils.debug("Focus lost to another app");
            return;
        }
        Utils.debug("Focus lost as car connected (" + state + ") - reclaim");
        acquire();
        if (haveFocus) {
            lib.play();
        }
    }

    /** Playback is starting (or resuming) - take focus. Failure to get it does not stop play. */
    public void request() {
        // User explicitly restarted playback, so no longer waiting to resume
        pausedByLoss = false;
        handler.removeCallbacks(reclaimTask);
        acquire();
    }

    private void acquire() {
        if (haveFocus || null==audioManager) {
            return;
        }
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (null==request) {
                request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAcceptsDelayedFocusGain(false)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(listener, handler)
                        .build();
            }
            result = audioManager.requestAudioFocus(request);
        } else {
            result = audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        haveFocus = AudioManager.AUDIOFOCUS_REQUEST_GRANTED==result;
        if (haveFocus) {
            Utils.debug("Audio focus granted");
        } else {
            Utils.warn("Audio focus request failed:" + result);
        }
    }

    /** Playback has paused or stopped. */
    public void abandon() {
        if (pausedByLoss) {
            // This pause is the one we asked for on losing focus - do not give up the request,
            // or the chance to reclaim it
            return;
        }
        releaseFocus();
    }

    /** Player is shutting down. */
    public void release() {
        pausedByLoss = false;
        handler.removeCallbacks(reclaimTask);
        releaseFocus();
    }

    private void releaseFocus() {
        if (!haveFocus || null==audioManager) {
            return;
        }
        haveFocus = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (null!=request) {
                audioManager.abandonAudioFocusRequest(request);
            }
        } else {
            audioManager.abandonAudioFocus(listener);
        }
        Utils.debug("Audio focus abandoned");
    }
}
