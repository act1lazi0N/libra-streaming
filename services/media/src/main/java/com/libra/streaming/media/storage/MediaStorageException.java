package com.libra.streaming.media.storage;

import software.amazon.awssdk.services.s3.model.S3Exception;

/** No SDK cause: remote bodies, endpoints and request identities must not escape this boundary. */
final class MediaStorageException extends RuntimeException {
    private MediaStorageException(String code) {
        super(code);
    }

    static MediaStorageException from(RuntimeException failure) {
        return new MediaStorageException(failure instanceof S3Exception s3
                && (s3.statusCode() == 401 || s3.statusCode() == 403)
                ? "MEDIA_STORAGE_ACCESS_DENIED" : "MEDIA_STORAGE_UNAVAILABLE");
    }
}
