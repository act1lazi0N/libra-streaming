package com.libra.streaming.media.upload.infrastructure;

import com.libra.streaming.media.upload.application.CandidateBinding;
import com.libra.streaming.media.upload.application.UploadGrantSigner;
import com.libra.streaming.media.upload.application.UploadPersistence;
import com.libra.streaming.media.upload.application.UploadWorkflow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UploadWorkflowConfiguration {
    @Bean
    UploadWorkflow uploadWorkflow(UploadPersistence persistence, CandidateBinding binding, UploadGrantSigner signer) {
        return new UploadWorkflow(persistence, binding, signer);
    }
}
