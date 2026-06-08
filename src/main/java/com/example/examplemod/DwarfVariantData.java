package com.example.examplemod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decoded per-citizen dwarf appearance.
 *
 * <p>Richest variant set of any race we ship — 9 enum-id fields (Gender,
 * Skin, Face, Scar, Hair, FacialHair, Top, Bottom, Feet) and 4 colour
 * ints (hair, top, bottom, feet). Total wire size: 25 bytes, fixed
 * little-endian layout.
 *
 * <p>Notes vs other races:
 * <ul>
 *   <li>No bandage flag — dwarves don't have that accessory.</li>
 *   <li>No evolution state — dwarf tier evolutions (Enlightened,
 *       Saint, Divine) are Tensura ManasCore Race entries on the same
 *       EntityType rather than separate states on the entity. We render
 *       base-form only at the citizen pipeline.</li>
 *   <li>{@code scar} is a raw int on the entity (not enum-backed), so
 *       it round-trips as-is with no enum-id lookup needed.</li>
 * </ul>
 *
 * <p>Legacy / corrupt payloads fall back to {@link #DEFAULT}.
 */
public record DwarfVariantData(
        int gender,          // DwarfVariant.Gender enum id
        int skin,            // DwarfVariant.Skin enum id
        int face,            // DwarfVariant.Face enum id
        int scar,            // raw int (no enum)
        int hair,            // DwarfVariant.Hair enum id
        int facialHair,      // DwarfVariant.FacialHair enum id
        int top,             // DwarfVariant.Top enum id
        int bottom,          // DwarfVariant.Bottom enum id
        int feet,            // DwarfVariant.Feet enum id
        int hairColor,       // ARGB int
        int topColor,        // ARGB int
        int bottomColor,     // ARGB int
        int feetColor,       // ARGB int
        float scale          // Tensura.DwarfEntity randomises SCALE per dwarf:
                             // royal guard = 1.0, others = 0.7 + rand³ × 0.3
                             // (~0.7-1.0 biased low). Captured at send so each
                             // citizen dwarf keeps the size of the wild dwarf
                             // it was named from.
) implements RaceVariantData {

    /**
     * Wire layout (little-endian, fixed offsets):
     * <pre>
     *  0   gender         byte
     *  1   skin           byte
     *  2   face           byte
     *  3   scar           byte
     *  4   hair           byte
     *  5   facialHair     byte
     *  6   top            byte
     *  7   bottom         byte
     *  8   feet           byte
     *  9   hairColor      int (4 bytes)
     *  13  topColor       int
     *  17  bottomColor    int
     *  21  feetColor      int
     *  25  scale          float (4 bytes)
     * </pre>
     * Total: 29 bytes.
     *
     * <p>Legacy payloads (pre-scale, 25 bytes) decode with the trailing
     * scale field defaulted to {@code 0.9375f} — Tensura's
     * {@code PlayerLikeRenderer} base, the median of the wild-dwarf range.
     */
    @Override
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(29).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) gender);
        buf.put((byte) skin);
        buf.put((byte) face);
        buf.put((byte) scar);
        buf.put((byte) hair);
        buf.put((byte) facialHair);
        buf.put((byte) top);
        buf.put((byte) bottom);
        buf.put((byte) feet);
        buf.putInt(hairColor);
        buf.putInt(topColor);
        buf.putInt(bottomColor);
        buf.putInt(feetColor);
        buf.putFloat(scale);
        return buf.array();
    }

    public static DwarfVariantData decode(byte[] bytes) {
        if (bytes.length < 25) {
            return DEFAULT;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int gender      = buf.get();     // signed (so any -1 sentinel from a
        int skin        = buf.get();     // future Tensura update round-trips
        int face        = buf.get();     // through byte correctly)
        int scar        = buf.get();
        int hair        = buf.get();
        int facialHair  = buf.get();
        int top         = buf.get();
        int bottom      = buf.get();
        int feet        = buf.get();
        int hairColor   = buf.getInt();
        int topColor    = buf.getInt();
        int bottomColor = buf.getInt();
        int feetColor   = buf.getInt();
        // Scale appended in a later revision (29-byte payload). For 25-byte
        // legacy payloads (no trailing float), fall back to the
        // PlayerLikeRenderer baseline so existing dwarf citizens keep a
        // recognisable size on first load.
        float scale     = bytes.length >= 29 ? buf.getFloat() : 0.9375f;
        return new DwarfVariantData(gender, skin, face, scar, hair, facialHair,
                top, bottom, feet, hairColor, topColor, bottomColor, feetColor, scale);
    }

    /** Safe fallback: first enum value for every kind, zero colours,
     *  median scale. */
    public static final DwarfVariantData DEFAULT =
            new DwarfVariantData(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.9375f);
}
