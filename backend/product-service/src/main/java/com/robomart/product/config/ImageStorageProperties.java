package com.robomart.product.config;

import java.net.URI;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "robomart.product.images")
public class ImageStorageProperties {
    private String endpoint = "";
    @NotBlank private String region = "us-east-1";
    @NotBlank private String bucket = "robomart-images";
    @NotBlank @Size(max = 400) private String publicBaseUrl = "";
    private String accessKey = "";
    private String secretKey = "";
    private boolean pathStyle = false;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { endpoint = value; }
    public String getRegion() { return region; }
    public void setRegion(String value) { region = value; }
    public String getBucket() { return bucket; }
    public void setBucket(String value) { bucket = value; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String value) { publicBaseUrl = value; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String value) { accessKey = value; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String value) { secretKey = value; }
    public boolean isPathStyle() { return pathStyle; }
    public void setPathStyle(boolean value) { pathStyle = value; }

    @AssertTrue(message = "Both S3 access and secret keys must be provided, or use the default IAM credential chain")
    public boolean isCredentialPairValid() {
        return accessKey.isBlank() == secretKey.isBlank();
    }

    @AssertTrue(message = "S3 public base URL must be an absolute HTTP(S) URL without credentials, query or fragment")
    public boolean isPublicBaseUrlValid() {
        try {
            URI value = URI.create(publicBaseUrl);
            return ("http".equals(value.getScheme()) || "https".equals(value.getScheme()))
                    && value.getHost() != null && value.getUserInfo() == null
                    && value.getQuery() == null && value.getFragment() == null
                    && value.normalize().equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
