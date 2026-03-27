package com.hsbc.pluse.dispatch;

public class BackpressureController {

    public enum Action { PAUSE, RESUME, NONE }

    private final int highWaterMark;
    private final int lowWaterMark;

    public BackpressureController(int highWaterMark, int lowWaterMark) {
        if (lowWaterMark >= highWaterMark) {
            throw new IllegalArgumentException("lowWaterMark (" + lowWaterMark
                    + ") must be less than highWaterMark (" + highWaterMark + ")");
        }
        this.highWaterMark = highWaterMark;
        this.lowWaterMark = lowWaterMark;
    }

    public Action evaluate(int inflightCount, boolean currentlyPaused) {
        if (!currentlyPaused && inflightCount >= highWaterMark) {
            return Action.PAUSE;
        }
        if (currentlyPaused && inflightCount <= lowWaterMark) {
            return Action.RESUME;
        }
        return Action.NONE;
    }

    public int getHighWaterMark() { return highWaterMark; }
    public int getLowWaterMark() { return lowWaterMark; }
}
