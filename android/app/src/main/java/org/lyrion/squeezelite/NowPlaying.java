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

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads the details of the track that Lyrion is currently playing, and publishes these -
 * together with the playback state - into the app's MediaSession.
 *
 * Android relays the contents of an active MediaSession to whatever is interested in it,
 * which includes the AVRCP metadata sent to a connected Bluetooth device (car stereo,
 * headphones, speaker, ...), Android Auto, the lock screen, and Wear OS. Up until now the
 * MediaSession was only used to *receive* commands from such devices, this adds the
 * opposite direction.
 */
public class NowPlaying {
    // artist, album, duration, coverid, artwork url, remote stream title, is-remote
    private static final String TAGS = "tags:aldcKNx";
    // Wait a little before querying, so that LMS has settled on the new track, and so that
    // a burst of events only results in a single query.
    private static final long QUERY_DELAY = 250;
    // If LMS still reports the previous track then try again after this long.
    private static final long RETRY_DELAY = 1500;
    private static final int MAX_RETRIES = 2;
    // Keep the artwork small enough to comfortably fit through a binder transaction.
    private static final int MAX_COVER_SIZE = 384;

    private final Library lib;
    private final MediaSessionCompat session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable queryTask = this::query;
    private final PlayerService service;

    private boolean released = false;
    private int retries = 0;
    // Identity of the track currently published, used to detect changes.
    private String trackKey = null;
    private int state = PlaybackStateCompat.STATE_NONE;
    private String coverUrl = null;
    private Bitmap cover = null;
    private MediaMetadataCompat.Builder metadata = null;
    private String description = null;

    public NowPlaying(PlayerService service, Library lib, MediaSessionCompat session) {
        this.service = service;
        this.lib = lib;
        this.session = session;
    }

    /**
     * Something happened that may have changed what is playing - re-read the details from LMS.
     */
    public void update() {
        if (released) {
            return;
        }
        retries = MAX_RETRIES;
        handler.removeCallbacks(queryTask);
        handler.postDelayed(queryTask, QUERY_DELAY);
    }

    public void release() {
        released = true;
        handler.removeCallbacks(queryTask);
        cover = null;
        coverUrl = null;
    }

    /**
     * Short 'Title - Artist' description of the current track, or null if nothing is known.
     */
    public String getDescription() {
        return description;
    }

    private void query() {
        if (released) {
            return;
        }
        lib.getStatus(TAGS, response -> {
            if (!released) {
                handleStatus(response);
            }
        });
    }

    private void handleStatus(JSONObject response) {
        JSONObject result = null==response ? null : response.optJSONObject("result");
        if (null==result) {
            Utils.warn("No status received from server");
            retry();
            return;
        }

        String mode = result.optString("mode", "stop");
        JSONArray loop = result.optJSONArray("playlist_loop");
        JSONObject track = null!=loop && loop.length()>0 ? loop.optJSONObject(0) : null;

        if (null==track || "stop".equals(mode)) {
            setStopped();
            return;
        }

        String title = firstOf(track, "title", "remote_title");
        String artist = firstOf(track, "artist", "trackartist", "albumartist", "artist_name");
        String album = firstOf(track, "album", "remote_title");
        // 'duration' is per-track for local files, but the status itself is more reliable for
        // remote streams that LMS knows the length of.
        double duration = track.optDouble("duration", result.optDouble("duration", 0));
        double time = result.optDouble("time", 0);
        String url = coverUrl(track);

        String key = title + " " + artist + " " + album + " " + duration + " " + url;
        int newState = "play".equals(mode) ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        if (key.equals(trackKey)) {
            // Nothing new - unless the playback state itself changed, LMS may not have caught
            // up with us yet, so try again shortly.
            if (newState==state) {
                retry();
            }
        } else {
            Utils.debug("New track:" + title + " - " + artist);
            trackKey = key;
            metadata = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (long)(duration*1000));
            description = Utils.isEmpty(artist) ? title : (title + " - " + artist);
            if (!url.equals(coverUrl)) {
                coverUrl = url;
                cover = null;
                fetchCover(url);
            }
            publishMetadata();
            service.trackChanged();
        }

        setState(newState, (long)(time*1000));
    }

    /**
     * LMS did not (yet) tell us anything new - it can briefly still report the previous track
     * after we have been told that a new one started. Try again, a couple of times.
     */
    private void retry() {
        if (retries>0) {
            retries--;
            handler.removeCallbacks(queryTask);
            handler.postDelayed(queryTask, RETRY_DELAY);
        }
    }

    private void setStopped() {
        boolean changed = null!=trackKey;
        trackKey = null;
        description = null;
        setState(PlaybackStateCompat.STATE_STOPPED, 0);
        if (changed) {
            service.trackChanged();
        }
    }

    private void setState(int state, long position) {
        this.state = state;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state, position, PlaybackStateCompat.STATE_PLAYING==state ? 1.0f : 0.0f,
                          SystemClock.elapsedRealtime())
                .build());
    }

    private void publishMetadata() {
        if (null==metadata) {
            return;
        }
        session.setMetadata(metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover)
                                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
                                    .build());
    }

    private void fetchCover(String url) {
        if (Utils.isEmpty(url)) {
            return;
        }
        lib.fetchImage(url, MAX_COVER_SIZE, bitmap -> {
            // Only use this if it is still the cover we are interested in.
            if (!released && null!=bitmap && url.equals(coverUrl)) {
                cover = bitmap;
                publishMetadata();
                service.trackChanged();
            }
        });
    }

    private String coverUrl(JSONObject track) {
        String base = lib.getServerUrl();
        if (null==base) {
            return "";
        }
        String coverId = track.optString("coverid", "");
        if (!Utils.isEmpty(coverId)) {
            return base + "music/" + coverId + "/cover.jpg";
        }
        String url = track.optString("artwork_url", "");
        if (!Utils.isEmpty(url)) {
            return url.startsWith("http") ? url : (base + (url.startsWith("/") ? url.substring(1) : url));
        }
        // Fall back to whatever LMS thinks this player's current cover is - this covers most
        // remote streams.
        String mac = lib.getMac();
        return Utils.isEmpty(mac) ? "" : (base + "music/current/cover.jpg?player=" + mac);
    }

    private static String firstOf(JSONObject obj, String... keys) {
        for (String key : keys) {
            String val = obj.optString(key, "");
            if (!Utils.isEmpty(val)) {
                return val;
            }
        }
        return "";
    }
}
