package com.example.examplemod;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.EntityUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rival-colony arc — Stage D: the CONQUEST PAYOFF (the climax). Runs when
 * an assault is WON (Stage C's {@link RivalColonies#resolveWin}, gated by
 * Stage B's {@code isConquestEligible} — boss dead AND ≥60% defenders
 * killed). It grants the rewards and turns the settlement into a defeated
 * husk. <b>It does NOT found a second colony</b> — that mechanic was
 * retired by DESIGN CHANGE 2 (MineColonies is one-player-colony by
 * design); the citizens go to the player's EXISTING colony.
 *
 * <p>Three rewards + the husk conversion:
 * <ol>
 *   <li><b>Citizen boost</b> — 10–20 faction-themed citizens added to the
 *       player's existing colony (the lend-return path: {@code
 *       createAndRegisterCivilianData} + {@code incrementLevel}), capped
 *       by the colony's housing (overflow is reported, never lost-silently
 *       or crashed).</li>
 *   <li><b>The boss's Covenant skill</b> — granted by FORCE via the same
 *       idempotent {@code DiplomacyManager.grantSkillReward} the
 *       diplomatic Covenant route uses.</li>
 *   <li><b>Loot chest(s)</b> — randomized from the faction's own quest-
 *       reward catalog ({@link DealSpec#factionRewardPool}), so the
 *       warlord earns ≈ what the diplomat would.</li>
 * </ol>
 *
 * <p>The boss's death during the assault already fired the Layer-1
 * marked-kill world-rep fan-out (faction down, enemies up); Stage D does
 * NOT touch reputation — it layers the payoff on top, no double-apply.
 */
public final class ConquestPayoff {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConquestPayoff.class);

    private ConquestPayoff() {}

    // ------------------------------------------------------------------
    // Per-faction citizen profiles — count (10–20) + a themed skill pair.
    // Citizens are non-combat colonists, so these are flavour more than
    // balance; named/tunable all the same.
    // ------------------------------------------------------------------

    /** A faction's citizen levy: how many, and the two skills they arrive
     *  trained in (the lend-return {@code incrementLevel} idiom). */
    record CitizenProfile(int count, Skill primary, int primaryBoost,
                          Skill secondary, int secondaryBoost, String role) {}

    private static final Map<String, CitizenProfile> PROFILES = new LinkedHashMap<>();
    static {
        // Dwargon — dwarven miners & smiths: brawny, tireless.
        PROFILES.put("dwargon", new CitizenProfile(15, Skill.Strength, 25, Skill.Stamina, 12, "miners"));
        // Falmuth — a militaristic kingdom: hardened soldiery.
        PROFILES.put("falmuth", new CitizenProfile(16, Skill.Stamina, 22, Skill.Strength, 14, "soldiers"));
        // Luminous — the Holy Empire: clergy-mages & scholars.
        PROFILES.put("luminous", new CitizenProfile(12, Skill.Mana, 22, Skill.Knowledge, 14, "clergy"));
        // Shizu — fire-mage pupils (her teacher's lineage).
        PROFILES.put("shizu", new CitizenProfile(10, Skill.Mana, 22, Skill.Focus, 12, "pupils"));
        // Leon — a demon lord's elite retinue.
        PROFILES.put("leon", new CitizenProfile(12, Skill.Strength, 22, Skill.Mana, 14, "retainers"));
        // Otherworlders — versatile summoned specialists.
        PROFILES.put("otherworlders", new CitizenProfile(13, Skill.Adaptability, 20, Skill.Creativity, 12, "specialists"));
        // Jura-Tempest Federation — the forest nation's sages (merged
        // tempest + jura_alliance; keeps the old Jura levy profile).
        PROFILES.put("tempest", new CitizenProfile(18, Skill.Knowledge, 22, Skill.Intelligence, 14, "sages"));
    }

    private static final CitizenProfile DEFAULT_PROFILE =
            new CitizenProfile(10, Skill.Stamina, 18, Skill.Adaptability, 10, "captives");

    static CitizenProfile profileFor(String factionId) {
        return PROFILES.getOrDefault(factionId, DEFAULT_PROFILE);
    }

    /** How many loot chests / loot stacks to spawn. */
    private static final int LOOT_MIN_STACKS = 6;
    private static final int LOOT_MAX_STACKS = 12;
    private static final int CHEST_SLOTS = 27;

    // ------------------------------------------------------------------
    // The payoff
    // ------------------------------------------------------------------

    /**
     * Apply the full conquest payoff for a WON assault. {@code level} is
     * the SETTLEMENT's level (where the husk + loot land); the player has
     * already been teleported home, so the citizen levy targets the
     * player's CURRENT-level colony.
     */
    static void apply(ServerLevel level, Settlement s, ServerPlayer player) {
        BossFaction faction = BossFaction.byId(s.factionId);
        String factionName = faction != null ? faction.displayName() : s.factionId;

        grantCitizenLevy(player, s, factionName);
        grantCovenantSkill(player, s, factionName);
        spawnLootChests(level, s, factionName);
        convertToHusk(level, s);

        LOGGER.info("[TM] rival: Stage-D payoff complete for settlement #{} ({}) — now a husk",
                s.id, s.factionId);
    }

    // --- 1. citizen levy (to the EXISTING colony, cap-aware) -----------

    private static void grantCitizenLevy(ServerPlayer player, Settlement s, String factionName) {
        ServerLevel level = player.serverLevel();
        IColony colony = IColonyManager.getInstance().getIColonyByOwner(level, player.getUUID());
        CitizenProfile profile = profileFor(s.factionId);

        if (colony == null || !colony.getServerBuildingManager().hasTownHall()) {
            // EDGE CASE — no colony to receive them: skip the levy, notify,
            // keep the other rewards (skill to the player, loot at the ruin).
            player.sendSystemMessage(Component.literal("The " + factionName
                    + " levy has no colony to join — found one to take captives next time.")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
            LOGGER.info("[TM] rival: conquest citizen levy skipped — {} owns no colony",
                    player.getName().getString());
            return;
        }

        // TRACKED RISK — housing overflow: respect the colony's capacity.
        // Add what fits; report the remainder rather than dropping silently.
        int current = colony.getCitizenManager().getCurrentCitizenCount();
        int max = colony.getCitizenManager().getMaxCitizens();
        int headroom = Math.max(0, max - current);
        int toAdd = Math.min(profile.count(), headroom);

        BlockPos th = colony.getServerBuildingManager().getTownHall().getPosition();
        BlockPos spawnAt = EntityUtils.getSpawnPoint(level, th);
        if (spawnAt == null) spawnAt = th;

        int added = 0;
        for (int i = 0; i < toAdd; i++) {
            try {
                ICitizenData data = colony.getCitizenManager().createAndRegisterCivilianData();
                if (data == null) break;
                data.setName(capitalize(factionName) + " " + capitalize(profile.role()) + " " + (i + 1));
                data.getCitizenSkillHandler().incrementLevel(profile.primary(), profile.primaryBoost());
                data.getCitizenSkillHandler().incrementLevel(profile.secondary(), profile.secondaryBoost());
                if (data.getEntity().isEmpty()) {
                    colony.getCitizenManager().spawnOrCreateCitizen(data, level, spawnAt);
                }
                added++;
            } catch (Throwable t) {
                LOGGER.warn("[TM] rival: conquest citizen #{} failed", i, t);
            }
        }

        player.sendSystemMessage(Component.literal(added + " " + factionName + " "
                + profile.role() + " swear to your colony (trained in "
                + profile.primary().name() + " & " + profile.secondary().name() + ").")
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        if (added < profile.count()) {
            int unhoused = profile.count() - added;
            player.sendSystemMessage(Component.literal(unhoused + " more could not be housed — "
                    + "your colony is at capacity (" + current + "/" + max + "). Expand to take more.")
                    .withStyle(net.minecraft.ChatFormatting.YELLOW));
        }
        LOGGER.info("[TM] rival: conquest levy — {} {} added to colony {} (wanted {}, headroom {})",
                added, profile.role(), colony.getID(), profile.count(), headroom);
    }

    // --- 2. the boss's Covenant skill (granted by force) ---------------

    private static void grantCovenantSkill(ServerPlayer player, Settlement s, String factionName) {
        var supplier = DealSpec.covenantSkillFor(s.factionId);
        if (supplier == null) {
            LOGGER.info("[TM] rival: {} has no capstone skill — no skill reward", s.factionId);
            return;
        }
        try {
            DiplomacyManager.grantSkillReward(player, supplier.get());
            LOGGER.info("[TM] rival: conquest granted {}'s Covenant skill to {} (by force)",
                    s.factionId, player.getName().getString());
        } catch (Throwable t) {
            LOGGER.warn("[TM] rival: conquest skill grant failed for {}", s.factionId, t);
        }
    }

    // --- 3. loot chest(s) from the faction's reward catalog ------------

    private static void spawnLootChests(ServerLevel level, Settlement s, String factionName) {
        List<ItemStack> pool = DealSpec.factionRewardPool(s.factionId);
        if (pool.isEmpty()) {
            LOGGER.info("[TM] rival: {} has no reward pool — no loot chest", s.factionId);
            return;
        }
        Collections.shuffle(pool, new java.util.Random(level.getRandom().nextLong()));
        int want = LOOT_MIN_STACKS + level.getRandom().nextInt(LOOT_MAX_STACKS - LOOT_MIN_STACKS + 1);
        List<ItemStack> loot = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            // Draw with replacement so a small pool still fills the chest.
            loot.add(pool.get(level.getRandom().nextInt(pool.size())).copy());
        }

        // One chest at the town center; a second if the haul is large.
        int chests = loot.size() > CHEST_SLOTS ? 2 : 1;
        int placed = 0;
        for (int c = 0; c < chests; c++) {
            BlockPos at = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,
                    s.center.offset(c == 0 ? 0 : 1, 0, 0));
            level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
            BlockEntity be = level.getBlockEntity(at);
            if (!(be instanceof Container container)) continue;
            int slots = Math.min(CHEST_SLOTS, container.getContainerSize());
            for (int slot = 0; slot < slots; slot++) {
                int idx = c * CHEST_SLOTS + slot;
                if (idx >= loot.size()) break;
                container.setItem(slot, loot.get(idx));
                placed++;
            }
        }
        LOGGER.info("[TM] rival: conquest loot — {} stacks in {} chest(s) at {}",
                placed, chests, s.center);
    }

    // --- 4. defeated-husk conversion -----------------------------------

    /**
     * Convert the settlement to a permanent DEFEATED HUSK: buildings
     * REMAIN (a sacked ruin), the boss is dead, the surviving defenders
     * are cleared, and the {@code conquered} flag makes it inert — never
     * re-discovered-to-war, never garrison-reset. Structure-type-agnostic
     * (town husks and dwarven-village husks behave identically).
     */
    private static void convertToHusk(ServerLevel level, Settlement s) {
        // Clear any defenders still standing (the win needed only ≥60%).
        for (UUID u : new ArrayList<>(s.garrisonUuids)) {
            Entity e = level.getEntity(u);
            if (e != null && !e.isRemoved()) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                        e.getX(), e.getY() + e.getBbHeight() / 2.0, e.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
                e.discard();
            }
        }
        s.garrisonUuids.clear();
        s.conquered = true;       // inert — see RivalColonies gates (declare/garrison/assault)
        s.assaulted = false;
        s.bossUuid = null;        // the anchor is gone for good
        // conquestReached stays true (it recorded the WIN); buildings stay.
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
