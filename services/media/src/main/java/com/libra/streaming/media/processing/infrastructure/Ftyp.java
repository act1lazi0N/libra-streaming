package com.libra.streaming.media.processing.infrastructure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads the major brand from the file's own first box. ffprobe reports {@code major_brand} as a metadata tag, and a
 * file can supply a tag of that name itself: current builds append it to the real brand, but the packaged Ubuntu
 * build replaces it, which would let a QuickTime file present itself as ISO base media. The brand is therefore read
 * from the {@code ftyp} box and the tag is never used. At most 16 bytes are read.
 */
final class Ftyp {
    private Ftyp() {}

    /** The four-character major brand of a leading {@code ftyp} box, or null if the file does not start with one. */
    static String majorBrand(Path file) {
        var head = ByteBuffer.allocate(16);
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (head.hasRemaining()) {
                if (channel.read(head) < 0) { break; }
            }
        } catch (IOException exception) {
            return null;
        }
        head.flip();
        if (head.remaining() < 12) { return null; }
        long size = head.getInt() & 0xFFFFFFFFL;
        var type = new byte[4];
        head.get(type);
        // Header, major brand and minor version make 16 bytes. Sizes 0 ("to the end") and 1 ("64-bit size", with
        // the brand further on) are not shapes a real ftyp box takes, so they count as no brand.
        if (!"ftyp".equals(new String(type, StandardCharsets.ISO_8859_1)) || size < 16) { return null; }
        var brand = new byte[4];
        head.get(brand);
        for (byte value : brand) {
            if (value < 0x20 || value > 0x7E) { return null; }
        }
        return new String(brand, StandardCharsets.US_ASCII);
    }
}
