package com.example.examplemod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decoded per-citizen orc appearance (Stage 3).
 *
 * Holds the raw integer IDs Tensura's {@code OrcVariant.*} enums use plus
 * the five color ints, two booleans, and evolution-state. Decoding into
 * Tensura's enums happens on the renderer side via
 * {@code OrcVariant.byId(int)} — same pattern as the goblin variant.
 *
 * Wire / NBT format: fixed 26-byte little-endian layout. Pre-Stage-3
 * payloads (legacy or corrupted) fall back to {@link #DEFAULT}.
 *
 * Evolution state and the {@code evolving} timer are captured for
 * round-trip completeness but forced to {@code 0} on the render shadow —
 * see {@code OrcCitizenRenderHandler}. Orc lord and orc disaster are
 * separate Tensura entity types and are blocked from becoming citizens
 * upstream by {@link Races#isBlocked}.
 */
public record OrcVariantData(
        int variantId,       // OrcVariant: 0=HAM..5=ROYAL_LORD (the base "type")
        int neckId,          // OrcVariant.Neck: 0=EMPTY, 1=NECKWRAP, 2=SIDECAPE
        int neckColor,       // ARGB int; 0 = no tint (white)
        int topId,           // OrcVariant.Top: 0=SHIRT_LONG..2=SHIRT_SLEEVELESS
        int topColor,        //
        int bottomColor,     // no Bottom enum — geometry fixed, color only
        int beltColor,       // no Belt enum
        int bootsColor,      // no Boots enum
        boolean bandage,
        boolean necklace,
        int evolutionState,  // 0 for base orc; tier 1+ promotes to OrcLord (blocked)
        int evolving         // active-evolution timer countdown
) implements RaceVariantData {

    /** Wire layout (little-endian, fixed offsets):
     *  <pre>
     *  0   variantId       byte
     *  1   neckId          byte
     *  2   topId           byte
     *  3   evolutionState  byte
     *  4   evolving        byte (clamped to [0,255] on encode)
     *  5   flags           byte (bit 0 = bandage, bit 1 = necklace)
     *  6   neckColor       int (4 bytes)
     *  10  topColor        int
     *  14  bottomColor     int
     *  18  beltColor       int
     *  22  bootsColor      int
     *  </pre>
     *  Total: 26 bytes. Reads via {@code buf.get()} are signed — so the
     *  sentinel {@code -1} round-trips correctly through {@code byte}.
     */
    @Override
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) variantId);
        buf.put((byte) neckId);
        buf.put((byte) topId);
        buf.put((byte) evolutionState);
        // Clamp evolving to byte range — the timer rarely exceeds ~30 ticks
        // in Tensura's evolve flow, well under 256. If it ever exceeds we
        // saturate at 255 rather than truncating to a misleading value.
        buf.put((byte) Math.min(255, Math.max(0, evolving)));
        int flags = (bandage ? 1 : 0) | (necklace ? 2 : 0);
        buf.put((byte) flags);
        buf.putInt(neckColor);
        buf.putInt(topColor);
        buf.putInt(bottomColor);
        buf.putInt(beltColor);
        buf.putInt(bootsColor);
        return buf.array();
    }

    public static OrcVariantData decode(byte[] bytes) {
        if (bytes.length < 26) {
            // Legacy / corrupt payload — return a safe default. Picks the
            // first enum value for every kind (HAM, EMPTY neck, SHIRT_LONG),
            // zero colors, no bandage/necklace.
            return DEFAULT;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int variantId      = buf.get();        // signed
        int neckId         = buf.get();
        int topId          = buf.get();
        int evolutionState = buf.get();
        int evolving       = buf.get() & 0xFF; // unsigned — see encode clamp
        int flags          = buf.get() & 0xFF;
        boolean bandage    = (flags & 1) != 0;
        boolean necklace   = (flags & 2) != 0;
        int neckColor      = buf.getInt();
        int topColor       = buf.getInt();
        int bottomColor    = buf.getInt();
        int beltColor      = buf.getInt();
        int bootsColor     = buf.getInt();
        return new OrcVariantData(variantId, neckId, neckColor, topId, topColor,
                bottomColor, beltColor, bootsColor, bandage, necklace,
                evolutionState, evolving);
    }

    /** Safe fallback. */
    public static final OrcVariantData DEFAULT =
            new OrcVariantData(0, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0);
}
