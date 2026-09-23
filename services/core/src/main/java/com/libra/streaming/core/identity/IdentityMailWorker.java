package com.libra.streaming.core.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "libra.identity.mail-worker-enabled", havingValue = "true", matchIfMissing = true)
public class IdentityMailWorker {
    private final IdentityMailQueue queue;
    private final IdentitySecrets secrets;
    private final ObjectMapper mapper;
    private final JavaMailSender sender;
    private final IdentityProperties properties;

    public IdentityMailWorker(IdentityMailQueue queue, IdentitySecrets secrets, ObjectMapper mapper,
            JavaMailSender sender, IdentityProperties properties) {
        this.queue = queue; this.secrets = secrets; this.mapper = mapper; this.sender = sender; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${libra.identity.mail-poll-ms:1000}")
    public void deliverBatch() {
        for (var claim : queue.claim()) {
            IdentityAccountService.MailPayload payload;
            try {
                payload = mapper.readValue(secrets.decrypt(claim.id(), claim.encryptedPayload()), IdentityAccountService.MailPayload.class);
            } catch (RuntimeException exception) {
                queue.failed(claim, true);
                continue;
            }
            if (!queue.stillValid(claim)) { continue; }
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.mailFrom());
            message.setTo(payload.recipient());
            message.setSubject(payload.subject());
            message.setText(payload.body());
            try {
                sender.send(message);
            } catch (org.springframework.mail.MailException exception) {
                queue.failed(claim, false);
                continue;
            }
            queue.complete(claim);
        }
    }
}
