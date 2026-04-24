package com.robomart.common.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditAspectTest {

    @Mock
    private AuditEventListener listener;

    @Mock
    private ProceedingJoinPoint pjp;

    private AuditAspect auditAspect;

    @BeforeEach
    void setUp() {
        auditAspect = new AuditAspect(List.of(listener));
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldCreateAuditEventWhenAuditableMethodSucceeds() throws Throwable {
        Auditable auditable = mock(Auditable.class);
        when(auditable.action()).thenReturn(AuditAction.CREATE);
        when(auditable.entityType()).thenReturn("Product");
        when(auditable.entityIdExpression()).thenReturn("#result?.id?.toString()");
        when(pjp.proceed()).thenReturn("42");

        Object result = auditAspect.audit(pjp, auditable);

        assertThat(result).isEqualTo("42");
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(listener).onAuditEvent(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.action()).isEqualTo(AuditAction.CREATE);
        assertThat(event.entityType()).isEqualTo("Product");
        assertThat(event.actor()).isEqualTo("SYSTEM");
    }

    @Test
    void shouldNotCreateAuditEventWhenMethodThrowsException() throws Throwable {
        Auditable auditable = mock(Auditable.class);
        when(pjp.proceed()).thenThrow(new RuntimeException("business error"));

        assertThatThrownBy(() -> auditAspect.audit(pjp, auditable))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("business error");

        verify(listener, never()).onAuditEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldUseSystemActorWhenNoSecurityContext() throws Throwable {
        SecurityContextHolder.clearContext();
        Auditable auditable = mock(Auditable.class);
        when(auditable.action()).thenReturn(AuditAction.UPDATE);
        when(auditable.entityType()).thenReturn("Order");
        when(auditable.entityIdExpression()).thenReturn("#result?.toString()");
        when(pjp.proceed()).thenReturn("order-123");

        auditAspect.audit(pjp, auditable);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(listener).onAuditEvent(captor.capture());
        assertThat(captor.getValue().actor()).isEqualTo("SYSTEM");
    }

    @Test
    void shouldUseAuthenticatedUserAsActor() throws Throwable {
        var auth = new UsernamePasswordAuthenticationToken("user@example.com", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        Auditable auditable = mock(Auditable.class);
        when(auditable.action()).thenReturn(AuditAction.DELETE);
        when(auditable.entityType()).thenReturn("Product");
        when(auditable.entityIdExpression()).thenReturn("#result?.toString()");
        when(pjp.proceed()).thenReturn("99");

        auditAspect.audit(pjp, auditable);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(listener).onAuditEvent(captor.capture());
        assertThat(captor.getValue().actor()).isEqualTo("user@example.com");

        SecurityContextHolder.clearContext();
    }
}
