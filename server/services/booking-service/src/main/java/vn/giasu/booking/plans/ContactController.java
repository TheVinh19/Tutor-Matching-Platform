package vn.giasu.booking.plans;

import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vn.giasu.booking.IdentityClient;
import vn.giasu.booking.plans.Plans.Plan;

/**
 * KN01 (revised): no in-app chat. The two parties get each other's phone only once the plan is confirmed,
 * so contact details are not handed out before the booking goes through the platform (CL01).
 */
@RestController
class ContactController {
    record PlanContact(String learnerPhone, String tutorPhone) {}

    private static final Set<String> SHARED = Set.of("CONFIRMED", "COMPLETED");

    private final Plans plans;
    private final IdentityClient identity;

    ContactController(Plans plans, IdentityClient identity) {
        this.plans = plans;
        this.identity = identity;
    }

    @GetMapping("/api/v1/bookings/plans/{id}/contact")
    PlanContact contact(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Plan plan = plans.get(id);
        Plans.requireParticipantOrAdmin(jwt, plan.learnerId(), plan.tutorId());
        Plans.requireStatus(SHARED.contains(plan.status()), "Chỉ xem được số điện thoại khi kế hoạch đã chốt");
        return new PlanContact(identity.phone(plan.learnerId()), identity.phone(plan.tutorId()));
    }
}
