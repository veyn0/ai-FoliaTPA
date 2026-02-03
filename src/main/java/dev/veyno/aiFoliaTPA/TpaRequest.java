package dev.veyno.aiFoliaTPA;

import java.time.Instant;
import java.util.UUID;

public record TpaRequest(UUID requester, UUID target, RequestType type, Instant createdAt) {
}
