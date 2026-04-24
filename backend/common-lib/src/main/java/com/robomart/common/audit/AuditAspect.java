package com.robomart.common.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuditAspect {

    private final List<AuditEventListener> listeners;
    private final ExpressionParser spelParser = new SpelExpressionParser();

    public AuditAspect(List<AuditEventListener> listeners) {
        this.listeners = listeners;
    }

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        Object result = pjp.proceed();
        try {
            String actor = extractActor();
            String entityId = extractEntityId(auditable, result, pjp);
            String traceId = MDC.get("traceId");
            String correlationId = MDC.get("correlationId");
            AuditEvent event = new AuditEvent(actor, auditable.action(), auditable.entityType(),
                    entityId, Instant.now(), traceId, correlationId);
            for (AuditEventListener listener : listeners) {
                listener.onAuditEvent(event);
            }
        } catch (Exception e) {
            // Audit failure must never break the business operation
            LoggerFactory.getLogger(AuditAspect.class)
                    .warn("Audit event creation failed: {}", e.getMessage());
        }
        return result;
    }

    private String extractActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return "SYSTEM";
    }

    private String extractEntityId(Auditable auditable, Object result, ProceedingJoinPoint pjp) {
        if (result instanceof EntityIdProvider provider) {
            return provider.getEntityId();
        }
        String expr = auditable.entityIdExpression();
        if (expr != null && !expr.isBlank() && !"#result?.toString()".equals(expr)) {
            try {
                StandardEvaluationContext ctx = new StandardEvaluationContext();
                ctx.setVariable("result", result);
                MethodSignature sig = (MethodSignature) pjp.getSignature();
                String[] paramNames = sig.getParameterNames();
                Object[] args = pjp.getArgs();
                if (paramNames != null) {
                    for (int i = 0; i < paramNames.length; i++) {
                        ctx.setVariable(paramNames[i], args[i]);
                    }
                }
                Object value = spelParser.parseExpression(expr).getValue(ctx);
                return value != null ? value.toString() : "UNKNOWN";
            } catch (Exception e) {
                // fall through
            }
        }
        if (result != null) {
            String str = result.toString();
            return str.length() > 255 ? str.substring(0, 255) : str;
        }
        return "UNKNOWN";
    }
}
