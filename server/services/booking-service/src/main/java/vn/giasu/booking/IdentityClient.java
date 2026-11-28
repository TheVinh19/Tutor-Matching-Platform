package vn.giasu.booking;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.giasu.common.security.InternalClients;
import vn.giasu.common.web.ApiException;

/** Reads an account's contact phone from identity-service (KN01: shared only for confirmed plans). */
@Component
public class IdentityClient {
    record Contact(UUID accountId, String phone) {}

    private final RestClient client;

    IdentityClient(InternalClients clients, @Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String baseUrl) {
        this.client = clients.create(baseUrl);
    }

    /** Phone of the account, or null if none is set or the account is gone. */
    public String phone(UUID accountId) {
        try {
            Contact contact = client.get().uri("/api/v1/identity/internal/accounts/{id}/contact", accountId).retrieve().body(Contact.class);
            return contact == null ? null : contact.phone();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_SERVICE_UNAVAILABLE", "Dịch vụ tài khoản tạm thời không phản hồi");
        }
    }
}
