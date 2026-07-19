package com.repoguard.agent.credential;

/**
 * Application-neutral password hashing and verification port.
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String storedHash);

    boolean matchesOrDummy(String rawPassword, String storedHash);
}
