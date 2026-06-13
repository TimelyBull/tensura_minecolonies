package com.example.examplemod;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One generated rival-faction settlement (rival-colony arc, Stage A) —
 * the persistent record the whole arc hangs off. Stage A populates the
 * structural fields (faction, center, pack, anchor boss, placed
 * buildings) and the discovery seam; Stages B–E extend the garrison /
 * assault / conquest fields already reserved here.
 *
 * <p>Mutable by design (the arc mutates it across stages); the SOLE
 * owner is {@link SettlementSavedData} via {@link RivalColonies}.
 */
public class Settlement {

    /** What physical form the settlement takes — the two coexisting
     *  types. MINECOLONIES_CLUSTER: a generated faux-town of MC
     *  schematics (the 6 town factions). DWARVEN_VILLAGE: an EXISTING
     *  Tensura dwarf village adopted as Dwargon's settlement (no
     *  generated buildings; the village's own structures stand). B/C
     *  operate on location + anchor boss, so they're type-agnostic;
     *  only generation (A) and any building-registration differ. */
    public enum StructureType { MINECOLONIES_CLUSTER, DWARVEN_VILLAGE }

    /** Stable per-world id. */
    public int id;
    /** Which structure form this settlement is. */
    public StructureType structureType = StructureType.MINECOLONIES_CLUSTER;
    /** Anchor faction (BossFaction id string). */
    public String factionId;
    /** Dimension the settlement sits in. */
    public ResourceKey<Level> dimension;
    /** Town center (the town-hall anchor). */
    public BlockPos center;
    /** The MineColonies structure pack (display name) it was built from. */
    public String packName;
    /** The anchor boss entity. */
    public UUID bossUuid;
    /** Anchors of every placed building (for B's garrison spread, C's
     *  assault bounds, D's hut registration). */
    public List<BlockPos> buildingPositions = new ArrayList<>();
    /** Players who have DISCOVERED this settlement (seeds Stage C's
     *  Declare-War unlock). */
    public Set<UUID> discoveredBy = new HashSet<>();
    /** Conquered flag (set by Stage D). */
    public boolean conquered = false;

    // --- Reserved seams for later stages (persisted, unused in A) ---
    /** Stage B — live garrison defender entity UUIDs. */
    public Set<UUID> garrisonUuids = new HashSet<>();
    /** Stage B — defender count snapshotted at an assault's start (the
     *  60%-cleared win calc). */
    public int defenderCountAtStart = 0;
    /** Stage C — UUID of the player currently assaulting, or null. */
    public UUID assaultingPlayer = null;
    /** Stage C — the assaulting party's muster/return origin. */
    public BlockPos assaultOrigin = null;

    public Settlement() {}

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("structureType", structureType.name());
        tag.putString("faction", factionId);
        tag.putString("dimension", dimension.location().toString());
        tag.putLong("center", center.asLong());
        tag.putString("pack", packName);
        if (bossUuid != null) tag.putUUID("boss", bossUuid);
        ListTag buildings = new ListTag();
        for (BlockPos p : buildingPositions) {
            CompoundTag b = new CompoundTag();
            b.putLong("pos", p.asLong());
            buildings.add(b);
        }
        tag.put("buildings", buildings);
        ListTag discovered = new ListTag();
        for (UUID u : discoveredBy) {
            CompoundTag d = new CompoundTag();
            d.putUUID("u", u);
            discovered.add(d);
        }
        tag.put("discoveredBy", discovered);
        tag.putBoolean("conquered", conquered);
        // Reserved seams.
        ListTag garrison = new ListTag();
        for (UUID u : garrisonUuids) {
            CompoundTag g = new CompoundTag();
            g.putUUID("u", u);
            garrison.add(g);
        }
        tag.put("garrison", garrison);
        tag.putInt("defenderCountAtStart", defenderCountAtStart);
        if (assaultingPlayer != null) tag.putUUID("assaultingPlayer", assaultingPlayer);
        if (assaultOrigin != null) tag.putLong("assaultOrigin", assaultOrigin.asLong());
        return tag;
    }

    static Settlement load(CompoundTag tag) {
        Settlement s = new Settlement();
        s.id = tag.getInt("id");
        try {
            s.structureType = StructureType.valueOf(tag.getString("structureType"));
        } catch (IllegalArgumentException ignored) {
            s.structureType = StructureType.MINECOLONIES_CLUSTER; // legacy default
        }
        s.factionId = tag.getString("faction");
        s.dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(tag.getString("dimension")));
        s.center = BlockPos.of(tag.getLong("center"));
        s.packName = tag.getString("pack");
        if (tag.hasUUID("boss")) s.bossUuid = tag.getUUID("boss");
        ListTag buildings = tag.getList("buildings", Tag.TAG_COMPOUND);
        for (int i = 0; i < buildings.size(); i++) {
            s.buildingPositions.add(BlockPos.of(buildings.getCompound(i).getLong("pos")));
        }
        ListTag discovered = tag.getList("discoveredBy", Tag.TAG_COMPOUND);
        for (int i = 0; i < discovered.size(); i++) {
            s.discoveredBy.add(discovered.getCompound(i).getUUID("u"));
        }
        s.conquered = tag.getBoolean("conquered");
        ListTag garrison = tag.getList("garrison", Tag.TAG_COMPOUND);
        for (int i = 0; i < garrison.size(); i++) {
            s.garrisonUuids.add(garrison.getCompound(i).getUUID("u"));
        }
        s.defenderCountAtStart = tag.getInt("defenderCountAtStart");
        if (tag.hasUUID("assaultingPlayer")) s.assaultingPlayer = tag.getUUID("assaultingPlayer");
        if (tag.contains("assaultOrigin")) s.assaultOrigin = BlockPos.of(tag.getLong("assaultOrigin"));
        return s;
    }
}
