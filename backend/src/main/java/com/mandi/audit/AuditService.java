package com.mandi.audit;

import com.mandi.shared.RequestIdFilter;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mandi.firm.CurrentFirm;
import com.mandi.shared.BusinessRuleException;

@Service
public class AuditService {

    /** The actor of events written before sign-in existed (and of repository-level tests with no request); never used for a signed-in request. */
    public static final String ANONYMOUS = "anonymous";
    private static final int MAX_EVENTS = 200;

    private final AuditEventRepository events;
    private final CurrentFirm firm;

    public AuditService(AuditEventRepository events, CurrentFirm firm) {
        this.events = events;
        this.firm = firm;
    }

    /**
     * Records an event. MANDATORY propagation: it can only run inside the transaction of the change it
     * describes, so a change and its history are saved together or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityType, Long entityId, String action, String details) {
        // The actor always comes from the server-side session context of THIS request, never from anything the client sent.
        com.mandi.firm.FirmContext who = com.mandi.firm.RequestFirmContext.currentOrNull();
        if (who == null || who.firmId() != firm.id()) {
            // no signed-in context for THIS firm (repository-level tests only): an actor is never attributed across firms
            events.save(new AuditEvent(firm.id(), entityType, entityId, action, details, ANONYMOUS));
            return;
        }
        Object request = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()
                .getAttribute(RequestIdFilter.ATTRIBUTE, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        events.save(new AuditEvent(firm.id(), entityType, entityId, action, details, who.userId(), who.membershipId(),
                who.role().name(), request instanceof java.util.UUID u ? u : null));
    }

    public record AuditEventResponse(Long id, String entityType, Long entityId, String action, String details,
                                     String actor, java.time.Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> history(String entityType, Long entityId) {
        if (entityType == null || entityType.isBlank() || entityId == null) {
            throw new BusinessRuleException("error.malformedRequest");
        }
        return events.findByFirmIdAndEntityTypeAndEntityIdOrderByIdDesc(firm.id(), entityType, entityId,
                        PageRequest.of(0, MAX_EVENTS))
                .stream()
                .map(e -> new AuditEventResponse(e.getId(), e.getEntityType(), e.getEntityId(), e.getAction(),
                        e.getDetails(), e.getActor(), e.getCreatedAt()))
                .toList();
    }
}
