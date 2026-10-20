package com.geotracker.fx.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Minimal PNG encoder for JavaFX images (avoids pulling in javafx.swing / AWT). */
public final class PngWriter {
    private PngWriter() {}

    public static void write(Image image, Path path) throws IOException {
        write(pixels(image), (int) image.getWidth(), (int) image.getHeight(), path);
    }

    /** Copies the pixels (BGRA). Call on the FX thread for snapshot images. */
    public static byte[] pixels(Image image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        byte[] bgra = new byte[w * h * 4];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getByteBgraInstance(), bgra, 0, w * 4);
        return bgra;
    }

    /** Encodes BGRA pixels as an RGB PNG. Safe to call from any thread. */
    public static void write(byte[] bgra, int w, int h, Path path) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(w * h);
        try (DeflaterOutputStream z = new DeflaterOutputStream(compressed, new Deflater(6))) {
            byte[] row = new byte[1 + w * 3];
            for (int y = 0; y < h; y++) {
                row[0] = 0; // filter: none
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    row[1 + x * 3] = bgra[i + 2];
                    row[2 + x * 3] = bgra[i + 1];
                    row[3 + x * 3] = bgra[i];
                }
                z.write(row);
            }
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(header);
        d.writeInt(w);
        d.writeInt(h);
        d.writeByte(8);  // bit depth
        d.writeByte(2);  // colour type: RGB
        d.writeByte(0);
        d.writeByte(0);
        d.writeByte(0);

        Files.createDirectories(path.toAbsolutePath().getParent());
        try (OutputStream file = Files.newOutputStream(path); DataOutputStream out = new DataOutputStream(file)) {
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            chunk(out, "IHDR", header.toByteArray());
            chunk(out, "IDAT", compressed.toByteArray());
            chunk(out, "IEND", new byte[0]);
        }
    }

    private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
