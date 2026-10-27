package com.robomart.common.audit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = {"org.aspectj.lang.annotation.Aspect", "org.springframework.security.core.context.SecurityContextHolder"})
public class AuditAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AuditAspect auditAspect(ObjectProvider<AuditEventListener> listeners) {
        return new AuditAspect(listeners.orderedStream().toList());
    }
}
