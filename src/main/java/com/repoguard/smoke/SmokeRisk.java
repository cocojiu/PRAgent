package com.repoguard.smoke;

public class SmokeRisk {

    public void printDebugMessage() {
        try {
            System.out.println("RepoGuard release smoke debug message");
        } catch (Exception exception) {
            System.out.println(exception.getMessage());
        }
    }
}
