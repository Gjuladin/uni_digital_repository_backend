/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.hibernate.SessionFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Restricts locally authenticated users with an administrator-issued temporary password to the endpoints required to
 * inspect their authentication state, log out, and choose a new password. External authentication methods are never
 * restricted by this filter.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    private static final String AUTHENTICATION_METHOD_PASSWORD = "password";
    private static final String SELF_PASSWORD_CHANGE_PATH = "/api/eperson/account-management/me/password";

    private final SessionFactory sessionFactory;

    public PasswordChangeRequiredFilter() {
        this(null);
    }

    public PasswordChangeRequiredFilter(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        Context context = ContextUtil.obtainContext(request);
        EPerson currentUser = context.getCurrentUser();

        // EPerson uses a non-strict second-level cache. A request arriving just
        // after the forced-password transaction commits can therefore receive
        // the old flag even though PostgreSQL already contains false. Refresh
        // a flagged principal from the database before denying the request.
        // This also replaces the Context's attached instance, ensuring a
        // subsequent profile/EULA patch cannot write the stale flag back.
        if (currentUser != null && currentUser.isPasswordChangeRequired() && sessionFactory != null) {
            try {
                sessionFactory.getCache().evictEntityData(EPerson.class, currentUser.getID());
                context.uncacheEntity(currentUser);
                currentUser = EPersonServiceFactory.getInstance().getEPersonService()
                    .find(context, currentUser.getID());
                context.setCurrentUser(currentUser);
            } catch (java.sql.SQLException e) {
                throw new ServletException("Unable to refresh the current EPerson password-change state", e);
            }
        }

        if (currentUser != null
            && currentUser.isPasswordChangeRequired()
            && AUTHENTICATION_METHOD_PASSWORD.equals(context.getAuthenticationMethod())
            && !isAllowed(request, currentUser)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"" + PASSWORD_CHANGE_REQUIRED + "\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    boolean isAllowed(HttpServletRequest request, EPerson currentUser) {
        String requestUri = request.getRequestURI();
        String contextPath = StringUtils.defaultString(request.getContextPath());
        String path = requestUri.startsWith(contextPath) ? requestUri.substring(contextPath.length()) : requestUri;
        String method = request.getMethod();
        String currentEPersonPath = currentUser == null
            ? null
            : "/api/eperson/epersons/" + currentUser.getID();
        return HttpMethod.OPTIONS.matches(method)
            || HttpMethod.HEAD.matches(method)
            || "/api".equals(path)
            || "/api/".equals(path)
            || ("/api/security/csrf".equals(path) && HttpMethod.GET.matches(method))
            || ("/api/authn/status".equals(path) && HttpMethod.GET.matches(method))
            // Authentication status links to the current EPerson. Angular must
            // read it to discover passwordChangeRequired and finish login.
            || (StringUtils.equals(path, currentEPersonPath) && HttpMethod.GET.matches(method))
            || ("/api/authn/logout".equals(path) && HttpMethod.POST.matches(method))
            || (SELF_PASSWORD_CHANGE_PATH.equals(path) && HttpMethod.POST.matches(method));
    }
}
