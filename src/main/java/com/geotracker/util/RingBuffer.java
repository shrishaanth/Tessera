package com.geotracker.util;

import com.geotracker.model.PositionUpdate;

public class RingBuffer {
    private final PositionUpdate[] buffer;
    private volatile long head = 0;
    private volatile long tail = 0;
    private final int size;

    public RingBuffer(int size) {
        this.buffer = new PositionUpdate[size];
        this.size = size;
    }

    public synchronized boolean offer(PositionUpdate update) {
        long t = tail;
        long h = head;
        if (t - h >= size) {
            head = h + 1;
        }
        buffer[(int) (t % size)] = update;
        tail = t + 1;
        return true;
    }

    public PositionUpdate poll() {
        long h = head;
        long t = tail;
        if (h == t) {
            return null;
        }
        PositionUpdate result = buffer[(int) (h % size)];
        buffer[(int) (h % size)] = null;
        head = h + 1;
        return result;
    }

    public boolean isEmpty() {
        return head == tail;
    }
}
