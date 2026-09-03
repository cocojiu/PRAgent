package com.repoguard.agent.scm;

public record ScmRepositoryRef(String namespace, String repository) {

    public ScmRepositoryRef {
        namespace = required(namespace, "namespace");
        repository = required(repository, "repository");
    }

    public String fullName() {
        return namespace + "/" + repository;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
