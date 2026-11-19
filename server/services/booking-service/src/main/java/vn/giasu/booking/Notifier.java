package vn.giasu.booking;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.giasu.common.security.InternalClients;

/**
 * Sends web notifications through notification-service after the booking transaction commits.
 * shortcut: best effort, a notification is lost if notification-service is down; move to outbox + Kafka when timers depend on it.
 */
@Component
public class Notifier {
    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    public record Notice(UUID recipientId, String type, String title, String body, String refType, UUID refId) {}

    private final RestClient client;

    Notifier(InternalClients clients, @Value("${NOTIFICATION_SERVICE_URL:http://localhost:8085}") String baseUrl) {
        this.client = clients.create(baseUrl);
    }

    public void send(UUID recipientId, String type, String title, String body, String refType, UUID refId) {
        Notice notice = new Notice(recipientId, type, title, body, refType, refId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { post(notice); }
            });
        } else {
            post(notice);
        }
    }

    void post(Notice notice) {
        try {
            client.post().uri("/api/v1/notifications/internal").body(notice).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Notification {} for {} not delivered: {}", notice.type(), notice.refId(), e.getMessage());
        }
    }
}
