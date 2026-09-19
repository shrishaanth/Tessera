package com.geotracker.util;

public class RingBuffer<T> {
    private final Object[] buffer;
    private volatile long head = 0;
    private volatile long tail = 0;
    private final int size;

    public RingBuffer(int size) {
        this.buffer = new Object[size];
        this.size = size;
    }

    public synchronized boolean offer(T update) {
        long t = tail;
        long h = head;
        if (t - h >= size) {
            head = h + 1;
        }
        buffer[(int) (t % size)] = update;
        tail = t + 1;
        return true;
    }

    public synchronized boolean put(T update) {
        return offer(update);
    }

    public synchronized T poll() {
        long h = head;
        long t = tail;
        if (h == t) {
            return null;
        }
        @SuppressWarnings("unchecked")
        T result = (T) buffer[(int) (h % size)];
        buffer[(int) (h % size)] = null;
        head = h + 1;
        return result;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) return null;
        long h = head;
        long t = tail;
        int len = (int) (t - h);
        if (index >= len) return null;
        @SuppressWarnings("unchecked")
        T result = (T) buffer[(int) ((h + index) % size)];
        return result;
    }

    public synchronized int size() {
        long h = head;
        long t = tail;
        return (int) (t - h);
    }

    public boolean isEmpty() {
        return head == tail;
    }
}
