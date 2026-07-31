package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.manasmods.tensura.entity.template.subclass.ISubordinate;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import io.github.manasmods.tensura.util.SubordinateHelper;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Drives Luminous's Covenant capstone, "The Trial of Light &amp; Dark".
 *
 * <p>Two halves, each filling a {@link TrialChalice} in thirds:
 * <ul>
 *   <li><b>Show of Faith</b> (light) — cure zombie villagers and raise them to
 *       Master. A majin ("The Next Generation") must further breed two children
 *       from them and raise both to Master.</li>
 *   <li><b>The Blood Sacrifice</b> (dark) — kill your own subordinates. A majin
 *       must give up their three highest-EP subordinates.</li>
 * </ul>
 *
 * <p>The player receives one empty chalice of each kind on accepting the deal;
 * completing the two full chalices and turning them in forges the Covenant and
 * yields the Twin Grail. Progress persists in {@link TrialSavedData}. All halves
 * get harder for a majin — see docs/faction-rewards-roadmap.md for the design.
 *
 * <p>Majin difficulty is fixed at accept time ({@code majin} captured then), so a
 * later side-change doesn't retroactively re-scale a trial in progress.
 */
final class TrialManager {

    private TrialManager() {}

    static final int FLOCK_REQUIRED    = 3;   // villagers cured + mastered
    static final int CHILDREN_REQUIRED = 2;   // majin: children bred + mastered
    static final int DARK_REQUIRED     = 3;   // subordinates sacrificed
    static final int MASTER_LEVEL      = 5;   // vanilla villager top trade tier

    // NBT keys on the per-player progress tag.
    private static final String K_MAJIN            = "majin";
    private static final String K_FLOCK            = "flock";
    private static final String K_FLOCK_MASTERED   = "flockMastered";
    private static final String K_CHILDREN         = "children";
    private static final String K_CHILDREN_MASTER  = "childrenMastered";
    private static final String K_DARK_COUNT       = "darkCount";
    private static final String K_DARK_TARGETS     = "darkTargets";   // majin top-3 identity ids
    private static final String K_DARK_DONE        = "darkDone";

    // ------------------------------------------------------------------
    // Accept / turn-in
    // ------------------------------------------------------------------

    /** Accepting the deal: capture difficulty, snapshot the majin's top-3
     *  subordinates, and hand the player the two empty chalices. */
    static void onAccept(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        TrialSavedData data = TrialSavedData.get(level);
        UUID uuid = player.getUUID();

        CompoundTag tag = new CompoundTag();
        boolean majin = WorldReputationManager.isMajinSide(player);
        tag.putBoolean(K_MAJIN, majin);
        tag.put(K_FLOCK, new ListTag());
        tag.put(K_FLOCK_MASTERED, new ListTag());
        tag.put(K_CHILDREN, new ListTag());
        tag.put(K_CHILDREN_MASTER, new ListTag());
        tag.putInt(K_DARK_COUNT, 0);
        tag.put(K_DARK_DONE, new ListTag());
        if (majin) {
            ListTag targets = new ListTag();
            for (UUID id : snapshotTopSubordinateIdentities(player, DARK_REQUIRED)) {
                targets.add(NbtUtils.createUUID(id));
            }
            tag.put(K_DARK_TARGETS, targets);
        }
        data.put(uuid, tag);

        giveChalice(player, TrialChalice.create(TrialChalice.Kind.HOLY, uuid));
        giveChalice(player, TrialChalice.create(TrialChalice.Kind.BLOOD, uuid));
        player.sendSystemMessage(Component.translatable(
                majin ? "tensura_minecolonies.trial.begin.majin"
                      : "tensura_minecolonies.trial.begin.human"));
        stampChalices(player, tag);
    }

    /** Turn-in: the player must carry a FULL holy + FULL blood chalice bound to
     *  them. Consumes both and clears the trial. Returns false if not ready. */
    static boolean tryTurnIn(ServerPlayer player) {
        ItemStack holy = findChalice(player, TrialChalice.Kind.HOLY, true);
        ItemStack blood = findChalice(player, TrialChalice.Kind.BLOOD, true);
        if (holy == null || blood == null) return false;
        holy.shrink(1);
        blood.shrink(1);
        TrialSavedData.get(player.serverLevel()).remove(player.getUUID());
        return true;
    }

    // ------------------------------------------------------------------
    // Debug (/trial) — start/status/turnin without the diplomacy chain
    // ------------------------------------------------------------------

