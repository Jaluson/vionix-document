package com.vionix.backend.common.security;

import com.vionix.backend.common.api.ErrorCode;
import com.vionix.backend.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequirePermission permission = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (permission == null) {
            permission = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (permission == null) {
            return true;
        }
        AuthPrincipal principal = SecurityContext.require();
        if (!principal.hasPermission(permission.value())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return true;
    }
}
