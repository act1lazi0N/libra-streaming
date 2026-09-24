package com.libra.streaming.media.storage;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "libra.media.storage")
public record MediaStorageProperties(String internalEndpoint, String browserEndpoint, String browserOrigin,
        String region, String accessKey, String secretKey, String sourceBucket, String hlsBucket,
        String stagingPrefix, String sourcePrefix, String hlsPrefix, Duration connectTimeout,
        Duration requestTimeout, long maxObjectBytes, Path scratchDirectory, boolean allowHttp,
        boolean initializeBuckets, boolean probeOnStartup) {
    public MediaStorageProperties {
        endpoint(internalEndpoint, allowHttp, "internal endpoint");
        endpoint(browserEndpoint, allowHttp, "browser endpoint");
        origin(browserOrigin, allowHttp);
        required(region, "region");
        required(accessKey, "access key");
        required(secretKey, "secret key");
        if (!accessKey.matches("[A-Za-z0-9_+=/-]{3,128}")) { throw invalid("access key is malformed"); }
        if (!secretKey.matches("[A-Za-z0-9_+=/-]{16,256}")) { throw invalid("secret key is malformed"); }
        if (accessKey.equals(secretKey)) { throw invalid("access and secret keys must differ"); }
        bucket(sourceBucket, "source bucket");
        bucket(hlsBucket, "HLS bucket");
        if (sourceBucket.equals(hlsBucket)) { throw invalid("source and HLS buckets must differ"); }
        prefix(stagingPrefix, "staging prefix");
        prefix(sourcePrefix, "source prefix");
        prefix(hlsPrefix, "HLS prefix");
        if (stagingPrefix.equals(sourcePrefix) || stagingPrefix.startsWith(sourcePrefix)
                || sourcePrefix.startsWith(stagingPrefix)) {
            throw invalid("source prefixes must not overlap");
        }
        if (connectTimeout == null || connectTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw invalid("connect timeout must be 1-30 seconds");
        }
        if (requestTimeout == null || requestTimeout.compareTo(connectTimeout) < 0
                || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw invalid("request timeout must be at least connect timeout and at most 5 minutes");
        }
        if (maxObjectBytes < 1 || maxObjectBytes > 268435456) {
            throw invalid("max object bytes must be 1-268435456");
        }
        if (scratchDirectory == null || !scratchDirectory.isAbsolute()) {
            throw invalid("scratch directory must be absolute");
        }
    }

    // Bind strings first: the generic URI converter can include raw credentials in parse errors.
    private static URI endpoint(String configured, boolean allowHttp, String label) {
        String message = label + " must be an allowed HTTP(S) origin without path or credentials";
        URI value;
        try { value = configured == null ? null : URI.create(configured); }
        catch (IllegalArgumentException failure) { throw invalid(message); }
        if (value == null || value.getHost() == null || value.getUserInfo() != null
                || value.getPort() == 0 || value.getPort() > 65535
                || value.getQuery() != null || value.getFragment() != null
                || !(value.getPath().isEmpty() || value.getPath().equals("/"))
                || !("https".equals(value.getScheme()) || (allowHttp && "http".equals(value.getScheme())))) {
            throw invalid(message);
        }
        return value;
    }

    public URI internalEndpointUri() { return URI.create(internalEndpoint); }

    private static void origin(String configured, boolean allowHttp) {
        URI value = endpoint(configured, allowHttp, "browser origin");
        if (!value.getPath().isEmpty()) { throw invalid("browser origin must not have a path"); }
        if (value.getPort() < 0 && value.getScheme().equals("http")) {
            throw invalid("browser origin must include an explicit HTTP port");
        }
    }

    private static void required(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw invalid(label + " is required");
        }
    }

    private static void bucket(String value, String label) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || value.contains("..")) { throw invalid(label + " is invalid"); }
    }

    private static void prefix(String value, String label) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9/_-]*/") || value.contains("//")) {
            throw invalid(label + " is invalid");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Media storage configuration: " + message);
    }

    @Override
    public String toString() {
        return "MediaStorageProperties[credentials=REDACTED]";
    }
}
