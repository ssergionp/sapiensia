package br.com.sergio.sapiensia.audit;

import java.util.UUID;

/**
 * One audit event. There is no free text on purpose: only enums, ids and the client IP that ClientIpResolver already
 * validated, so e-mails, codes or tokens cannot reach the trail. Actor, object and origin may be null (a wrong
 * password identifies nobody).
 */
public record AuditEntry(
        AuditAction action, AuditResult result, UUID actorId, AuditObject objectType, UUID objectId, String origin) {}
