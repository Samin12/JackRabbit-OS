package com.resonolabs.ui.design;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The generated geometry and the packed PNGs must stay in sync (re-run pack_pixel_head.py). */
public final class PixelHeadAtlasTest {
    private static final int BUCKETS = PixelHeadAtlas.FRAME.length;

    @Test public void bucketsAreOrderedSmallestFirst() {
        assertEquals(4, BUCKETS);
        for (int b = 1; b < BUCKETS; b++) {
            assertTrue(PixelHeadAtlas.FRAME[b] > PixelHeadAtlas.FRAME[b - 1]);
            assertTrue(PixelHeadAtlas.HEAD_H[b] > PixelHeadAtlas.HEAD_H[b - 1]);
        }
        assertEquals(PixelHeadMotion.POSES, PixelHeadAtlas.POSES);
    }

    @Test public void headIsCentredInEveryCell() {
        for (int b = 0; b < BUCKETS; b++) {
            float ax = PixelHeadAtlas.ANCHOR_X[b];
            float ay = PixelHeadAtlas.ANCHOR_Y[b];
            assertEquals("bucket " + b, PixelHeadAtlas.CELL_W[b] / 2f, ax, 2f);
            assertEquals("bucket " + b, PixelHeadAtlas.CELL_H[b] / 2f, ay, 2f);
            assertTrue(PixelHeadAtlas.HEAD_H[b] <= PixelHeadAtlas.CELL_H[b]);
        }
    }

    @Test public void eyePatchesLieInsideTheirCell() {
        for (int b = 0; b < BUCKETS; b++) {
            for (int pose = 0; pose < PixelHeadAtlas.POSES; pose++) {
                assertInside(b, PixelHeadAtlas.ACTIVE_X[b][pose], PixelHeadAtlas.ACTIVE_Y[b][pose],
                        PixelHeadAtlas.ACTIVE_W[b], PixelHeadAtlas.ACTIVE_H[b]);
                assertInside(b, PixelHeadAtlas.BLINK_X[b][pose], PixelHeadAtlas.BLINK_Y[b][pose],
                        PixelHeadAtlas.BLINK_W[b], PixelHeadAtlas.BLINK_H[b]);
                assertInside(b, PixelHeadAtlas.TALK_X[b][pose], PixelHeadAtlas.TALK_Y[b][pose],
                        PixelHeadAtlas.TALK_W[b], PixelHeadAtlas.TALK_H[b]);
            }
            assertEquals(PixelHeadAtlas.ACTIVE_W[b] + 2 * PixelHeadAtlas.GUTTER, PixelHeadAtlas.ACTIVE_SLOT_W[b]);
            assertEquals(PixelHeadAtlas.BLINK_H[b] + 2 * PixelHeadAtlas.GUTTER, PixelHeadAtlas.BLINK_SLOT_H[b]);
            assertEquals(PixelHeadAtlas.TALK_W[b] + 2 * PixelHeadAtlas.GUTTER, PixelHeadAtlas.TALK_SLOT_W[b]);
            assertEquals(PixelHeadAtlas.TALK_H[b] + 2 * PixelHeadAtlas.GUTTER, PixelHeadAtlas.TALK_SLOT_H[b]);
        }
    }

    @Test public void packedPngsMatchTheGeometry() throws IOException {
        int rows = (PixelHeadAtlas.POSES + PixelHeadAtlas.COLUMNS - 1) / PixelHeadAtlas.COLUMNS;
        for (int b = 0; b < BUCKETS; b++) {
            int size = PixelHeadAtlas.FRAME[b];
            Png idle = Png.read(new File(resDir(), "pixel_head_" + size + ".png"));
            assertEquals(PixelHeadAtlas.COLUMNS * PixelHeadAtlas.CELL_W[b], idle.width);
            assertEquals(rows * PixelHeadAtlas.CELL_H[b], idle.height);
            Png eyes = Png.read(new File(resDir(), "pixel_head_" + size + "_eyes.png"));
            int slotW = Math.max(PixelHeadAtlas.TALK_SLOT_W[b],
                    Math.max(PixelHeadAtlas.ACTIVE_SLOT_W[b], PixelHeadAtlas.BLINK_SLOT_W[b]));
            assertEquals(PixelHeadAtlas.COLUMNS * slotW, eyes.width);
            assertEquals(rows * PixelHeadAtlas.ACTIVE_SLOT_H[b], PixelHeadAtlas.BLINK_TOP[b]);
            assertEquals(PixelHeadAtlas.BLINK_TOP[b] + rows * PixelHeadAtlas.BLINK_SLOT_H[b], PixelHeadAtlas.TALK_TOP[b]);
            assertEquals(PixelHeadAtlas.TALK_TOP[b] + rows * PixelHeadAtlas.TALK_SLOT_H[b], eyes.height);
            // 8-bit palette with per-entry alpha: small in the APK, still transparent
            for (Png png : new Png[]{idle, eyes}) {
                assertEquals(3, png.colorType);
                assertEquals(8, png.bitDepth);
                assertTrue(png.hasTransparency);
            }
        }
    }

    @Test public void packedArtIsSmall() {
        long total = 0;
        File dir = resDir();
        File[] files = dir.listFiles((d, name) -> name.startsWith("pixel_head_"));
        assertNotNull(files);
        assertEquals(2 * BUCKETS, files.length);
        for (File file : files) total += file.length();
        assertTrue("pixel head art is " + total / 1024 + " KB", total < 1100 * 1024);
    }

    private static void assertInside(int b, int x, int y, int w, int h) {
        assertTrue(x >= PixelHeadAtlas.GUTTER && y >= PixelHeadAtlas.GUTTER);
        assertTrue(x + w <= PixelHeadAtlas.CELL_W[b] - PixelHeadAtlas.GUTTER);
        assertTrue(y + h <= PixelHeadAtlas.CELL_H[b] - PixelHeadAtlas.GUTTER);
    }

    /** PNG header facts (IHDR + whether a tRNS chunk exists); no image decoding on the JVM. */
    private static final class Png {
        int width;
        int height;
        int bitDepth;
        int colorType;
        boolean hasTransparency;

        static Png read(File file) throws IOException {
            Png png = new Png();
            try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
                assertEquals(0x89504E47, in.readInt());
                in.readInt();
                while (true) {
                    int length = in.readInt();
                    String type = new String(new byte[]{in.readByte(), in.readByte(), in.readByte(), in.readByte()},
                            java.nio.charset.StandardCharsets.US_ASCII);
                    if ("IHDR".equals(type)) {
                        png.width = in.readInt();
                        png.height = in.readInt();
                        png.bitDepth = in.readUnsignedByte();
                        png.colorType = in.readUnsignedByte();
                        in.skipBytes(length - 10);
                    } else {
                        if ("tRNS".equals(type)) png.hasTransparency = true;
                        if ("IDAT".equals(type) || "IEND".equals(type)) break;
                        in.skipBytes(length);
                    }
                    in.readInt(); // CRC
                }
            }
            return png;
        }
    }

    private static File resDir() {
        File dir = new File("src/main/res/drawable-nodpi");
        if (!dir.isDirectory()) dir = new File("core/design/src/main/res/drawable-nodpi");
        return dir;
    }
}
