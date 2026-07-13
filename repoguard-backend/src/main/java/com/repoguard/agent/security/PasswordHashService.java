package com.repoguard.agent.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordHashService {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final String dummyPasswordHash = passwordEncoder.encode("repoguard-dummy-password-value");

    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || !storedHash.startsWith("$2")) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, storedHash);
    }

    public boolean matchesOrDummy(String rawPassword, String storedHash) {
        boolean validStoredHash = storedHash != null && storedHash.startsWith("$2");
        boolean matches = passwordEncoder.matches(rawPassword == null ? "" : rawPassword, validStoredHash ? storedHash : dummyPasswordHash);
        return validStoredHash && matches;
    }
}
