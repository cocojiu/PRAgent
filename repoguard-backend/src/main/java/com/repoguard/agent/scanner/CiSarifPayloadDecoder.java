package com.repoguard.agent.scanner;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Bounded decoder for raw SARIF JSON and CI-produced SARIF zip artifacts. */
@Component
public class CiSarifPayloadDecoder {

    public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    public static final int MAX_SARIF_BYTES = 2_000_000;
    private static final int MAX_ZIP_ENTRIES = 20;
    private static final int MAX_UNCOMPRESSED_BYTES = 8_000_000;
    private static final int MAX_EXPANSION_RATIO = 10;

    public String decode(byte[] payload, String contentType) {
        return decode(
            payload == null ? null : new ByteArrayInputStream(payload),
            payload == null ? -1 : payload.length,
            contentType
        );
    }

    /**
     * Decodes directly from the request stream. The stream is consumed only up to the configured
     * compressed/raw budgets; callers do not need to buffer an entire CI artifact first.
     */
    public String decode(InputStream payload, long contentLength, String contentType) {
        if (payload == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF payload is required");
        }
        if (contentLength > MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "CI SARIF upload exceeds 10 MiB");
        }
        try {
            byte[] prefix = payload.readNBytes(4);
            if (prefix.length == 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF payload is required");
            }
            InputStream combined = new SequenceInputStream(new ByteArrayInputStream(prefix), payload);
            if (isZip(prefix, contentType)) {
                return decodeZip(combined);
            }
            if (contentLength > MAX_SARIF_BYTES) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF JSON exceeds 2,000,000 bytes");
            }
            byte[] raw = combined.readNBytes(MAX_SARIF_BYTES + 1);
            if (raw.length == 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF payload is required");
            }
            if (raw.length > MAX_SARIF_BYTES) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF JSON exceeds 2,000,000 bytes");
            }
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unable to read SARIF payload");
        }
    }

    private String decodeZip(InputStream payload) {
        int entryCount = 0;
        int totalBytes = 0;
        byte[] selected = null;
        CountingInputStream compressed = new CountingInputStream(payload);
        try (ZipInputStream zip = new ZipInputStream(compressed)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ensureCompressedBudget(compressed);
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF zip contains too many entries");
                }
                String name = safeEntryName(entry.getName());
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                boolean candidate = isSarifCandidate(name);
                ByteArrayOutputStream output = candidate ? new ByteArrayOutputStream() : null;
                byte[] buffer = new byte[8192];
                int entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    entryBytes += read;
                    totalBytes += read;
                    ensureCompressedBudget(compressed);
                    long ratioLimit = Math.max((long) MAX_SARIF_BYTES, compressed.count() * MAX_EXPANSION_RATIO);
                    if (entryBytes > MAX_SARIF_BYTES || totalBytes > MAX_UNCOMPRESSED_BYTES || totalBytes > ratioLimit) {
                        throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF zip expands beyond the configured budget");
                    }
                    if (output != null) {
                        output.write(buffer, 0, read);
                    }
                }
                if (candidate) {
                    if (selected != null) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF zip must contain exactly one SARIF document");
                    }
                    selected = output == null ? null : output.toByteArray();
                }
                zip.closeEntry();
            }
            ensureCompressedBudget(compressed);
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid SARIF zip payload");
        }
        if (selected == null || selected.length == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF zip does not contain a .sarif or .json document");
        }
        return new String(selected, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void ensureCompressedBudget(CountingInputStream compressed) {
        if (compressed.count() > MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "CI SARIF upload exceeds 10 MiB");
        }
    }

    private boolean isZip(byte[] prefix, String contentType) {
        boolean magic = prefix.length >= 4
            && prefix[0] == 'P' && prefix[1] == 'K'
            && (prefix[2] == 3 || prefix[2] == 5 || prefix[2] == 7)
            && (prefix[3] == 4 || prefix[3] == 6 || prefix[3] == 8);
        return magic || (StringUtils.hasText(contentType)
            && contentType.toLowerCase(Locale.ROOT).contains("zip"));
    }

    private String safeEntryName(String value) {
        String name = value == null ? "" : value.trim().replace('\\', '/');
        if (name.isBlank() || name.length() > 256 || name.startsWith("/")
            || name.matches("^[A-Za-z]:/.*") || name.startsWith("file:")
            || java.util.Arrays.stream(name.split("/", -1)).anyMatch(".."::equals)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF zip contains an unsafe entry path");
        }
        return name;
    }

    private boolean isSarifCandidate(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sarif") || lower.endsWith(".sarif.json") || lower.endsWith(".json");
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        private CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        private long count() {
            return count;
        }
    }
}
