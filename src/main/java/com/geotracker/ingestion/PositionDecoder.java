package com.geotracker.ingestion;

import com.geotracker.model.PositionUpdate;
import com.geotracker.util.RingBuffer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

public class PositionDecoder {
    public static PositionUpdate decode(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        long vehicleId = dis.readLong();
        double x = dis.readDouble();
        double y = dis.readDouble();
        long timestamp = dis.readLong();
        return new PositionUpdate(vehicleId, x, y, timestamp);
    }
}
