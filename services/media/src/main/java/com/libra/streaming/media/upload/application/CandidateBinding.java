package com.libra.streaming.media.upload.application;

import com.libra.streaming.media.upload.domain.UploadDescriptor;
/** Core remains authoritative for the current candidate binding. */
public interface CandidateBinding {
    void requireCurrent(UploadDescriptor command);
}
