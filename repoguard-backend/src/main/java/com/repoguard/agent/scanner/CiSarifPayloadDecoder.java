package com.repoguard.agent.scanner;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        if (payload == null || payload.length == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF payload is required");
        }
        if (payload.length > MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "CI SARIF upload exceeds 10 MiB");
        }
        if (isZip(payload, contentType)) {
            return decodeZip(payload);
        }
        if (payload.length > MAX_SARIF_BYTES) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF JSON exceeds 2,000,000 bytes");
        }
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String decodeZip(byte[] payload) {
        int entryCount = 0;
        int totalBytes = 0;
        byte[] selected = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF zip contains too many entries");
                }
                String name = safeEntryName(entry.getName());
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    entryBytes += read;
                    totalBytes += read;
                    int ratioLimit = Math.max(MAX_SARIF_BYTES, payload.length * MAX_EXPANSION_RATIO);
                    if (entryBytes > MAX_SARIF_BYTES || totalBytes > MAX_UNCOMPRESSED_BYTES || totalBytes > ratioLimit) {
                        throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF zip expands beyond the configured budget");
                    }
                    output.write(buffer, 0, read);
                }
                if (isSarifCandidate(name)) {
                    if (selected != null) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF zip must contain exactly one SARIF document");
                    }
                    selected = output.toByteArray();
                }
                zip.closeEntry();
            }
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

    private boolean isZip(byte[] payload, String contentType) {
        boolean magic = payload.length >= 4
            && payload[0] == 'P' && payload[1] == 'K'
            && (payload[2] == 3 || payload[2] == 5 || payload[2] == 7)
            && (payload[3] == 4 || payload[3] == 6 || payload[3] == 8);
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
}
