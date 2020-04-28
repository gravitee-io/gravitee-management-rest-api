package io.gravitee.management.security.filter;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.GenericFilterBean;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * @author Jeoffrey HAEYAERT (jeoffrey.haeyaert at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CsrfIncludeFilter extends GenericFilterBean {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        HttpServletResponse httpResponse = (HttpServletResponse) response;
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        httpResponse.addHeader(csrfToken.getHeaderName(), csrfToken.getToken());

        chain.doFilter(request, response);
    }
}