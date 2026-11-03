package com.donotmiss.backend.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's IP. Behind nginx every request would come from the proxy, so the server is started
 * with {@code server.forward-headers-strategy=native}: Tomcat then takes the address from
 * {@code X-Forwarded-For}, but only when the request comes from a private-network proxy. A client
 * on the internet cannot pick its own IP by sending that header.
 */
public final class ClientIp {
    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
