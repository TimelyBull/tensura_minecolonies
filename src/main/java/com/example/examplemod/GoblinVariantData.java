package com.example.examplemod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decoded per-citizen goblin appearance (Stage F4).
 *
 * Holds the raw integer IDs Tensura's {@code GoblinVariant.*} enums use —
 * decoding (id → enum value → texture) is done by {@link GoblinTextures}
 * at render time, mirroring Tensura's own per-layer texture selection.
 *
 * Wire / NBT format: fixed 24-byte little-endian layout. Stable across
 * versions because every field is a primitive at a known offset, so
 * mismatches between encoder and decoder are caught immediately as
 * out-of-bounds rather than producing silently-wrong appearances.
 *
 * For F4 we only render the BASE goblin tier; {@code head} / {@code top}
 * / {@code bottom} are captured for forward-compatibility with the
 * hobgoblin renderer (Stage G) but ignored by the current
 * {@code GoblinCitizenRenderer} (Tensura's own Top/Bottom/Head layers
 * are gated by {@code isHobgoblin()} and return immediately for base
 * goblins).
 */
public record GoblinVariantData(
        int gender,          // 0=MALE, 1=FEMALE, 2=OTHER
        int skin,            // 0=MEDIUM, 1=LIGHT, 2=DARK
        int face,            // 0..4 → FACE_A..FACE_E
        int hair,            // 0=BANDANA, 1=LONG, 2=SHORT
        int hairColor,       // ARGB int; 0 = no tint
        int head,            // hobgoblin: 0=BANDANA, 1=BANDANA_FULL; -1 = no head accessory
        int headColor,       //
        int top,             // hobgoblin: 0=T_SHIRT, 1=VEST
        int topColor,        //
        int bottom,          // hobgoblin: 0=SHORTS, 1=PANTS
        int bottomColor,     //
        boolean bandages,
        int evolutionState   // 0=base goblin, ≥1=hobgoblin (matches GoblinEntity.isHobgoblin)
) implements RaceVariantData {

    public boolean isHobgoblin() {
        return evolutionState >= 1;
    }

    /** Encoded layout (little-endian, fixed offsets):
     *  <pre>
     *  0   gender         byte (signed, but use & 0xFF on decode for -1 sentinels)
     *  1   skin           byte
     *  2   face           byte
     *  3   hair           byte
     *  4   head           byte
     *  5   top            byte
     *  6   bottom         byte
     *  7   bandages       byte (0/1)
     *  8   hairColor      int (4 bytes)
     *  12  headColor      int
     *  16  topColor       int
     *  20  bottomColor    int
     *  24  evolutionState byte (added in F5 — pre-F5 records decode as 0 = base goblin)
     *  </pre>
     *  Total: 25 bytes. Pre-F5 24-byte payloads decode with evolutionState=0,
     *  which is the correct historical default (base goblin only). */
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) gender);
        buf.put((byte) skin);
        buf.put((byte) face);
        buf.put((byte) hair);
        buf.put((byte) head);
        buf.put((byte) top);
        buf.put((byte) bottom);
        buf.put((byte) (bandages ? 1 : 0));
        buf.putInt(hairColor);
        buf.putInt(headColor);
        buf.putInt(topColor);
        buf.putInt(bottomColor);
        buf.put((byte) evolutionState);
        return buf.array();
    }

    public static GoblinVariantData decode(byte[] bytes) {
        if (bytes.length < 24) {
            // F2 placeholder (empty array) — return a safe default.
            return DEFAULT;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int gender = buf.get();        // signed; head/top/bottom may store -1
        int skin   = buf.get();
        int face   = buf.get();
        int hair   = buf.get();
        int head   = buf.get();
        int top    = buf.get();
        int bottom = buf.get();
        boolean bandages = buf.get() != 0;
        int hairColor   = buf.getInt();
        int headColor   = buf.getInt();
        int topColor    = buf.getInt();
        int bottomColor = buf.getInt();
        // evolutionState appended in F5. Pre-F5 records are 24 bytes;
        // default to 0 (base goblin).
        int evolutionState = (bytes.length >= 25) ? buf.get() : 0;
        return new GoblinVariantData(gender, skin, face, hair, hairColor,
                head, headColor, top, topColor, bottom, bottomColor, bandages,
                evolutionState);
    }

    /** Safe fallback used when decoding a legacy / corrupt payload. */
    public static final GoblinVariantData DEFAULT =
            new GoblinVariantData(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0);
}
