package com.repoguard.agent.review.codeowners;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Caches parsed operator mappings only; callers must still read and verify the file on every query. */
public final class CodeownersMappingCache {
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    private final ObjectMapper json;
    private final LongSupplier clock;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(8, 0.75f, true);
    public CodeownersMappingCache(ObjectMapper json) { this(json, System::nanoTime); }
    CodeownersMappingCache(ObjectMapper json, LongSupplier clock) { this.json = json; this.clock = clock; }
    public synchronized CodeownersMappingCatalog.Scope resolve(byte[] document, long tenant, String repository) {
        if (document == null || document.length == 0 || document.length > 262144 || tenant < 1 || repository == null)
            return CodeownersMappingCatalog.resolve(document, tenant, repository, json);
        long now = clock.getAsLong();
        entries.values().removeIf(entry -> now - entry.created() >= TTL_NANOS);
        Key key = new Key(tenant, repository.toLowerCase(Locale.ROOT), fingerprint(document));
        Entry cached = entries.get(key);
        if (cached != null) return cached.scope();
        var scope = CodeownersMappingCatalog.resolve(document, tenant, repository, json);
        if ("CONFIGURED".equals(scope.status())) {
            // At most eight bounded documents' scoped mappings; errors and final recommendations are never cached.
            if (entries.size() == 8) entries.remove(entries.keySet().iterator().next());
            entries.put(key, new Entry(now, scope));
        }
        return scope;
    }
    private static String fingerprint(byte[] document) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private record Key(long tenant, String repository, String fingerprint) { }
    private record Entry(long created, CodeownersMappingCatalog.Scope scope) { }
}
