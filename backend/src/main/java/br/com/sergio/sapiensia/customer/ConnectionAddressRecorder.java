package br.com.sergio.sapiensia.customer;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import org.springframework.stereotype.Component;

/**
 * Stores the real connection address of every request in a request attribute. It is a request listener, not a
 * filter, because the container runs listeners before every filter: Spring's ForwardedHeaderFilter (enabled by
 * server.forward-headers-strategy=framework) can otherwise rewrite getRemoteAddr() from a forged X-Forwarded-For
 * before anything reads it, and no filter order is strictly earlier than its Ordered.HIGHEST_PRECEDENCE.
 */
@Component
class ConnectionAddressRecorder implements ServletRequestListener {

    /** Request attribute holding the address of the connection, read before any header-based rewriting. */
    public static final String ATTRIBUTE = ConnectionAddressRecorder.class.getName() + ".CONNECTION_ADDRESS";

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        event.getServletRequest().setAttribute(ATTRIBUTE, event.getServletRequest().getRemoteAddr());
    }
}
