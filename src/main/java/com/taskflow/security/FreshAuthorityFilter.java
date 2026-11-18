package com.taskflow.security;

import com.taskflow.model.UserSecurity;
import com.taskflow.repository.UserSecurityRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * A session remembers what was true when the user logged in. This filter re-checks the database on every
 * request (two primary-key lookups) so that changes take effect immediately:
 *
 *  1. Roles: an admin who demotes someone has an immediate effect, and a deleted user's session ends.
 *  2. Revoked sessions: when a password is reset or changed, every session that started before that
 *     moment is ended (the user is logged out everywhere).
 *  3. Forced password change: after an admin reset the user may only change their password, look at
 *     /api/auth/me or log out. Everything else under /api gets 403 with code PASSWORD_CHANGE_REQUIRED.
 *
 * Not a @Component on purpose: it is added to the security chain in SecurityConfig, so Spring Boot
 * doesn't also register it as a plain servlet filter.
 */
public class FreshAuthorityFilter extends OncePerRequestFilter {

    /** Session attribute holding the moment this session was logged in (compared with sessions_valid_after). */
    private static final String ISSUED_AT = "taskflow.authIssuedAt";

    private static final String PASSWORD_CHANGE_REQUIRED_BODY = """
            {"type":"about:blank","title":"Forbidden","status":403,\
            "detail":"You must change your password before continuing. Call PUT /api/auth/password.",\
            "code":"PASSWORD_CHANGE_REQUIRED"}""";

    private final CustomUserDetailsService userDetailsService;
    private final UserSecurityRepository userSecurityRepository;

    public FreshAuthorityFilter(CustomUserDetailsService userDetailsService,
                                UserSecurityRepository userSecurityRepository) {
        this.userDetailsService = userDetailsService;
        this.userSecurityRepository = userSecurityRepository;
    }

    /** Call right after a login or a password change, so this session counts as newer than any reset. */
    public static void markSessionIssued(HttpServletRequest request) {
        request.getSession().setAttribute(ISSUED_AT, Instant.now());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Authentication current = SecurityContextHolder.getContext().getAuthentication();

        if (current != null && current.isAuthenticated() && !(current instanceof AnonymousAuthenticationToken)) {
            UserDetails fresh;
            try {
                fresh = userDetailsService.loadUserByUsername(current.getName());
            } catch (UsernameNotFoundException e) {
                endSession(request);
                chain.doFilter(request, response);
                return;
            }

            UserSecurity security = userSecurityRepository.findByUsername(current.getName()).orElse(null);
            if (isRevoked(request.getSession(false), security)) {
                endSession(request); // carries on as anonymous, so protected endpoints answer 401
                chain.doFilter(request, response);
                return;
            }

            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities()));

            if (security != null && security.isMustChangePassword() && !allowedWhilePasswordChangeRequired(request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/problem+json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(PASSWORD_CHANGE_REQUIRED_BODY);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * A session is revoked when it started before the user's sessions_valid_after. A session with no
     * recorded start can't be shown to be newer, so it counts as revoked too, but only for users who have a
     * security row (that is, who have had a reset or change), so existing sessions survive an upgrade.
     */
    private static boolean isRevoked(HttpSession session, UserSecurity security) {
        if (security == null) {
            return false;
        }
        Object issued = session == null ? null : session.getAttribute(ISSUED_AT);
        return !(issued instanceof Instant issuedAt) || issuedAt.isBefore(security.getSessionsValidAfter());
    }

    private static void endSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /** Only /api is locked down; Swagger UI and the docs stay reachable so the user can fix the password there. */
    private static boolean allowedWhilePasswordChangeRequired(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) {
            return true;
        }
        String method = request.getMethod();
        return (method.equals("PUT") && path.equals("/api/auth/password"))
                || (method.equals("GET") && (path.equals("/api/auth/me") || path.equals("/api/auth/csrf")))
                || (method.equals("POST") && (path.equals("/api/auth/logout")
                        || path.equals("/api/auth/login") || path.equals("/api/auth/register")));
    }
}
