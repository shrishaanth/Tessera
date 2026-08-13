package com.geotracker.util;

import com.geotracker.model.PositionUpdate;

public class RingBufferTest {
    public static void main(String[] args) {
        boolean allPassed = true;
        allPassed &= testOfferPollFifo();
        allPassed &= testOfferWrapOverwritesOldest();
        allPassed &= testIsEmptyOnEmpty();
        if (allPassed) {
            System.out.println("All RingBuffer tests passed");
        } else {
            System.out.println("Some RingBuffer tests FAILED");
            System.exit(1);
        }
    }

    private static boolean testOfferPollFifo() {
        try {
            RingBuffer rb = new RingBuffer(4);
            PositionUpdate u1 = new PositionUpdate(1, 0, 0, 0);
            PositionUpdate u2 = new PositionUpdate(2, 1, 1, 1);
            assert rb.offer(u1) : "offer failed";
            assert rb.offer(u2) : "offer failed";
            assert u1.equals(rb.poll()) : "Expected u1";
            assert u2.equals(rb.poll()) : "Expected u2";
            assert rb.poll() == null : "Expected null";
            System.out.println("PASS: offerPollFifo");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: offerPollFifo - " + t.getMessage());
            return false;
        }
    }

    private static boolean testOfferWrapOverwritesOldest() {
        try {
            RingBuffer rb = new RingBuffer(2);
            PositionUpdate u1 = new PositionUpdate(1, 0, 0, 0);
            PositionUpdate u2 = new PositionUpdate(2, 1, 1, 1);
            PositionUpdate u3 = new PositionUpdate(3, 2, 2, 2);
            assert rb.offer(u1) : "offer failed";
            assert rb.offer(u2) : "offer failed";
            assert rb.offer(u3) : "offer failed";
            assert u2.equals(rb.poll()) : "Expected u2";
            assert u3.equals(rb.poll()) : "Expected u3";
            assert rb.poll() == null : "Expected null";
            System.out.println("PASS: offerWrapOverwritesOldest");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: offerWrapOverwritesOldest - " + t.getMessage());
            return false;
        }
    }

    private static boolean testIsEmptyOnEmpty() {
        try {
            RingBuffer rb = new RingBuffer(4);
            assert rb.isEmpty() : "Expected empty";
            rb.offer(new PositionUpdate(1, 0, 0, 0));
            assert !rb.isEmpty() : "Expected not empty";
            rb.poll();
            assert rb.isEmpty() : "Expected empty";
            System.out.println("PASS: isEmptyOnEmpty");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: isEmptyOnEmpty - " + t.getMessage());
            return false;
        }
    }
}