    /** {@code /trial start} — begin a trial as if the covenant were accepted
     *  (grants the chalices + tracking), bypassing the diplomacy grind. */
    static String debugStart(ServerPlayer player) {
        if (TrialSavedData.get(player.serverLevel()).has(player.getUUID())) {
            return "you already have an active trial (use /trial status)";
        }
        onAccept(player);
        return "trial started ("
                + (WorldReputationManager.isMajinSide(player) ? "MAJIN" : "human")
                + " difficulty) — two empty chalices granted";
    }

    /** {@code /trial status} — dump the player's current progress. */
    static String debugStatus(ServerPlayer player) {
        CompoundTag tag = active(player);
        if (tag == null) return "no active trial (use /trial start)";
        boolean majin = tag.getBoolean(K_MAJIN);
        StringBuilder sb = new StringBuilder();
        sb.append(majin ? "MAJIN" : "human").append(" trial — ");
        sb.append("HOLY ").append(holyFill(tag)).append("/3 ")
          .append("[flock ").append(uuidCount(tag.getList(K_FLOCK, Tag.TAG_INT_ARRAY)))
          .append("/").append(FLOCK_REQUIRED)
          .append(", mastered ").append(uuidCount(tag.getList(K_FLOCK_MASTERED, Tag.TAG_INT_ARRAY)));
        if (majin) {
            sb.append(", children ").append(uuidCount(tag.getList(K_CHILDREN, Tag.TAG_INT_ARRAY)))
              .append("/").append(CHILDREN_REQUIRED)
              .append(", children mastered ").append(uuidCount(tag.getList(K_CHILDREN_MASTER, Tag.TAG_INT_ARRAY)));
        }
        sb.append("]; BLOOD ").append(bloodFill(tag)).append("/3 [");
        if (majin) {
            sb.append("targets ").append(uuidCount(tag.getList(K_DARK_TARGETS, Tag.TAG_INT_ARRAY)))
              .append(", done ").append(uuidCount(tag.getList(K_DARK_DONE, Tag.TAG_INT_ARRAY)));
        } else {
            sb.append("sacrifices ").append(tag.getInt(K_DARK_COUNT)).append("/").append(DARK_REQUIRED);
        }
        sb.append("]");
        return sb.toString();
    }

    /** {@code /trial turnin} — if both chalices are full, consume them and grant
     *  the Twin Grail directly (bypasses the diplomacy deliver path). */
    static String debugTurnIn(ServerPlayer player) {
        if (!tryTurnIn(player)) return "both chalices must be FULL first (see /trial status)";
        ItemStack grail = new ItemStack(ExampleMod.TWIN_GRAIL.get());
        if (!player.getInventory().add(grail)) player.drop(grail, false);
        return "chalices consumed — the Twin Grail is yours";
    }

    // ------------------------------------------------------------------
    // Light half — "Show of Faith"
    // ------------------------------------------------------------------

    /** A zombie villager was cured near a player running the trial — claim it as
     *  part of their flock (until the flock is full). */
    static void onZombieCured(ServerPlayer curer, Villager villager) {
        CompoundTag tag = active(curer);
        if (tag == null) return;
        ListTag flock = tag.getList(K_FLOCK, Tag.TAG_INT_ARRAY);
        if (uuidCount(flock) >= FLOCK_REQUIRED) return;      // flock already gathered
        if (containsUUID(flock, villager.getUUID())) return; // already claimed
        flock.add(NbtUtils.createUUID(villager.getUUID()));
        tag.put(K_FLOCK, flock);
        dirty(curer, tag);
        curer.serverLevel().sendParticles(ParticleTypes.END_ROD,
                villager.getX(), villager.getY() + 1.0, villager.getZ(), 20, 0.4, 0.6, 0.4, 0.02);
    }

    /** A villager was born — if both parents belong to a trial player's flock,
     *  the child counts toward "The Next Generation" (majin only). */
    static void onVillagerBorn(ServerLevel level, Villager child, UUID parentA, UUID parentB) {
        TrialSavedData data = TrialSavedData.get(level);
        for (ServerPlayer player : level.players()) {
            CompoundTag tag = data.get(player.getUUID());
            if (tag == null || !tag.getBoolean(K_MAJIN)) continue;
            ListTag flock = tag.getList(K_FLOCK, Tag.TAG_INT_ARRAY);
            if (!containsUUID(flock, parentA) || !containsUUID(flock, parentB)) continue;
            ListTag children = tag.getList(K_CHILDREN, Tag.TAG_INT_ARRAY);
            if (uuidCount(children) >= CHILDREN_REQUIRED) continue;
            if (containsUUID(children, child.getUUID())) continue;
            children.add(NbtUtils.createUUID(child.getUUID()));
            tag.put(K_CHILDREN, children);
            data.markDirty();
            level.sendParticles(ParticleTypes.END_ROD,
                    child.getX(), child.getY() + 0.5, child.getZ(), 15, 0.3, 0.4, 0.3, 0.02);
            return;
        }
    }

