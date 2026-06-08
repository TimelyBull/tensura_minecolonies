package com.example.examplemod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * NeoForge data-attachment registry for this mod.
 *
 * A single attachment type — {@link #RACE_TAG} — marks a MineColonies
 * citizen entity as a race-identity body. Presence is checked with
 * {@code entity.hasData(RACE_TAG.get())}; payload read with
 * {@code entity.getData(...)}; clearing with {@code removeData(...)}.
 *
 * Default value supplier returns {@code null}, so {@code hasData()} is
 * the authoritative presence check.
 *
 * Note: the underlying registry path is intentionally kept as
 * {@code "goblin_tag"} for backward compatibility with existing world
 * saves that already have attachments under that key. Renaming the
 * registry key would orphan attachments on citizens already in flight.
 * Only the Java identifier is updated.
 */
public final class Attachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ExampleMod.MODID);

    public static final Supplier<AttachmentType<RaceTag>> RACE_TAG =
            ATTACHMENTS.register("goblin_tag", // registry key kept for save compat
                    () -> AttachmentType.<RaceTag>builder(() -> null)
                            .serialize(RaceTag.SERIALIZER)
                            .build());

    /**
     * Envoy marker — see {@link EnvoyTag}. Attached to envoy mobs
     * (GoblinEntity / OrcEntity / vanilla Villager) so right-click /
     * naming / despawn logic can identify them. Default null →
     * {@code hasData(...)} is the authoritative presence check, same
     * pattern as {@link #RACE_TAG}.
     */
    public static final Supplier<AttachmentType<EnvoyTag>> ENVOY_TAG =
            ATTACHMENTS.register("envoy_tag",
                    () -> AttachmentType.<EnvoyTag>builder(() -> null)
                            .serialize(EnvoyTag.SERIALIZER)
                            .build());

    /**
     * Beast-guard marker — see {@link BeastTag}. Attached to citizen
     * entities created from a named beast (knight spider). Disjoint
     * from {@link #RACE_TAG} — a citizen has one or the other, never
     * both. The render-handler pipeline probes BEAST first, then RACE.
     */
    public static final Supplier<AttachmentType<BeastTag>> BEAST_TAG =
            ATTACHMENTS.register("beast_tag",
                    () -> AttachmentType.<BeastTag>builder(() -> null)
                            .serialize(BeastTag.SERIALIZER)
                            .build());

    private Attachments() {}

    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }
}
