package com.taskflow.security;

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

/**
 * A session remembers the roles the user had when they logged in. Without this filter, an admin who
 * demotes someone (or deletes them) would have no effect until that person's session ended.
 *
 * On every request this reloads the user from the database and swaps in their current roles. If the
 * user no longer exists, the session is ended. It costs one primary-key lookup per request.
 *
 * Not a @Component on purpose: it is added to the security chain in SecurityConfig, so Spring Boot
 * doesn't also register it as a plain servlet filter.
 */
public class FreshAuthorityFilter extends OncePerRequestFilter {

    private final CustomUserDetailsService userDetailsService;

    public FreshAuthorityFilter(CustomUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Authentication current = SecurityContextHolder.getContext().getAuthentication();

        if (current != null && current.isAuthenticated() && !(current instanceof AnonymousAuthenticationToken)) {
            try {
                UserDetails fresh = userDetailsService.loadUserByUsername(current.getName());
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities()));
            } catch (UsernameNotFoundException e) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            }
        }

        chain.doFilter(request, response);
    }
}
