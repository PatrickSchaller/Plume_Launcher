package dev.plume.search;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.os.CancellationSignal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

final class BroadcastGuide {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    static final int LIMIT = 40;
    private static final long LOOKAHEAD = 48L * 60 * 60 * 1000;

    static final class Program {
        final long channelId, start;
        final String station, title, episode;
        Program(long channelId, String station, String title, String episode, long start) {
            this.channelId = channelId; this.station = station; this.title = title;
            this.episode = episode; this.start = start;
        }
    }

    static final class Result {
        final List<Program> programs;
        final int channels, availablePrograms;
        Result(List<Program> programs, int channels, int availablePrograms) {
            this.programs = programs; this.channels = channels;
            this.availablePrograms = availablePrograms;
        }
    }

    static Result search(Context context, String query, CancellationSignal cancellation) {
        long now = System.currentTimeMillis(), until = now + LOOKAHEAD;
        List<Long> ids = new ArrayList<>();
        List<String> stations = new ArrayList<>();
        String[] channelColumns = {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_TYPE,
                TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.COLUMN_BROWSABLE,
                TvContract.Channels.COLUMN_LOCKED};
        // Unprivileged TV providers reject SQL selections; narrow results in memory instead.
        try (Cursor channels = context.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                channelColumns, null, null, null, cancellation)) {
            if (channels == null) throw new IllegalStateException("The TV guide provider is unavailable.");
            for (int scanned = 0; scanned < 1000 && ids.size() < 100 && channels.moveToNext(); scanned++) {
                cancellation.throwIfCanceled();
                if (!BroadcastRules.isAntenna(channels.getString(3), channels.getString(4),
                        channels.getInt(5) == 1, channels.getInt(6) == 1)) continue;
                ids.add(channels.getLong(0));
                String number = channels.getString(1), name = channels.getString(2);
                stations.add((number == null ? "" : number + " ") + (name == null ? "Antenna" : name));
            }
        }
        Comparator<Program> chronological = Comparator.comparingLong(program -> program.start);
        PriorityQueue<Program> matches = new PriorityQueue<>(LIMIT + 1, chronological.reversed());
        String[] columns = {TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_EPISODE_TITLE,
                TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS};
        int scanned = 0;
        // shortcut: search at most 100 channels and 10,000 cached rows over 48 hours, expand after hardware profiling.
        for (int index = 0; index < ids.size() && scanned < 10000; index++) {
            cancellation.throwIfCanceled();
            try (Cursor programs = context.getContentResolver().query(
                    TvContract.buildProgramsUriForChannel(ids.get(index), now, until),
                    columns, null, null, null, cancellation)) {
                if (programs == null) throw new IllegalStateException("The TV guide provider is unavailable.");
                for (int rows = 0; rows < 500 && scanned < 10000 && programs.moveToNext(); rows++, scanned++) {
                    cancellation.throwIfCanceled();
                    String title = programs.getString(0), episode = programs.getString(1);
                    long start = programs.getLong(2), end = programs.getLong(3);
                    if (end <= now || start >= until || end <= start
                            || !BroadcastRules.matches(query, title, episode)) continue;
                    matches.add(new Program(ids.get(index), stations.get(index), title, episode, start));
                    if (matches.size() > LIMIT) matches.poll();
                }
            }
        }
        List<Program> ordered = new ArrayList<>(matches);
        ordered.sort(chronological);
        return new Result(ordered, ids.size(), scanned);
    }
}
