package com.example.examplemod;

import java.nio.charset.StandardCharsets;

/**
 * Appearance data for an OTHERWORLDER race citizen.
 *
 * <p>Unlike goblins or dwarves, an otherworlder's look isn't randomised
 * per-mob: each of Tensura's eight otherworlders is its OWN entity type with a
 * fixed skin (Kirara, Kyoya, Shogo, …). So the only thing to remember is WHICH
 * character this citizen is — its entity-type id — and the citizen renderer
 * builds a hidden copy of that exact type to draw.
 *
 * <p>Encoding: the id as UTF-8 bytes (e.g. {@code "tensura:kirara_mizutani"}).
 * An empty/garbled payload decodes to {@link #DEFAULT}.
 */
public record OtherworlderVariantData(String typeId) implements RaceVariantData {

    /** Fallback character when the stored id is missing or unknown. */
    public static final OtherworlderVariantData DEFAULT =
            new OtherworlderVariantData("tensura:kirara_mizutani");

    @Override
    public byte[] encode() {
        return typeId.getBytes(StandardCharsets.UTF_8);
    }

    public static OtherworlderVariantData decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return DEFAULT;
        String id = new String(bytes, StandardCharsets.UTF_8);
        return id.contains(":") ? new OtherworlderVariantData(id) : DEFAULT;
    }
}
