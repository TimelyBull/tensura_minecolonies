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
     * Standing-order marker for the "Patrol Colony Outskirts" subordinate
     * command — see {@link PatrolOrder} and {@link SubordinatePatrol}.
     * Attached to a named Tensura subordinate while it is following the
     * PATROL command; removed when the command is cycled away. Default null
     * → {@code hasData(...)} is the authoritative presence check, same
     * pattern as {@link #RACE_TAG} / {@link #ENVOY_TAG}.
     */
    public static final Supplier<AttachmentType<PatrolOrder>> PATROL_ORDER =
            ATTACHMENTS.register("patrol_order",
                    () -> AttachmentType.<PatrolOrder>builder(() -> null)
                            .serialize(PatrolOrder.SERIALIZER)
                            .build());

    /**
     * Raid-mob marker — see {@link RaidTag} and {@link TensuraRaidEvent}.
     * Attached to every Tensura mob spawned by a raid; checked by the
     * steering pass and the barrier block. Default null →
     * {@code hasData(...)} is the authoritative presence check.
     */
    public static final Supplier<AttachmentType<RaidTag>> RAID_TAG =
            ATTACHMENTS.register("raid_tag",
                    () -> AttachmentType.<RaidTag>builder(() -> null)
                            .serialize(RaidTag.SERIALIZER)
                            .build());

    private Attachments() {}

    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }
}
