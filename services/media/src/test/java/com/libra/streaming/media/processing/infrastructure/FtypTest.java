package com.libra.streaming.media.processing.infrastructure;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** The brand comes from the file's first box, never from a tag the file could have written about itself. */
class FtypTest {
    @TempDir Path directory;

    private Path file(byte[] content) throws IOException { return Files.write(directory.resolve("f.mp4"), content); }

    private static byte[] box(long size, String type, byte[] brand) {
        var buffer = ByteBuffer.allocate(16);
        buffer.putInt((int) size).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(brand).putInt(512);
        return buffer.array();
    }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.ISO_8859_1); }

    @Test
    void readsTheBrandOfALeadingFtypBox() throws IOException {
        assertThat(Ftyp.majorBrand(file(box(24, "ftyp", ascii("isom"))))).isEqualTo("isom");
        assertThat(Ftyp.majorBrand(file(box(16, "ftyp", ascii("qt  "))))).isEqualTo("qt  ");
    }

    @Test
    void anythingElseIsNoBrand() throws IOException {
        assertThat(Ftyp.majorBrand(file(new byte[0]))).isNull();
        assertThat(Ftyp.majorBrand(file(ascii("ftyp")))).isNull();
        assertThat(Ftyp.majorBrand(file(box(24, "moov", ascii("isom"))))).isNull();
        assertThat(Ftyp.majorBrand(file(box(15, "ftyp", ascii("isom"))))).isNull();
        assertThat(Ftyp.majorBrand(file(box(0, "ftyp", ascii("isom"))))).isNull();
        assertThat(Ftyp.majorBrand(file(box(1, "ftyp", ascii("isom"))))).isNull();
        assertThat(Ftyp.majorBrand(file(box(24, "ftyp", new byte[] {'i', 0, 'o', 'm'})))).isNull();
        assertThat(Ftyp.majorBrand(file(box(24, "ftyp", new byte[] {'i', 's', (byte) 0xC3, 'm'})))).isNull();
        assertThat(Ftyp.majorBrand(directory.resolve("missing.mp4"))).isNull();
    }
}
