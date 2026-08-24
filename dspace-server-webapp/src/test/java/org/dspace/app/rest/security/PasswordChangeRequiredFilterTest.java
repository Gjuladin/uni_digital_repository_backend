/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.dspace.eperson.EPerson;
import org.junit.Test;

/**
 * Unit tests for the endpoint allowlist used while a temporary password is active.
 */
public class PasswordChangeRequiredFilterTest {

    private static final UUID CURRENT_USER_ID = UUID.fromString("b14d60cf-8276-4ef5-bc32-d7edef64da26");

    private final PasswordChangeRequiredFilter filter = new PasswordChangeRequiredFilter();

    @Test
    public void allowsPasswordChangeAndPrerequisiteEndpoints() {
        assertTrue(isAllowed("GET", "/api/security/csrf"));
        assertTrue(isAllowed("GET", "/api/authn/status"));
        assertTrue(isAllowed("POST", "/api/authn/logout"));
        assertTrue(isAllowed("POST", "/api/eperson/account-management/me/password"));
        assertTrue(isAllowed("GET", "/api/eperson/epersons/" + CURRENT_USER_ID));
        assertTrue(isAllowed("OPTIONS", "/api/eperson/account-management/me/password"));
        assertTrue(isAllowed("HEAD", "/api/eperson/account-management/me/password"));
        assertTrue(isAllowed("POST", "/server/api/eperson/account-management/me/password", "/server"));
    }

    @Test
    public void blocksOtherApplicationEndpointsAndUnsafeMethodVariants() {
        assertFalse(isAllowed("GET", "/api/eperson/account-management/me/password"));
        assertFalse(isAllowed("POST", "/api/authn/status"));
        assertFalse(isAllowed("GET", "/api/eperson/epersons/00000000-0000-0000-0000-000000000001"));
        assertFalse(isAllowed("PUT", "/api/eperson/epersons/" + CURRENT_USER_ID));
        assertFalse(isAllowed("GET", "/api/core/items"));
    }

    private boolean isAllowed(String method, String path) {
        return isAllowed(method, path, "");
    }

    private boolean isAllowed(String method, String path, String contextPath) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getContextPath()).thenReturn(contextPath);
        EPerson currentUser = mock(EPerson.class);
        when(currentUser.getID()).thenReturn(CURRENT_USER_ID);
        return filter.isAllowed(request, currentUser);
    }
}
