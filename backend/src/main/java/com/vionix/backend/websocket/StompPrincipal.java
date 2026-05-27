package com.vionix.backend.websocket;

import com.vionix.backend.common.security.AuthPrincipal;

import java.security.Principal;

public record StompPrincipal(AuthPrincipal authPrincipal) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(authPrincipal.userId());
    }
}
