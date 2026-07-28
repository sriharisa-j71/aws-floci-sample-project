package com.example.regression.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "regression")
public class AppConfig {

    private Aws aws = new Aws();
    private List<String> suiteDirs = List.of("test-suites");
    private List<String> dataDirs = List.of("test-data");
    private int retentionDays = 90;

    public Aws getAws() { return aws; }
    public void setAws(Aws aws) { this.aws = aws; }

    public List<String> getSuiteDirs() { return suiteDirs; }
    public void setSuiteDirs(List<String> suiteDirs) { this.suiteDirs = suiteDirs; }

    public List<String> getDataDirs() { return dataDirs; }
    public void setDataDirs(List<String> dataDirs) { this.dataDirs = dataDirs; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public static class Aws {
        private String endpoint = "http://localhost:4566";
        private String region = "us-east-1";
        private boolean pathStyleAccess = true;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        public boolean isPathStyleAccess() { return pathStyleAccess; }
        public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
    }
}
