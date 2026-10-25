package com.bejo.voice.service;

import java.util.ArrayList;
import java.util.List;

/** {@link LiveSale.Timers} on a clock the test moves by hand: {@link #advance} runs what has come due, in order. */
final class ManualTimers implements LiveSale.Timers {

    private static final class Task {
        final long at;
        final Runnable run;
        boolean cancelled;
        Task(long at, Runnable run) { this.at = at; this.run = run; }
    }

    private long now;
    private final List<Task> tasks = new ArrayList<>();

    @Override
    public Cancel after(long millis, Runnable task) {
        Task t = new Task(now + millis, task);
        tasks.add(t);
        return () -> t.cancelled = true;
    }

    /** Moves the clock forward by {@code millis}, running every task that falls due (including ones they schedule). */
    void advance(long millis) {
        long target = now + millis;
        while (true) {
            Task next = null;
            for (Task t : tasks) {
                if (!t.cancelled && t.at <= target && (next == null || t.at < next.at)) next = t;
            }
            if (next == null) break;
            now = next.at;
            next.cancelled = true;
            next.run.run();
        }
        now = target;
    }

    int pending() {
        return (int) tasks.stream().filter(t -> !t.cancelled).count();
    }
}
