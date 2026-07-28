package edu.bu.archive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private boolean enabled;
    private boolean stubEnabled;
    private String provider = "";
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

    public void setProvider(
            String provider
    ) {
        this.provider = provider;
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
