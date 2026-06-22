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

    // --- Stage B — the garrison + the assault win-tracking machinery ---
    /** Live garrison defender entity UUIDs (the boss is tracked
     *  separately via {@link #bossUuid}). */
    public Set<UUID> garrisonUuids = new HashSet<>();
    /** Defender count snapshotted when the garrison was raised (and
     *  re-snapshotted at each assault's start) — the denominator of the
     *  60%-cleared win calc. */
    public int defenderCountAtStart = 0;
    /** Defenders confirmed killed (the GARRISON_TAG death-tally). Reset to
     *  0 at each assault start; the numerator of the 60% check. */
    public int defenderKills = 0;
    /** Anchor boss confirmed dead (one half of the conquest condition). */
    public boolean bossDead = false;
    /** Settlement state machine: IDLE (garrison at rest) vs ASSAULTED (an
     *  assault is in progress — Stage C drives the flag; Stage B only
     *  reads/exposes it and clears it on reset). */
    public boolean assaulted = false;

    // --- Stage C — the discovery + Declare-War + assault loop ----------
    /** UUID of the player currently assaulting, or null (IDLE). */
    public UUID assaultingPlayer = null;
    /** The assaulting player's pre-war location (where they teleport back
     *  to on win / retreat / death / logout). */
    public BlockPos assaultOrigin = null;
    /** Dimension of {@link #assaultOrigin} (the return trip may cross
     *  dimensions). */
    public ResourceKey<Level> assaultOriginDim = null;
    /** Entity UUIDs of the teleported-in war party (to bring home). */
    public Set<UUID> warParty = new HashSet<>();
    /** Set by Stage C when an assault is WON (bossDead && ≥60% defenders).
     *  Stage D consumes this to grant the payoff + set {@link #conquered};
     *  C only flags + teleports the party home. */
    public boolean conquestReached = false;
    /** The assaulting player died / logged out mid-assault and owes a
     *  return trip to {@link #assaultOrigin} on next respawn / login. */
    public boolean pendingReturn = false;

    // --- Stage E — betrayal scaling ------------------------------------
    /** Betrayal multiplier on the garrison's stat-bump for the CURRENT
     *  assault — 1.0 = not a betrayal (no diplomatic relations); >1.0 =
     *  scaled by the broken relationship tier (the deeper the ally, the
     *  harder the punishment). Set at Declare-War, cleared on resolve. */
    public double betrayalFactor = 1.0;
    /** The relationship tier that was betrayed ("OPEN"/"PACT"/"COVENANT"),
     *  or "" — selects the defender-skill set. */
    public String betrayalTier = "";

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
        tag.putInt("defenderKills", defenderKills);
        tag.putBoolean("bossDead", bossDead);
        tag.putBoolean("assaulted", assaulted);
        if (assaultingPlayer != null) tag.putUUID("assaultingPlayer", assaultingPlayer);
        if (assaultOrigin != null) tag.putLong("assaultOrigin", assaultOrigin.asLong());
        if (assaultOriginDim != null) tag.putString("assaultOriginDim", assaultOriginDim.location().toString());
        ListTag party = new ListTag();
        for (UUID u : warParty) {
            CompoundTag p = new CompoundTag();
            p.putUUID("u", u);
            party.add(p);
        }
        tag.put("warParty", party);
        tag.putBoolean("conquestReached", conquestReached);
        tag.putBoolean("pendingReturn", pendingReturn);
        tag.putDouble("betrayalFactor", betrayalFactor);
        tag.putString("betrayalTier", betrayalTier);
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
        // Faction merge migration: the old "jura_alliance" faction was
        // folded into "tempest" (Tempest Jura Alliance). Any settlement
        // saved under the old id resolves through the moved anchor/pack/
        // garrison once renamed.
        if ("jura_alliance".equals(s.factionId)) {
            s.factionId = "tempest";
        }
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
        s.defenderKills = tag.getInt("defenderKills");
        s.bossDead = tag.getBoolean("bossDead");
        s.assaulted = tag.getBoolean("assaulted");
        if (tag.hasUUID("assaultingPlayer")) s.assaultingPlayer = tag.getUUID("assaultingPlayer");
        if (tag.contains("assaultOrigin")) s.assaultOrigin = BlockPos.of(tag.getLong("assaultOrigin"));
        if (tag.contains("assaultOriginDim")) {
            s.assaultOriginDim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse(tag.getString("assaultOriginDim")));
        }
        ListTag party = tag.getList("warParty", Tag.TAG_COMPOUND);
        for (int i = 0; i < party.size(); i++) {
            s.warParty.add(party.getCompound(i).getUUID("u"));
        }
        s.conquestReached = tag.getBoolean("conquestReached");
        s.pendingReturn = tag.getBoolean("pendingReturn");
        s.betrayalFactor = tag.contains("betrayalFactor") ? tag.getDouble("betrayalFactor") : 1.0;
        s.betrayalTier = tag.getString("betrayalTier");
        return s;
    }
}
