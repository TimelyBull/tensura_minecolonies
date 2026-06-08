package com.example.examplemod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decoded per-citizen lizardman appearance.
 *
 * Holds the raw integer IDs Tensura's {@code LizardmanVariant.*} enums use
 * plus the three color ints, two booleans (bandage + flying), and the
 * evolution state/timer. Decoding into Tensura's enums happens on the
 * renderer side via {@code LizardmanVariant.byId(int)} — same pattern as
 * the orc / goblin variants.
 *
 * <p>Slimmer than {@link OrcVariantData} (18 bytes vs 26) because
 * lizardman has fewer accessory slots: no Bottom enum (color only),
 * no Necklace, no Neck colour, no Belt / Boots colours.
 *
 * <p>Wire / NBT format: fixed 18-byte little-endian layout. Legacy /
 * corrupt payloads fall back to {@link #DEFAULT}.
 *
 * <p>Evolution state is captured for round-trip completeness but forced to
 * {@code 0} on the render shadow — see {@code LizardmanCitizenRenderHandler}.
 * Lizardman evolution tiers (Dragonewt, TrueDragonewt, DivineDragon) are
 * Tensura ManasCore Race entries on the SAME entity type rather than
 * separate EntityTypes (unlike orc lord / orc disaster), so no upstream
 * blocking like {@link Races#isBlocked} is needed.
 *
 * <p>Lizardman variants per the {@code LizardmanEntity} decompile:
 * {@code variantId} (the skin / scale "type" enum), {@code hairId} +
 * colour, {@code topId} + colour, {@code bottomColor} only.
 */
public record LizardmanVariantData(
        int variantId,        // LizardmanVariant: skin/scale type (5 values per decompile)
        int hairId,           // LizardmanVariant.Hair enum id
        int hairColor,        // ARGB int
        int topId,            // LizardmanVariant.Top enum id
        int topColor,         // ARGB int
        int bottomColor,      // no Bottom enum — color only
        boolean bandage,      // optional accessory flag
        int evolutionState,   // 0 = base lizardman; higher tiers handled by Tensura race system
        int evolving          // active-evolution timer countdown
) implements RaceVariantData {

    /**
     * Wire layout (little-endian, fixed offsets):
     * <pre>
     *  0   variantId       byte
     *  1   hairId          byte
     *  2   topId           byte
     *  3   evolutionState  byte
     *  4   evolving        byte (clamped to [0,255] on encode)
     *  5   flags           byte (bit 0 = bandage)
     *  6   hairColor       int (4 bytes)
     *  10  topColor        int (4 bytes)
     *  14  bottomColor     int (4 bytes)
     * </pre>
     * Total: 18 bytes.
     */
    @Override
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) variantId);
        buf.put((byte) hairId);
        buf.put((byte) topId);
        buf.put((byte) evolutionState);
        buf.put((byte) Math.min(255, Math.max(0, evolving)));
        int flags = (bandage ? 1 : 0);
        buf.put((byte) flags);
        buf.putInt(hairColor);
        buf.putInt(topColor);
        buf.putInt(bottomColor);
        return buf.array();
    }

    public static LizardmanVariantData decode(byte[] bytes) {
        if (bytes.length < 18) {
            return DEFAULT;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int variantId      = buf.get();         // signed
        int hairId         = buf.get();
        int topId          = buf.get();
        int evolutionState = buf.get();
        int evolving       = buf.get() & 0xFF;  // unsigned — see encode clamp
        int flags          = buf.get() & 0xFF;
        boolean bandage    = (flags & 1) != 0;
        int hairColor      = buf.getInt();
        int topColor       = buf.getInt();
        int bottomColor    = buf.getInt();
        return new LizardmanVariantData(variantId, hairId, hairColor, topId, topColor,
                bottomColor, bandage, evolutionState, evolving);
    }

    /** Safe fallback. */
    public static final LizardmanVariantData DEFAULT =
            new LizardmanVariantData(0, 0, 0, 0, 0, 0, false, 0, 0);
}