    /** Per-second poll: check tracked villagers (flock + children) for Master
     *  rank and refresh every active player's holy chalice. */
    static void tick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        TrialSavedData data = TrialSavedData.get(overworld);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CompoundTag tag = data.get(player.getUUID());
            if (tag == null) continue;
            boolean changed = false;
            changed |= promoteMastered(server, tag, K_FLOCK, K_FLOCK_MASTERED);
            if (tag.getBoolean(K_MAJIN)) {
                changed |= promoteMastered(server, tag, K_CHILDREN, K_CHILDREN_MASTER);
            }
            if (changed) data.markDirty();
            stampChalices(player, tag);
        }
    }

    /** Move any tracked villager that has reached Master from {@code fromKey} into
     *  {@code masteredKey}. Returns true if anything changed. */
    private static boolean promoteMastered(MinecraftServer server, CompoundTag tag,
                                           String fromKey, String masteredKey) {
        ListTag from = tag.getList(fromKey, Tag.TAG_INT_ARRAY);
        ListTag mastered = tag.getList(masteredKey, Tag.TAG_INT_ARRAY);
        boolean changed = false;
        for (int i = 0; i < from.size(); i++) {
            UUID id = NbtUtils.loadUUID(from.get(i));
            if (containsUUID(mastered, id)) continue;
            Villager v = findVillager(server, id);
            if (v != null && v.getVillagerData().getLevel() >= MASTER_LEVEL) {
                mastered.add(NbtUtils.createUUID(id));
                changed = true;
            }
        }
        if (changed) tag.put(masteredKey, mastered);
        return changed;
    }

    // ------------------------------------------------------------------
    // Dark half — "The Blood Sacrifice"
    // ------------------------------------------------------------------

    /** The owner killed one of their own subordinates during the trial — the
     *  ritual sacrifice. Fills the blood chalice; returns true if it counted (so
     *  the caller can suppress collateral and confirm the ritual). */
    static boolean onSubordinateSacrificed(ServerPlayer owner, Mob subordinate) {
        CompoundTag tag = active(owner);
        if (tag == null) return false;

        if (tag.getBoolean(K_MAJIN)) {
            // Majin — only the snapshotted top-3 highest-EP subordinates count.
            RaceIdentitySavedData ids = RaceIdentitySavedData.get(owner.serverLevel());
            RaceIdentitySavedData.RaceIdentity id = ids.getByMobUUID(subordinate.getUUID());
            if (id == null) return false;
            ListTag targets = tag.getList(K_DARK_TARGETS, Tag.TAG_INT_ARRAY);
            ListTag done = tag.getList(K_DARK_DONE, Tag.TAG_INT_ARRAY);
            if (!containsUUID(targets, id.identityId)) {
                owner.sendSystemMessage(Component.translatable(
                        "tensura_minecolonies.trial.dark.not_strongest"));
                return false;
            }
            if (containsUUID(done, id.identityId)) return false;
            done.add(NbtUtils.createUUID(id.identityId));
            tag.put(K_DARK_DONE, done);
        } else {
            int count = tag.getInt(K_DARK_COUNT);
            if (count >= DARK_REQUIRED) return false;
            tag.putInt(K_DARK_COUNT, count + 1);
        }
        dirty(owner, tag);
        owner.serverLevel().sendParticles(ParticleTypes.SOUL,
                subordinate.getX(), subordinate.getY() + 0.8, subordinate.getZ(),
                25, 0.4, 0.6, 0.4, 0.02);
        owner.sendSystemMessage(Component.translatable("tensura_minecolonies.trial.dark.filled"));
        return true;
    }

    // ------------------------------------------------------------------
    // Fill computation + chalice stamping
    // ------------------------------------------------------------------

    /** The holy chalice's fill (0..3) from the current light progress. */
    private static int holyFill(CompoundTag tag) {
        int mastered = uuidCount(tag.getList(K_FLOCK_MASTERED, Tag.TAG_INT_ARRAY));
        if (!tag.getBoolean(K_MAJIN)) {
            return Math.min(TrialChalice.MAX_FILL, mastered);
        }
        // Majin milestones, in order: 3 mastered → children born → children mastered.
        int fill = 0;
        if (mastered >= FLOCK_REQUIRED) fill = 1;
        if (fill == 1 && uuidCount(tag.getList(K_CHILDREN, Tag.TAG_INT_ARRAY)) >= CHILDREN_REQUIRED) fill = 2;
        if (fill == 2 && uuidCount(tag.getList(K_CHILDREN_MASTER, Tag.TAG_INT_ARRAY)) >= CHILDREN_REQUIRED) fill = 3;
        return fill;
    }

    /** The blood chalice's fill (0..3) from the current dark progress. */
    private static int bloodFill(CompoundTag tag) {
        int done = tag.getBoolean(K_MAJIN)
                ? uuidCount(tag.getList(K_DARK_DONE, Tag.TAG_INT_ARRAY))
                : tag.getInt(K_DARK_COUNT);
        return Math.min(TrialChalice.MAX_FILL, done);
    }

    /** Stamp both fills onto the player's carried chalices (and flourish on a
     *  chalice that just filled up). */
    private static void stampChalices(ServerPlayer player, CompoundTag tag) {
        applyFill(player, TrialChalice.Kind.HOLY, holyFill(tag), ParticleTypes.END_ROD);
        applyFill(player, TrialChalice.Kind.BLOOD, bloodFill(tag), ParticleTypes.SOUL);
    }

    private static void applyFill(ServerPlayer player, TrialChalice.Kind kind, int fill,
                                  net.minecraft.core.particles.SimpleParticleType flourish) {
        ItemStack chalice = findChalice(player, kind, false);
        if (chalice == null) return;
        int before = TrialChalice.fillOf(chalice);
        if (before == fill) return;
        TrialChalice.setFill(chalice, fill);
        if (fill >= TrialChalice.MAX_FILL && before < TrialChalice.MAX_FILL) {
            player.serverLevel().sendParticles(flourish,
                    player.getX(), player.getY() + 1.2, player.getZ(), 40, 0.5, 0.8, 0.5, 0.04);
            player.sendSystemMessage(Component.translatable(
                    kind == TrialChalice.Kind.HOLY
                            ? "tensura_minecolonies.trial.holy.full"
                            : "tensura_minecolonies.trial.blood.full"));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The player's active trial tag, or null. */
    private static CompoundTag active(ServerPlayer player) {
        return TrialSavedData.get(player.serverLevel()).get(player.getUUID());
    }

    private static void dirty(ServerPlayer player, CompoundTag tag) {
        TrialSavedData data = TrialSavedData.get(player.serverLevel());
        data.put(player.getUUID(), tag);
        stampChalices(player, tag);
    }

    private static void giveChalice(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /** Find a chalice of {@code kind} bound to the player; if {@code mustBeFull},
     *  only a full one qualifies. */
    private static ItemStack findChalice(ServerPlayer player, TrialChalice.Kind kind, boolean mustBeFull) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!TrialChalice.isTrialChalice(stack)) continue;
            if (TrialChalice.kindOf(stack) != kind) continue;
            if (!TrialChalice.isOwnedBy(stack, player.getUUID())) continue;
            if (mustBeFull && !TrialChalice.isFull(stack)) continue;
            return stack;
        }
        return null;
    }

    /** Snapshot the identityIds of the player's {@code n} highest-EP loaded owned
     *  subordinates (majin dark-target set, taken at accept time). */
    private static List<UUID> snapshotTopSubordinateIdentities(ServerPlayer player, int n) {
        ServerLevel level = player.serverLevel();
        RaceIdentitySavedData ids = RaceIdentitySavedData.get(level);
        UUID me = player.getUUID();
        AABB scan = player.getBoundingBox().inflate(256);
        record Ranked(UUID identityId, double ep) {}
        List<Ranked> ranked = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, scan, m -> m.isAlive()
                && m instanceof ISubordinate
                && me.equals(SubordinateHelper.getSubordinateOwnerUUID(m)))) {
            RaceIdentitySavedData.RaceIdentity id = ids.getByMobUUID(mob.getUUID());
            if (id == null) continue;
            ExistenceStorage ex = ExampleMod.readExistence(mob);
            ranked.add(new Ranked(id.identityId, ex != null ? ex.getEP() : 0.0));
        }
        ranked.sort((a, b) -> Double.compare(b.ep(), a.ep()));
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, ranked.size()); i++) out.add(ranked.get(i).identityId());
        return out;
    }

    /** Find a tracked villager (any loaded dimension) by UUID, or null. */
    private static Villager findVillager(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e instanceof Villager v) return v;
        }
        return null;
    }

    // --- UUID list helpers (stored as int-array UUID entries) ---

    private static boolean containsUUID(ListTag list, UUID id) {
        for (int i = 0; i < list.size(); i++) {
            if (NbtUtils.loadUUID(list.get(i)).equals(id)) return true;
        }
        return false;
    }

    private static int uuidCount(ListTag list) {
        return list.size();
    }
}
