package de.drremote.dsp408controller.runtime;

import java.util.UUID;

import de.drremote.dsp408.api.AutoLimitLeaseDto;

/**
 * Owns the short-lived client lease used to serialize Auto-Limit operations.
 *
 * <p>The manager deliberately contains no DSP I/O or job execution. Its only
 * responsibility is lease ownership, expiry and the public lease snapshot.</p>
 */
final class AutoLimitLeaseManager {
    static final long MAX_TTL_MS = 120_000L;

    private static final long DEFAULT_TTL_MS = 30_000L;
    private static final long MIN_TTL_MS = 5_000L;

    private String owner;
    private String token;
    private long expiresAtEpochMs;

    synchronized AutoLimitLeaseDto current() {
        clearIfExpired();
        return snapshot(false, null);
    }

    synchronized AutoLimitLeaseDto acquire(String requestedOwner, long ttlMs) {
        clearIfExpired();
        String normalizedOwner = requestedOwner == null ? "" : requestedOwner.trim();
        if (normalizedOwner.isBlank()) {
            throw new IllegalArgumentException("Auto-Limit owner is required.");
        }
        if (normalizedOwner.length() > 80) {
            normalizedOwner = normalizedOwner.substring(0, 80);
        }
        if (token != null) {
            return snapshot(false, "Auto-Limit is already running on " + owner + ".");
        }

        owner = normalizedOwner;
        token = UUID.randomUUID().toString();
        expiresAtEpochMs = System.currentTimeMillis() + normalizeTtl(ttlMs);
        return snapshot(true, "Auto-Limit lease acquired.");
    }

    synchronized AutoLimitLeaseDto renew(String requestedToken, long ttlMs) {
        clearIfExpired();
        if (!ownsInternal(requestedToken)) {
            return snapshot(false, "Auto-Limit lease is not owned by this client.");
        }
        expiresAtEpochMs = System.currentTimeMillis() + normalizeTtl(ttlMs);
        return snapshot(true, "Auto-Limit lease renewed.");
    }

    synchronized AutoLimitLeaseDto release(String requestedToken) {
        clearIfExpired();
        if (!ownsInternal(requestedToken)) {
            return snapshot(false, "Auto-Limit lease is not owned by this client.");
        }
        clear();
        return new AutoLimitLeaseDto(
                false, true, null, null, null, "Auto-Limit lease released."
        );
    }

    synchronized boolean owns(String requestedToken) {
        clearIfExpired();
        return ownsInternal(requestedToken);
    }

    private static long normalizeTtl(long ttlMs) {
        long requested = ttlMs <= 0L ? DEFAULT_TTL_MS : ttlMs;
        return Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, requested));
    }

    private void clearIfExpired() {
        if (token != null && System.currentTimeMillis() >= expiresAtEpochMs) {
            clear();
        }
    }

    private void clear() {
        owner = null;
        token = null;
        expiresAtEpochMs = 0L;
    }

    private boolean ownsInternal(String requestedToken) {
        return token != null
                && requestedToken != null
                && token.equals(requestedToken.trim());
    }

    private AutoLimitLeaseDto snapshot(boolean acquired, String message) {
        boolean active = token != null;
        return new AutoLimitLeaseDto(
                active,
                acquired,
                owner,
                acquired ? token : null,
                active ? expiresAtEpochMs : null,
                message == null
                        ? (active ? "Auto-Limit is running." : "Auto-Limit is available.")
                        : message
        );
    }
}
