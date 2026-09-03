package com.repoguard.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class CiSarifPayloadDecoderTest {

    private static final String SARIF = "{\"version\":\"2.1.0\",\"runs\":[{}]}";
    private final CiSarifPayloadDecoder decoder = new CiSarifPayloadDecoder();

    @Test
    void acceptsRawSarifJsonAndZipArtifact() throws Exception {
        assertThat(decoder.decode(SARIF.getBytes(StandardCharsets.UTF_8), "application/json"))
            .isEqualTo(SARIF);
        byte[] sarifBytes = SARIF.getBytes(StandardCharsets.UTF_8);
        assertThat(decoder.decode(new ByteArrayInputStream(sarifBytes), sarifBytes.length, "application/json"))
            .isEqualTo(SARIF);
        byte[] zip = zip(MapEntry.of("results.sarif", SARIF));
        assertThat(decoder.decode(zip, "application/zip")).isEqualTo(SARIF);
        assertThat(decoder.decode(new ByteArrayInputStream(zip), zip.length, "application/zip"))
            .isEqualTo(SARIF);
    }

    @Test
    void rejectsUnknownLengthRawStreamAfterReadingConfiguredLimit() {
        InputStream overLimit = new InputStream() {
            @Override
            public int read() {
                return 'x';
            }
        };
        assertThatThrownBy(() -> decoder.decode(overLimit, -1, "application/json"))
            .hasMessageContaining("2,000,000 bytes");
    }

    @Test
    void rejectsEmptyOversizedAndMismatchedPayloads() {
        assertThatThrownBy(() -> decoder.decode(new byte[0], "application/json"))
            .hasMessageContaining("payload is required");
        assertThatThrownBy(() -> decoder.decode(new byte[CiSarifPayloadDecoder.MAX_UPLOAD_BYTES + 1], "application/json"))
            .hasMessageContaining("10 MiB");
        assertThatThrownBy(() -> decoder.decode(SARIF.getBytes(StandardCharsets.UTF_8), "application/zip"))
            .hasMessageContaining("does not contain");
    }

    @Test
    void rejectsUnsafeZipEntriesMultipleDocumentsAndTooManyEntries() throws Exception {
        assertThatThrownBy(() -> decoder.decode(zip(MapEntry.of("../results.sarif", SARIF)), null))
            .hasMessageContaining("unsafe entry path");
        assertThatThrownBy(() -> decoder.decode(zip(
            MapEntry.of("one.sarif", SARIF), MapEntry.of("two.json", SARIF)
        ), null)).hasMessageContaining("exactly one SARIF");

        MapEntry[] entries = new MapEntry[21];
        for (int index = 0; index < entries.length; index++) {
            entries[index] = MapEntry.of("entry-" + index + ".txt", "x");
        }
        assertThatThrownBy(() -> decoder.decode(zip(entries), null))
            .hasMessageContaining("too many entries");
    }

    @Test
    void rejectsZipWithoutSarifDocumentAndExpandedContent() throws Exception {
        assertThatThrownBy(() -> decoder.decode(zip(MapEntry.of("readme.txt", "not sarif")), null))
            .hasMessageContaining("does not contain");
        String large = "x".repeat(CiSarifPayloadDecoder.MAX_SARIF_BYTES + 1);
        assertThatThrownBy(() -> decoder.decode(zip(MapEntry.of("results.sarif", large)), null))
            .hasMessageContaining("budget");
    }

    @Test
    void coversZipDetectionAndEntryPathGuards() throws Exception {
        assertThat(decoder.decode(new byte[] {1}, null)).isEqualTo("\u0001");
        assertThat(decoder.decode(new byte[] {'P', 'K', 3, 5}, null)).isNotNull();
        assertThat(decoder.decode(new byte[] {'P', 'K', 1, 4}, null)).isNotNull();
        assertThatThrownBy(() -> decoder.decode(new byte[] {'P', 'X', 3, 4}, "application/zip"))
            .hasMessageContaining("does not contain");
        assertThatCode(() -> decoder.decode(new byte[] {1, 2, 3, 4}, " "))
            .doesNotThrowAnyException();

        for (String name : new String[] {
            "/results.sarif", "C:\\results.sarif", "file:results.sarif",
            "folder/../results.sarif", "a".repeat(257) + ".sarif", ""
        }) {
            assertThatThrownBy(() -> decoder.decode(zip(MapEntry.of(name, SARIF)), null))
                .hasMessageContaining("unsafe entry path");
        }
        assertThat(decoder.decode(zip(MapEntry.directory("reports/"), MapEntry.of("results.SARIF.JSON", SARIF)), null))
            .isEqualTo(SARIF);
    }

    private byte[] zip(MapEntry... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (MapEntry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                if (!entry.directory()) {
                    zip.write(entry.content().getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record MapEntry(String name, String content, boolean directory) {
        private static MapEntry of(String name, String content) {
            return new MapEntry(name, content, false);
        }

        private static MapEntry directory(String name) {
            return new MapEntry(name, "", true);
        }
    }
}
