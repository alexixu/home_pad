package com.alex.minimalhome;

/**
 * Observed speech is a guard on a completed model tool request, never an action trigger.
 * GPT-Live has no final-user-turn event. Keep all observed input for the session: neither
 * assistant speech nor completed queries establish a safe boundary for dropping a prefix.
 * A session permits at most one accepted control request; another requires explicit re-entry.
 */
final class VoiceIntentEvidence {
    private String input = "";
    private double lastStart = -1, lastEnd = -1;
    private long revision, updatedAt;
    private boolean consumed, invalidated;
    static final class Ticket {
        final long revision;
        final String utterance;
        final boolean preserveQuestions;
        final long expiresAtNanos;
        Ticket(long revision, String utterance, boolean preserveQuestions, long expiresAtNanos) {
            this.revision = revision; this.utterance = utterance; this.preserveQuestions = preserveQuestions;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    void observe(String role, String delta, double start, double end) {
        // Assistant backchannels can occur in the middle of a user's sentence. They
        // cannot clear, consume, or reauthorize any portion of the user's input.
        if (!"user".equals(role) || invalidated) return;
        if (delta == null || delta.isEmpty() || delta.length() > 512
                || !Double.isFinite(start) || !Double.isFinite(end)
                || start < 0 || end < start || end > 86400000
                || start < lastStart || end < lastEnd
                || input.length() + delta.length() > 512) {
            invalidate();
            return;
        }
        input += delta;
        lastStart = start; lastEnd = end;
        ++revision; updatedAt = System.nanoTime();
    }

    Ticket prepare(String utterance, String title, boolean target) {
        if (consumed || !matches(utterance)) return null;
        VoiceCommand parsed = VoiceCommand.parse(input, title);
        if (parsed.kind != VoiceCommand.Kind.SET_DEVICE || parsed.targetOn != target) return null;
        consumed = true;
        return new Ticket(revision, utterance, false, updatedAt + 30000000000L);
    }

    // Only call after the native device policy validates the full target, action and
    // value. This binds that validated request to actual speech, never model text alone.
    Ticket prepareValidated(String utterance) {
        if (consumed || !matches(utterance) || !normalizeCommand(input).equals(normalizeCommand(utterance))) return null;
        // Consuming an attempt is permanent until explicit session clear, including
        // when the later device read fails, a correction arrives, or the action is canceled.
        consumed = true;
        return new Ticket(revision, utterance, true, updatedAt + 30000000000L);
    }

    boolean stillMatches(Ticket ticket) {
        return ticket != null && ticket.revision == revision && matches(ticket.utterance)
                && (!ticket.preserveQuestions || normalizeCommand(input).equals(normalizeCommand(ticket.utterance)));
    }

    void invalidate() { invalidated = true; ++revision; }

    String rejectionCode() {
        if (invalidated) return "spoken_evidence_invalid";
        if (consumed) return "voice_session_write_used";
        return "spoken_intent_not_verified";
    }

    private boolean matches(String utterance) {
        return !invalidated && revision > 0 && System.nanoTime() - updatedAt <= 30000000000L
                && utterance != null && !normalize(input).isEmpty() && normalize(input).equals(normalize(utterance));
    }
    private static String normalize(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\u3000]+", "").replaceAll("[。.!！?？…]+$", "");
    }
    private static String normalizeCommand(String text) {
        // Unlike the legacy parser (which separately parses actual observed input),
        // generic policy validates the supplied utterance: never erase a question mark.
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\u3000]+", "").replaceAll("[。.!！…]+$", "");
    }
    void clear() {
        input = ""; consumed = invalidated = false; updatedAt = 0;
        lastStart = lastEnd = -1;
        // Keep ticket versions monotonic so an old session ticket cannot match a new
        // session's first identical utterance after clear().
        ++revision;
    }
}
