package com.example.examplemod;

/**
 * Marker for per-race variant data carried inside a {@link RaceTag}.
 *
 * Sealed so the type system enforces a closed set of races at the
 * call sites that have to do per-race work (encoding dispatch in
 * {@code RaceTag.fromWire}, type-narrowing in the renderers). Adding
 * a new race is one new {@code permits} entry plus a new record.
 *
 * Each implementation owns its own wire format — {@link #encode()}
 * is polymorphic, and decoding happens through a race-specific
 * static {@code decode(byte[])} on the concrete record, dispatched
 * by the race byte in {@link RaceTag#fromWire}.
 */
public sealed interface RaceVariantData
        permits GoblinVariantData, OrcVariantData, LizardmanVariantData, DwarfVariantData {

    /** Race-specific wire encoding. Layout is private to the implementation. */
    byte[] encode();
}
