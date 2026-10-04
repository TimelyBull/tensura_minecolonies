package com.example.examplemod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import io.github.manasmods.tensura.util.SubordinateHelper;
import net.tslat.smartbrainlib.util.BrainUtils;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * The Orb of Domination — the Moderate Harlequin Alliance's (Clayman's)
 * Covenant reward (2026-09-26, developer design).
 *
 * <p>Canon: the pendant Clayman hung on Milim to "control" her. Right-click a
 * hostile mob with it: the orb is hung on the mob (the item leaves your hand)
 * and the mob becomes your puppet — INDEFINITELY, until it dies. When it dies
 * the orb drops where it fell, so it can be used again.
 *
 * <p>How the puppet works. Tensura's mind control only records an owner — it
 * doesn't make a vanilla mob (a zombie, a skeleton) change sides — so we drive
 * it ourselves:
 * <ul>
 *   <li>Tensura's {@code temporaryOwner} is set to you. Tensura's
 *       {@code SubordinateHelper} reads that as "owned by you", so the rest of
 *       the mod (the Holy Field Stone, the Pack Leader's Mark, subordinate
 *       friendly-fire protection) treats the puppet as your side for free.</li>
 *   <li>{@link #onChangeTarget} vetoes any attempt by the puppet to target a
 *       player, a citizen, or anything else you own.</li>
 *   <li>{@link #tick} (once a second) points it at whatever attacks you, what
 *       you attack, or the nearest enemy near it; with nothing to fight it
 *       follows you (and teleports to you if left far behind).</li>
 * </ul>
 *
 * <p>Who can be dominated: a HOSTILE mob (a monster, or anything currently
 * attacking you) with no owner, whose EP is at most
 * {@value #MAX_EP_FRACTION}× yours. Never: bosses, faction-marked bosses,
 * raiders, rival garrison defenders, assassins, envoys, allied fighters, or
 * our colony races (goblin/orc/lizardman/dwarf/otherworlder — those join by
 * naming or taming and would confuse that pipeline).
 *
 * <p>State lives on the mob itself ({@code tm_dominated_by} in its persistent
 * data), so it survives restarts; {@link #PUPPETS} is just the list of loaded
 * puppets the tick walks, rebuilt from that tag as mobs load.
 */
public class OrbOfDominationItem extends Item {

    /** A mob can be dominated if its EP is at most this fraction of yours. */
    static final double MAX_EP_FRACTION = 0.5;
    static final double FIGHT_RADIUS = 16.0;
    static final double FOLLOW_DISTANCE = 6.0;
    static final double TELEPORT_DISTANCE = 32.0;

    static final String TAG_DOMINATED_BY = "tm_dominated_by";

    /** Loaded puppets: mob UUID → its dimension. */
    private static final Map<UUID, ResourceKey<Level>> PUPPETS = new HashMap<>();
    /** Old bodies whose orb was just moved onto the mob they converted into —
     *  read (and cleared) by the deferred check in {@link #onLeaveLevel}. */
    private static final java.util.Set<UUID> CARRIED_OVER = new java.util.HashSet<>();

    public OrbOfDominationItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC).fireResistant());
    }

    // ------------------------------------------------------------------
    // Hanging the orb on a mob
    // ------------------------------------------------------------------

    /** Right-click ON a mob. Runs before the mob's own right-click. Registered in ExampleMod. */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof OrbOfDominationItem)) return;
        if (!(event.getTarget() instanceof Mob mob)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof ServerPlayer sp) {
            tryDominate(level, sp, stack, mob);
        }
    }

    private static void tryDominate(ServerLevel level, ServerPlayer sp, ItemStack stack, Mob mob) {
        String refusal = refusalReason(sp, mob);
        if (refusal != null) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.orb_of_domination." + refusal)
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }

        ExistenceStorage ex = ExampleMod.readExistence(mob);
        if (ex != null) {
            ex.setTemporaryOwner(sp.getUUID());
            ex.markDirty();
        }
        mob.getPersistentData().putUUID(TAG_DOMINATED_BY, sp.getUUID());
        mob.setPersistenceRequired();          // a puppet must never despawn with the orb on it
        mob.setTarget(null);
        BrainUtils.setTargetOfEntity(mob, null);
        PUPPETS.put(mob.getUUID(), level.dimension());
        syncFlag(mob, true);

        if (!sp.getAbilities().instabuild) stack.shrink(1);   // the orb is now on the mob

        level.playSound(null, mob.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1.0f, 0.6f);
        level.sendParticles(ParticleTypes.WITCH, mob.getX(), mob.getEyeY(), mob.getZ(), 30, 0.4, 0.5, 0.4, 0.05);
        sp.sendSystemMessage(Component.translatable("item.tensura_minecolonies.orb_of_domination.dominated",
                mob.getName()).withStyle(ChatFormatting.DARK_PURPLE));
    }

    /** Why this mob can't be dominated (a lang-key suffix), or null if it can. */
    private static String refusalReason(ServerPlayer sp, Mob mob) {
        if (!mob.isAlive()) return "not_hostile";
        if (mob.getPersistentData().hasUUID(TAG_DOMINATED_BY)) return "already";
        if (mob instanceof AbstractEntityCitizen) return "not_hostile";
        if (mob.getType().is(Tags.EntityTypes.BOSSES)
                || mob.hasData(Attachments.FACTION_MARK.get())
                || mob.hasData(Attachments.ASSASSIN_TAG.get())) return "too_strong";
        if (mob.hasData(Attachments.RAID_TAG.get())
                || mob.hasData(Attachments.GARRISON_TAG.get())
                || mob.hasData(Attachments.ENVOY_TAG.get())
                || mob.hasData(Attachments.ALLY_TAG.get())) return "bound";
        if (Races.of(mob.getType()) != null) return "race";
        // Already owned by anyone (tamed, named, charmed) — no stealing.
        if (SubordinateHelper.getSubordinateOwnerUUID(mob) != null) return "owned";
        ExistenceStorage mobEx = ExampleMod.readExistence(mob);
        if (mobEx != null && mobEx.getPermanentOwner() != null) return "owned";
        // Hostile: a monster, or anything currently attacking you.
        boolean hostile = mob instanceof Enemy || mob.getTarget() == sp;
        if (!hostile) return "not_hostile";
        // EP gate — only the weaker-willed can be bound.
        ExistenceStorage playerEx = ExampleMod.readExistence(sp);
        double playerEp = playerEx == null ? 0 : playerEx.getEP();
        double mobEp = mobEx == null ? 0 : mobEx.getEP();
        if (mobEp > playerEp * MAX_EP_FRACTION) return "too_strong";
        return null;
    }

    // ------------------------------------------------------------------
    // Puppet behaviour — once a second from ExampleMod.onServerTickPost
    // ------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (PUPPETS.isEmpty()) return;
        Iterator<Map.Entry<UUID, ResourceKey<Level>>> it = PUPPETS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ResourceKey<Level>> entry = it.next();
            ServerLevel level = server.getLevel(entry.getValue());
            Entity e = level == null ? null : level.getEntity(entry.getKey());
            if (!(e instanceof Mob mob) || !mob.isAlive()) {
                it.remove();     // unloaded or gone; re-added on load (onJoinLevel)
                continue;
            }
            UUID owner = ownerOf(mob);
            if (owner == null) { it.remove(); continue; }
            drivePuppet(level, mob, owner);
        }
    }

    private static void drivePuppet(ServerLevel level, Mob mob, UUID owner) {
        // Keep Tensura's owner pointed at us (e.g. if something cleared it).
        ExistenceStorage ex = ExampleMod.readExistence(mob);
        if (ex != null && !owner.equals(ex.getTemporaryOwner())) {
            ex.setTemporaryOwner(owner);
            ex.markDirty();
        }

        LivingEntity current = mob.getTarget();
        if (current != null && (!current.isAlive() || isOwnSide(current, owner))) {
            mob.setTarget(null);
            BrainUtils.setTargetOfEntity(mob, null);
            current = null;
        }

        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        boolean ownerHere = player != null && player.level() == level;

        if (current == null) {
            LivingEntity pick = pickTarget(level, mob, owner, ownerHere ? player : null);
            if (pick != null) {
                mob.setTarget(pick);
                BrainUtils.setTargetOfEntity(mob, pick);
                current = pick;
            }
        }

        // Nothing to fight: stay with the owner, like a pet.
        if (current == null && ownerHere) {
            double dist = mob.distanceTo(player);
            if (dist > TELEPORT_DISTANCE) {
                Vec3 at = player.position().add(player.getLookAngle().scale(-2.0));
                mob.teleportTo(at.x, player.getY(), at.z);
                mob.getNavigation().stop();
            } else if (dist > FOLLOW_DISTANCE) {
                mob.getNavigation().moveTo(player, 1.2);
            }
        }

        // A faint purple shimmer so the puppet is easy to spot.
        level.sendParticles(new DustParticleOptions(new Vector3f(0.6f, 0.1f, 0.8f), 0.8f),
                mob.getX(), mob.getEyeY() - 0.3, mob.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
    }

    /** Whatever is attacking the owner, then what the owner attacked, then the nearest enemy. */
    private static LivingEntity pickTarget(ServerLevel level, Mob mob, UUID owner, ServerPlayer player) {
        if (player != null) {
            LivingEntity attacker = player.getLastHurtByMob();
            if (valid(attacker, mob, owner)) return attacker;
            LivingEntity victim = player.getLastHurtMob();
            if (valid(victim, mob, owner)) return victim;
        }
        List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class,
                mob.getBoundingBox().inflate(FIGHT_RADIUS),
                e -> e != mob && e.isAlive() && HolyFieldStoneItem.isEnemy(e, owner));
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : near) {
            double d = e.distanceToSqr(mob);
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }

    private static boolean valid(LivingEntity e, Mob mob, UUID owner) {
        return e != null && e != mob && e.isAlive() && !isOwnSide(e, owner)
                && e.distanceTo(mob) <= FIGHT_RADIUS * 1.5;
    }

    /** Players, citizens, and anything the owner owns (subordinates, pets, other puppets). */
    static boolean isOwnSide(LivingEntity e, UUID owner) {
        if (e instanceof Player) return true;
        if (e instanceof AbstractEntityCitizen) return true;
        if (e.hasData(Attachments.ALLY_TAG.get()) || e.hasData(Attachments.ENVOY_TAG.get())) return true;
        return owner.equals(SubordinateHelper.getSubordinateOwnerUUID(e));
    }

    static UUID ownerOf(LivingEntity e) {
        CompoundTag data = e.getPersistentData();
        return data.hasUUID(TAG_DOMINATED_BY) ? data.getUUID(TAG_DOMINATED_BY) : null;
    }

    // ------------------------------------------------------------------
    // NeoForge listeners (registered in ExampleMod)
    // ------------------------------------------------------------------

    /** A puppet may never turn on its master's side. */
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity self = event.getEntity();
        if (self.level().isClientSide()) return;
        UUID owner = ownerOf(self);
        if (owner == null) return;
        LivingEntity next = event.getNewAboutToBeSetTarget();
        if (next != null && isOwnSide(next, owner)) event.setCanceled(true);
    }

    /** Puppets loading back in (restart, chunk reload) rejoin the tick list. */
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        if (ownerOf(mob) == null) return;
        PUPPETS.put(mob.getUUID(), event.getLevel().dimension());
    }

    /** The puppet dies: the orb drops where it fell. */
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!(dead.level() instanceof ServerLevel level) || ownerOf(dead) == null) return;
        UUID owner = ownerOf(dead);
        dead.getPersistentData().remove(TAG_DOMINATED_BY);
        PUPPETS.remove(dead.getUUID());
        syncFlag(dead, false);
        dropOrb(level, dead.getX(), dead.getY(), dead.getZ(), owner, dead.getName());
    }

    /**
     * The puppet is removed WITHOUT dying — the world was switched to Peaceful
     * (which discards hostile mobs, and a puppet is not "tamed"), or a command
     * or another mod discarded it. No death event fires for that, so the orb
     * used to vanish with the mob. Drop it here instead.
     *
     * <p>Only a real removal counts: a chunk unloading or a dimension change
     * also takes the mob out of the level, with the orb still on it.</p>
     */
    public static void onLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof LivingEntity gone)) return;
        if (gone.getRemovalReason() != Entity.RemovalReason.DISCARDED) return;
        UUID owner = ownerOf(gone);
        if (owner == null) return;   // not a puppet, or it died (tag already cleared)

        UUID goneId = gone.getUUID();
        double x = gone.getX(), y = gone.getY(), z = gone.getZ();
        Component name = gone.getName();
        // Decide a moment later, not here. A CONVERSION (zombie → drowned)
        // also discards the old body, and its event — which moves the orb to
        // the new body — only fires after this one; dropping now would hand
        // out a second orb. The tag is left on the old body so onConversion
        // can still read it. Deferring also keeps the item spawn out of the
        // level's own entity-removal bookkeeping.
        level.getServer().tell(new net.minecraft.server.TickTask(level.getServer().getTickCount(), () -> {
            PUPPETS.remove(goneId);
            if (CARRIED_OVER.remove(goneId)) return;   // the orb moved to the converted mob
            dropOrb(level, x, y, z, owner, name);
        }));
    }

    /** Put the orb on the ground where its puppet was and tell the owner. */
    private static void dropOrb(ServerLevel level, double x, double y, double z, UUID owner, Component puppetName) {
        ItemEntity drop = new ItemEntity(level, x, y + 0.5, z,
                new ItemStack(ExampleMod.ORB_OF_DOMINATION.get()));
        drop.setUnlimitedLifetime();          // never despawns — it's a Covenant relic
        drop.setDefaultPickUpDelay();
        level.addFreshEntity(drop);
        level.sendParticles(ParticleTypes.WITCH, x, y + 1.0, z, 20, 0.3, 0.4, 0.3, 0.05);

        ServerPlayer player = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(Component.translatable("item.tensura_minecolonies.orb_of_domination.dropped",
                    puppetName).withStyle(ChatFormatting.DARK_PURPLE));
        }
    }

    /** Zombie → drowned, skeleton → stray, …: the orb stays on the converted mob. */
    public static void onConversion(LivingConversionEvent.Post event) {
        LivingEntity from = event.getEntity();
        if (!(from.level() instanceof ServerLevel level)) return;
        UUID owner = ownerOf(from);
        if (owner == null || !(event.getOutcome() instanceof Mob to)) return;
        to.getPersistentData().putUUID(TAG_DOMINATED_BY, owner);
        to.setPersistenceRequired();
        ExistenceStorage ex = ExampleMod.readExistence(to);
        if (ex != null) { ex.setTemporaryOwner(owner); ex.markDirty(); }
        from.getPersistentData().remove(TAG_DOMINATED_BY);   // so the old body's removal drops nothing
        CARRIED_OVER.add(from.getUUID());                      // …including the deferred onLeaveLevel check
        PUPPETS.remove(from.getUUID());
        PUPPETS.put(to.getUUID(), level.dimension());
        syncFlag(to, true);
    }

    // ------------------------------------------------------------------
    // Client sync — the pendant render (OrbOfDominationClientHandler)
    // ------------------------------------------------------------------

    private static void syncFlag(Entity mob, boolean on) {
        PacketDistributor.sendToPlayersTrackingEntity(mob,
                new Networking.SyncDominatedPayload(mob.getUUID(), on));
    }

    /** StartTracking resync — called from ExampleMod.onStartTracking. */
    static void resyncOnTracking(ServerPlayer player, Entity target) {
        if (target instanceof LivingEntity le && ownerOf(le) != null) {
            PacketDistributor.sendToPlayer(player, new Networking.SyncDominatedPayload(target.getUUID(), true));
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tensura_minecolonies.orb_of_domination.desc")
                .withStyle(ChatFormatting.GRAY));
    }

    /** Forget everything this class keeps in memory about the running world.
     *  Called when the server stops (ExampleMod.onServerStopped): these fields
     *  are static, so in single-player they would otherwise carry over from
     *  one world into the next one opened in the same game session. */
    static void resetSessionState() {
        PUPPETS.clear();
        CARRIED_OVER.clear();
    }
}
