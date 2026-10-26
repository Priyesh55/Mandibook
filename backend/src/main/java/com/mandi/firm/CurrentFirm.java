package com.mandi.firm;

/**
 * The firm on whose behalf the current request runs. EVERY query and every id lookup is scoped by it.
 *
 * It is resolved on the server, never from the request (not from a header, URL or body), so a caller cannot
 * switch firm by editing a request: the implementation {@link RequestFirmContext} takes the firm from the signed-in
 * session and has verified an ACTIVE membership, the firm and the owner's account on this very request
 * ({@code com.mandi.security.FirmContextFilter}).
 * See docs/FIRM_ISOLATION.md.
 */
public interface CurrentFirm {

    long id();
}
