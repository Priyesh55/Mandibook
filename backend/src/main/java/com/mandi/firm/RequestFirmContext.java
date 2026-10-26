package com.mandi.firm;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The {@link CurrentFirm} of the running application: the firm of the current web request as established by the
 * server-side session and verified against an ACTIVE membership on this very request. It replaces the old single configured
 * firm. There is no ambient firm: outside a verified request (a route that bypassed the filter, a background thread) the call
 * FAILS instead of defaulting to some firm.
 */
@Component
public class RequestFirmContext implements CurrentFirm {

    @Override
    public long id() {
        return current().firmId();
    }

    /** The verified context of the current request, or null outside one (never a default firm). */
    public static FirmContext currentOrNull() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object context = attributes == null ? null : attributes.getAttribute(FirmContext.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return context instanceof FirmContext c ? c : null;
    }

    public FirmContext current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object context = attributes == null ? null : attributes.getAttribute(FirmContext.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (context instanceof FirmContext c) {
            return c;
        }
        throw new IllegalStateException("No verified firm context for this request; firm-owned data is only reachable through a "
                + "request that passed the membership check");
    }
}
