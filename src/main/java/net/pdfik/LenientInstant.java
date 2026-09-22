package net.pdfik;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Reads the API's timestamps into {@link Instant} whatever their zone spelling.
 *
 * <p>Until 2026-09-21 the API printed {@code created_at} / {@code finished_at} with no
 * offset at all ({@code 2026-09-21T11:56:56.932000}); the default {@code Instant}
 * deserializer rejects that, so every {@code getJob} / {@code waitForJob} failed with
 * "Failed to parse response body". The API now sends UTC with {@code Z}; this keeps the
 * SDK working against a server that does not, reading a zone-less value as UTC (which
 * is what the API always meant).</p>
 */
final class LenientInstant extends JsonDeserializer<Instant> {
    static SimpleModule module() {
        return new SimpleModule("PdfikLenientInstant").addDeserializer(Instant.class, new LenientInstant());
    }

    @Override
    public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null || text.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (RuntimeException withoutOffset) {
            try {
                return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
            } catch (RuntimeException e) {
                return (Instant) ctxt.handleWeirdStringValue(Instant.class, text, "not an ISO-8601 timestamp");
            }
        }
    }
}
