package edu.bu.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private boolean enabled;
    private boolean stubEnabled;
    private boolean openaiEnabled;
    private boolean questionsEnabled;
    private String provider = "";
    private String openAiModel = "gpt-5-mini";
    private String openAiBaseUrl = "https://api.openai.com/v1";
    private int openAiTimeoutSeconds = 60;
    private int openAiConnectTimeoutSeconds = 10;
    private String promptVersion = "award-summary-v2";
    private String questionPromptVersion = "award-question-v1";
    private boolean cacheEnabled;
    private int cacheMaxEntries = 250;
    private int maxRecords = 100;
    private int maxSerializedContextChars = 20_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(
            boolean enabled
    ) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public boolean isStubEnabled() {
        return stubEnabled;
    }

    public void setStubEnabled(
            boolean stubEnabled
    ) {
        this.stubEnabled = stubEnabled;
    }

    public boolean isOpenaiEnabled() {
        return openaiEnabled;
    }

    public boolean isQuestionsEnabled() {
        return questionsEnabled;
    }

    public void setQuestionsEnabled(
            boolean questionsEnabled
    ) {
        this.questionsEnabled = questionsEnabled;
    }

    public void setOpenaiEnabled(
            boolean openaiEnabled
    ) {
        this.openaiEnabled = openaiEnabled;
    }

    public void setProvider(
            String provider
    ) {
        this.provider = provider;
    }

    public String getOpenAiModel() {
        return openAiModel;
    }

    public void setOpenAiModel(
            String openAiModel
    ) {
        this.openAiModel = openAiModel;
    }

    public String getOpenAiBaseUrl() {
        return openAiBaseUrl;
    }

    public void setOpenAiBaseUrl(
            String openAiBaseUrl
    ) {
        this.openAiBaseUrl = openAiBaseUrl;
    }

    public int getOpenAiTimeoutSeconds() {
        return openAiTimeoutSeconds;
    }

    public void setOpenAiTimeoutSeconds(
            int openAiTimeoutSeconds
    ) {
        this.openAiTimeoutSeconds = openAiTimeoutSeconds;
    }

    public int getOpenAiConnectTimeoutSeconds() {
        return openAiConnectTimeoutSeconds;
    }

    public void setOpenAiConnectTimeoutSeconds(
            int openAiConnectTimeoutSeconds
    ) {
        this.openAiConnectTimeoutSeconds =
                openAiConnectTimeoutSeconds;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(
            String promptVersion
    ) {
        this.promptVersion = promptVersion;
    }

    public String getQuestionPromptVersion() {
        return questionPromptVersion;
    }

    public void setQuestionPromptVersion(
            String questionPromptVersion
    ) {
        this.questionPromptVersion = questionPromptVersion;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(
            boolean cacheEnabled
    ) {
        this.cacheEnabled = cacheEnabled;
    }

    public int getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    public void setCacheMaxEntries(
            int cacheMaxEntries
    ) {
        this.cacheMaxEntries = cacheMaxEntries;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(
            int maxRecords
    ) {
        this.maxRecords = maxRecords;
    }

    public int getMaxSerializedContextChars() {
        return maxSerializedContextChars;
    }

    public void setMaxSerializedContextChars(
            int maxSerializedContextChars
    ) {
        this.maxSerializedContextChars =
                maxSerializedContextChars;
    }
}
