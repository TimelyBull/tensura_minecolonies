package com.example.examplemod;

import io.github.manasmods.tensura.data.existence.gear.GearExistenceData;
import io.github.manasmods.tensura.data.existence.gear.UniqueGearEvolutionData;
import io.github.manasmods.tensura.handler.GearHandler;
import io.github.manasmods.tensura.registry.data.TensuraCustomData;
import io.github.manasmods.tensura.registry.item.misc.TensuraDataComponents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a growing weapon's stats in sync with its {@code gear_existence} EP
 * ladder — for weapons that ALREADY EXIST in a player's inventory.
 *
 * <p>Tensura bakes each EP tier into the stack: {@code GearHandler} folds the
 * tier's amounts into the stack's {@code ATTRIBUTE_MODIFIERS} component and then
 * deletes that tier from the stack's remaining-evolutions list. Two consequences
 * we have to handle ourselves:</p>
 *
 * <ul>
 *   <li><b>The tiers COMPOUND.</b> Each one is added to whatever the stack is
 *       already at, so the four numbers in a ladder sum — they are increments,
 *       not "the stats at this tier". (0.2.0 shipped ladders authored as
 *       absolute values, which is how a Masterwork katana ended up at 184
 *       attack damage instead of the intended cap.)</li>
 *   <li><b>Re-tuning the datapack does nothing to existing weapons.</b> Their
 *       numbers are already baked in, and a player can't re-forge without
 *       spending another Masterwork Core.</li>
 * </ul>
 *
 * <p>{@link #recalibrate} rebuilds the stack's stats from scratch — the item's
 * current base attributes plus exactly the tiers its EP has actually reached,
 * folded in through Tensura's own helper so the maths is identical to what
 * {@code GearHandler} would have produced. A stack that is already correct is
 * left completely untouched, so this is safe to call on a timer.</p>
 *
 * <p><b>Known gap:</b> {@code GearHandler.getEvolvedAttributeModifiers} only
 * bumps attributes the item ALREADY declares. A ladder entry for an attribute
 * the base item doesn't have (say max health on a weapon that never declares it)
 * is silently dropped — by Tensura, not by us. Give the base item the attribute
 * if a tier is meant to raise it.</p>
 */
final class GearEvolution {

    private GearEvolution() { }

    /** Per-item EP ladder, read from the gear_existence registry. */
    private static final Map<Item, List<UniqueGearEvolutionData>> LADDER_CACHE = new HashMap<>();
    /** The registry the cache was built from — a datapack reload swaps it. */
    private static Registry<GearExistenceData> cachedRegistry;

    /** Rebuild {@code stack}'s stats from its CURRENT EP. No-op when they already match. */
    static void recalibrate(ItemStack stack, Level level) {
        Double ep = stack.get(TensuraDataComponents.EP.get());
        if (ep == null) return;   // never stamped — GearHandler does that on equip
        List<UniqueGearEvolutionData> ladder = ladderFor(stack, level);
        if (ladder.isEmpty()) return;

        List<UniqueGearEvolutionData> remaining = new ArrayList<>();
        // The base attributes must come from the item's DEFAULT ATTRIBUTE_MODIFIERS
        // COMPONENT — NOT Item.getDefaultAttributeModifiers(), which in 1.21.1 is a
        // legacy shim that always returns ItemAttributeModifiers.EMPTY. Seeding from
        // EMPTY made getEvolvedAttributeModifiers (which only bumps attributes that
        // already exist) produce EMPTY too, so recalibrate wiped the weapon's damage,
        // speed and reach the moment Tensura stamped EP onto it (i.e. on first hold).
        ItemAttributeModifiers expected = stack.getItem().components()
                .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (UniqueGearEvolutionData tier : ladder) {
            if (ep >= tier.EP()) {
                expected = GearHandler.getEvolvedAttributeModifiers(expected, tier).build();
            } else {
                remaining.add(tier);
            }
        }

        ItemAttributeModifiers current = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (current != null && current.modifiers().equals(expected.modifiers())) return;
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, expected);
        stack.set(TensuraDataComponents.UNIQUE_EVOLUTIONS.get(), remaining);
    }

    /** This item's full EP ladder (every tier), straight from the datapack. */
    private static List<UniqueGearEvolutionData> ladderFor(ItemStack stack, Level level) {
        Registry<GearExistenceData> registry =
                level.registryAccess().registryOrThrow(TensuraCustomData.GEAR_EXISTENCE);
        if (registry != cachedRegistry) {
            LADDER_CACHE.clear();
            cachedRegistry = registry;
        }
        return LADDER_CACHE.computeIfAbsent(stack.getItem(), item -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            for (GearExistenceData data : registry) {
                if (data.gear().equals(id) && data.uniqueEvolution().isPresent()) {
                    return data.uniqueEvolution().get();
                }
            }
            return List.of();
        });
    }
}
