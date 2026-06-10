package com.repoguard.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.repoguard.agent.mapper")
@EnableScheduling
@SpringBootApplication
public class RepoGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepoGuardApplication.class, args);
    }
}
