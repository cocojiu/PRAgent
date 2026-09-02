package com.repoguard.agent.external;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.security.outbound")
public class OutboundEndpointProperties {

    private List<String> githubAllowedHosts = new ArrayList<>(List.of("api.github.com"));
    private List<String> gitlabAllowedHosts = new ArrayList<>(List.of("gitlab.com"));
    private List<String> giteeAllowedHosts = new ArrayList<>(List.of("gitee.com"));
    private List<String> bitbucketAllowedHosts = new ArrayList<>(List.of("api.bitbucket.org"));
    private List<String> llmAllowedHosts = new ArrayList<>(List.of(
        "dashscope.aliyuncs.com",
        "api.openai.com"
    ));
    private List<String> notificationAllowedHosts = new ArrayList<>(List.of(
        "qyapi.weixin.qq.com",
        "oapi.dingtalk.com"
    ));
    private List<String> infrastructureAllowedHosts = new ArrayList<>(List.of(
        "localhost",
        "127.0.0.1",
        "::1",
        "mysql",
        "rabbitmq"
    ));
    private List<String> privateNetworkAllowedHosts = new ArrayList<>(List.of(
        "localhost",
        "127.0.0.1",
        "::1",
        "mysql",
        "rabbitmq"
    ));
    private List<Integer> githubAllowedPorts = new ArrayList<>(List.of(443));
    private List<Integer> gitlabAllowedPorts = new ArrayList<>(List.of(443));
    private List<Integer> giteeAllowedPorts = new ArrayList<>(List.of(443));
    private List<Integer> bitbucketAllowedPorts = new ArrayList<>(List.of(443));
    private List<Integer> llmAllowedPorts = new ArrayList<>(List.of(443));
    private List<Integer> notificationAllowedPorts = new ArrayList<>(List.of(443));
    private List<Integer> mysqlAllowedPorts = new ArrayList<>(List.of(3306));
    private List<Integer> rabbitMqAllowedPorts = new ArrayList<>(List.of(5671, 5672));

    public List<String> getGithubAllowedHosts() {
        return githubAllowedHosts;
    }

    public void setGithubAllowedHosts(List<String> githubAllowedHosts) {
        this.githubAllowedHosts = copy(githubAllowedHosts);
    }

    public List<String> getGitlabAllowedHosts() {
        return gitlabAllowedHosts;
    }

    public void setGitlabAllowedHosts(List<String> gitlabAllowedHosts) {
        this.gitlabAllowedHosts = copy(gitlabAllowedHosts);
    }

    public List<String> getGiteeAllowedHosts() {
        return giteeAllowedHosts;
    }

    public void setGiteeAllowedHosts(List<String> giteeAllowedHosts) {
        this.giteeAllowedHosts = copy(giteeAllowedHosts);
    }

    public List<String> getBitbucketAllowedHosts() {
        return bitbucketAllowedHosts;
    }

    public void setBitbucketAllowedHosts(List<String> bitbucketAllowedHosts) {
        this.bitbucketAllowedHosts = copy(bitbucketAllowedHosts);
    }

    public List<String> getLlmAllowedHosts() {
        return llmAllowedHosts;
    }

    public void setLlmAllowedHosts(List<String> llmAllowedHosts) {
        this.llmAllowedHosts = copy(llmAllowedHosts);
    }

    public List<String> getNotificationAllowedHosts() {
        return notificationAllowedHosts;
    }

    public void setNotificationAllowedHosts(List<String> notificationAllowedHosts) {
        this.notificationAllowedHosts = copy(notificationAllowedHosts);
    }

    public List<String> getInfrastructureAllowedHosts() {
        return infrastructureAllowedHosts;
    }

    public void setInfrastructureAllowedHosts(List<String> infrastructureAllowedHosts) {
        this.infrastructureAllowedHosts = copy(infrastructureAllowedHosts);
    }

    public List<String> getPrivateNetworkAllowedHosts() {
        return privateNetworkAllowedHosts;
    }

    public void setPrivateNetworkAllowedHosts(List<String> privateNetworkAllowedHosts) {
        this.privateNetworkAllowedHosts = copy(privateNetworkAllowedHosts);
    }

    public List<Integer> getGithubAllowedPorts() {
        return githubAllowedPorts;
    }

    public void setGithubAllowedPorts(List<Integer> githubAllowedPorts) {
        this.githubAllowedPorts = copyIntegers(githubAllowedPorts);
    }

    public List<Integer> getGitlabAllowedPorts() {
        return gitlabAllowedPorts;
    }

    public void setGitlabAllowedPorts(List<Integer> gitlabAllowedPorts) {
        this.gitlabAllowedPorts = copyIntegers(gitlabAllowedPorts);
    }

    public List<Integer> getGiteeAllowedPorts() {
        return giteeAllowedPorts;
    }

    public void setGiteeAllowedPorts(List<Integer> giteeAllowedPorts) {
        this.giteeAllowedPorts = copyIntegers(giteeAllowedPorts);
    }

    public List<Integer> getBitbucketAllowedPorts() {
        return bitbucketAllowedPorts;
    }

    public void setBitbucketAllowedPorts(List<Integer> bitbucketAllowedPorts) {
        this.bitbucketAllowedPorts = copyIntegers(bitbucketAllowedPorts);
    }

    public List<Integer> getLlmAllowedPorts() {
        return llmAllowedPorts;
    }

    public void setLlmAllowedPorts(List<Integer> llmAllowedPorts) {
        this.llmAllowedPorts = copyIntegers(llmAllowedPorts);
    }

    public List<Integer> getNotificationAllowedPorts() {
        return notificationAllowedPorts;
    }

    public void setNotificationAllowedPorts(List<Integer> notificationAllowedPorts) {
        this.notificationAllowedPorts = copyIntegers(notificationAllowedPorts);
    }

    public List<Integer> getMysqlAllowedPorts() {
        return mysqlAllowedPorts;
    }

    public void setMysqlAllowedPorts(List<Integer> mysqlAllowedPorts) {
        this.mysqlAllowedPorts = copyIntegers(mysqlAllowedPorts);
    }

    public List<Integer> getRabbitMqAllowedPorts() {
        return rabbitMqAllowedPorts;
    }

    public void setRabbitMqAllowedPorts(List<Integer> rabbitMqAllowedPorts) {
        this.rabbitMqAllowedPorts = copyIntegers(rabbitMqAllowedPorts);
    }

    List<String> allowedHosts(OutboundEndpointType type) {
        return switch (type) {
            case GITHUB -> githubAllowedHosts;
            case GITLAB -> gitlabAllowedHosts;
            case GITEE -> giteeAllowedHosts;
            case BITBUCKET -> bitbucketAllowedHosts;
            case LLM -> llmAllowedHosts;
            case NOTIFICATION -> notificationAllowedHosts;
            case MYSQL, RABBITMQ -> infrastructureAllowedHosts;
        };
    }

    List<Integer> allowedPorts(OutboundEndpointType type) {
        return switch (type) {
            case GITHUB -> githubAllowedPorts;
            case GITLAB -> gitlabAllowedPorts;
            case GITEE -> giteeAllowedPorts;
            case BITBUCKET -> bitbucketAllowedPorts;
            case LLM -> llmAllowedPorts;
            case NOTIFICATION -> notificationAllowedPorts;
            case MYSQL -> mysqlAllowedPorts;
            case RABBITMQ -> rabbitMqAllowedPorts;
        };
    }

    private List<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private List<Integer> copyIntegers(List<Integer> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
