package com.mandi.onboarding;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mandi.firm.RequestFirmContext;

/**
 * The owner of the ACTIVE firm manages its employees. The firm is the one of the verified request context (session,
 * membership, account checked by FirmContextFilter), never a parameter; every operation additionally requires the OWNER
 * role in that firm (403 PERMISSION_DENIED for an employee). No endpoint here sets, shows or resets a password or a token.
 */
@RestController
@RequestMapping("/api/v1/firm")
public class FirmTeamController {

    public record InviteRequest(String email) {
    }

    private final OnboardingService onboarding;
    private final RequestFirmContext context;

    public FirmTeamController(OnboardingService onboarding, RequestFirmContext context) {
        this.onboarding = onboarding;
        this.context = context;
    }

    @GetMapping("/members")
    public List<Map<String, Object>> members() {
        return onboarding.members(context.current());
    }

    @GetMapping("/invitations")
    public List<Map<String, Object>> invitations() {
        return onboarding.pendingInvitations(context.current());
    }

    @PostMapping("/invitations")
    public ResponseEntity<OnboardingService.InvitationView> invite(@RequestBody(required = false) InviteRequest body) {
        if (body == null || body.email() == null) throw new OnboardingException(400, "INVALID_REQUEST");
        return ResponseEntity.status(202).body(onboarding.inviteEmployee(context.current(), body.email()));
    }

    @PostMapping("/invitations/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable long id) {
        onboarding.revokeInvitation(context.current(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable long id) {
        onboarding.setMemberActive(context.current(), id, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable long id) {
        onboarding.setMemberActive(context.current(), id, true);
        return ResponseEntity.noContent().build();
    }
}
