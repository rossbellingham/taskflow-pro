package com.taskflow.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * CSRF handling for JavaScript clients (Swagger UI now, page scripts later).
 *
 * The token lives in a readable XSRF-TOKEN cookie. A script copies it into the X-XSRF-TOKEN header on
 * every POST/PUT/DELETE, and the server checks that the header matches the cookie. A different website
 * can't read our cookie, so it can't forge that header.
 *
 * This is the recipe from the Spring Security reference for single-page apps: the header carries the raw
 * cookie value, while form posts (e.g. Thymeleaf pages later) keep Spring's BREACH-protected token.
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.xor.handle(request, response, csrfToken);
        // The token is loaded lazily. Reading it here makes sure the XSRF-TOKEN cookie is
        // written on the very first response, so a client always has a token to send back.
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? this.plain : this.xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
