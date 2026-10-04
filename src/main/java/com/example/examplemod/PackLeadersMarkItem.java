package com.example.examplemod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import io.github.manasmods.tensura.util.SubordinateHelper;
import net.tslat.smartbrainlib.util.BrainUtils;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The Pack Leader's Mark — Eurazania's Covenant reward (2026-09-26, developer
 * design).
 *
 * <p>Canon: Eurazania is Carrion's beast kingdom, and "The Great Hunt" is its
 * Covenant task. The Mark turns the player's subordinates into a hunting pack.
 * Right-click a mob (up to {@value #RANGE} blocks away) to mark it as prey for
 * {@value #DURATION_SECONDS} seconds:
 * <ul>
 *   <li>the prey glows (visible through walls);</li>
 *   <li>every one of the player's subordinates within {@value #PACK_RADIUS}
 *       blocks of the prey drops its current target and hunts it — re-asserted
 *       once a second, written to BOTH vanilla {@code setTarget} and the
 *       SmartBrainLib ATTACK_TARGET memory (the raid-steer idiom);</li>
 *   <li>those subordinates deal ×{@value #PACK_DAMAGE_MULTIPLIER} damage to
 *       it;</li>
 *   <li>killing it heals every pack member nearby by
 *       {@value #KILL_HEAL_FRACTION} of their max health.</li>
 * </ul>
 * One mark per player; a new mark replaces the old one. Your own side
 * (players, citizens, allies, envoys, your own subordinates and pets) can't be
 * marked. Marks are transient (a restart ends one early).
 *
 * <p>Cooldown: {@value #COOLDOWN_MINUTES} real-time minutes, persisted on the
 * item's CUSTOM_DATA; the cooldown only starts when a mark actually lands.
 */
public class PackLeadersMarkItem extends Item {

    static final double RANGE = 32.0;
    static final int DURATION_SECONDS = 60;
    static final double PACK_RADIUS = 48.0;
    static final float PACK_DAMAGE_MULTIPLIER = 1.5f;
    static final float KILL_HEAL_FRACTION = 0.25f;
    static final long COOLDOWN_MINUTES = 15;

    private static final long COOLDOWN_MS = COOLDOWN_MINUTES * 60_000L;
    private static final String TAG_READY_AT = "tm_pack_mark_ready_at";

    /** One active mark: which mob is the prey, and which player's pack hunts it. */
    private record Mark(ResourceKey<Level> dimension, UUID prey, long endsAtGameTime) {}

    /** Owner (player) UUID → their current mark. */
    private static final Map<UUID, Mark> MARKS = new HashMap<>();

    public PackLeadersMarkItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC).fireResistant());
    }

    // ------------------------------------------------------------------
    // Using the item
    // ------------------------------------------------------------------

    /** Right-click in the air: mark whatever mob the player is looking at. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.success(stack);
        }
        LivingEntity prey = lookedAtMob(sp);
        if (prey == null) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.pack_mark.no_target")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }
        return tryMark(serverLevel, sp, stack, prey)
                ? InteractionResultHolder.success(stack) : InteractionResultHolder.fail(stack);
    }

    /**
     * Right-click directly ON a mob. Runs before the mob's own right-click, so
     * marking a tameable / tradeable mob doesn't also open its menu. Registered
     * in ExampleMod.
     */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof PackLeadersMarkItem item)) return;
        if (!(event.getTarget() instanceof LivingEntity prey)) return;
        event.setCanceled(true);
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof ServerPlayer sp) {
            item.tryMark(level, sp, stack, prey);
        }
    }

    private boolean tryMark(ServerLevel level, ServerPlayer sp, ItemStack stack, LivingEntity prey) {
        long now = System.currentTimeMillis();
        long readyAt = readyAt(stack);
        if (now < readyAt) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.pack_mark.recharging",
                    minutesLeft(readyAt, now)).withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        if (!canBeMarked(prey, sp.getUUID())) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.pack_mark.not_prey")
                    .withStyle(ChatFormatting.GRAY), true);
            return false;
        }

        MARKS.put(sp.getUUID(), new Mark(level.dimension(), prey.getUUID(),
                level.getGameTime() + DURATION_SECONDS * 20L));
        prey.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_SECONDS * 20, 0, false, false));
        int pack = steerPack(level, sp.getUUID(), prey);

        setReadyAt(stack, now + COOLDOWN_MS);
        sp.getCooldowns().addCooldown(this, (int) (COOLDOWN_MS / 50));
        level.playSound(null, sp.blockPosition(), SoundEvents.WOLF_HOWL, SoundSource.PLAYERS, 1.0f, 0.8f);
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, prey.getX(), prey.getEyeY() + 0.5, prey.getZ(),
                6, 0.4, 0.3, 0.4, 0.0);
        sp.sendSystemMessage(Component.translatable("item.tensura_minecolonies.pack_mark.marked",
                prey.getName(), pack, DURATION_SECONDS).withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** The living mob under the player's crosshair, up to RANGE blocks away. */
    private static LivingEntity lookedAtMob(ServerPlayer sp) {
        Vec3 eye = sp.getEyePosition();
        Vec3 end = eye.add(sp.getLookAngle().scale(RANGE));
        AABB sweep = sp.getBoundingBox().expandTowards(sp.getLookAngle().scale(RANGE)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(sp.level(), sp, eye, end, sweep,
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator());
        return hit != null && hit.getEntity() instanceof LivingEntity le ? le : null;
    }

    /** Anything can be prey EXCEPT your own side. */
    static boolean canBeMarked(LivingEntity e, UUID owner) {
        if (!e.isAlive()) return false;
        if (e instanceof Player) return false;
        if (e instanceof AbstractEntityCitizen) return false;          // any colony's citizens / visitors
        if (e.hasData(Attachments.ALLY_TAG.get())) return false;
        if (e.hasData(Attachments.ENVOY_TAG.get())) return false;
        if (owner.equals(SubordinateHelper.getSubordinateOwnerUUID(e))) return false;
        if (e instanceof TamableAnimal t && owner.equals(t.getOwnerUUID())) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // The hunt — called once a second from ExampleMod.onServerTickPost
    // ------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (MARKS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Mark>> it = MARKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Mark> entry = it.next();
            UUID owner = entry.getKey();
            Mark mark = entry.getValue();
            ServerLevel level = server.getLevel(mark.dimension());
            Entity preyEntity = level == null ? null : level.getEntity(mark.prey());
            boolean expired = level == null || level.getGameTime() >= mark.endsAtGameTime();
            if (expired || !(preyEntity instanceof LivingEntity prey) || !prey.isAlive()) {
                if (level != null && preyEntity instanceof LivingEntity prey && prey.isAlive()) {
                    callOffPack(level, owner, prey);
                }
                it.remove();
                continue;
            }
            steerPack(level, owner, prey);
        }
    }

    /** Point every nearby subordinate of {@code owner} at the prey. Returns how many. */
    private static int steerPack(ServerLevel level, UUID owner, LivingEntity prey) {
        int count = 0;
        for (Mob member : packAround(level, owner, prey)) {
            if (BrainUtils.getTargetOfEntity(member) != prey) BrainUtils.setTargetOfEntity(member, prey);
            if (member.getTarget() != prey) member.setTarget(prey);
            count++;
        }
        return count;
    }

    /** Mark ran out with the prey still alive: stop chasing it. */
    private static void callOffPack(ServerLevel level, UUID owner, LivingEntity prey) {
        for (Mob member : packAround(level, owner, prey)) {
            if (member.getTarget() == prey) member.setTarget(null);
            if (BrainUtils.getTargetOfEntity(member) == prey) BrainUtils.setTargetOfEntity(member, null);
        }
    }

    private static List<Mob> packAround(ServerLevel level, UUID owner, LivingEntity prey) {
        return level.getEntitiesOfClass(Mob.class, prey.getBoundingBox().inflate(PACK_RADIUS),
                m -> m.isAlive() && m != prey && owner.equals(SubordinateHelper.getSubordinateOwnerUUID(m)));
    }

    /** The owner of the mark on this mob, or null if it isn't marked. */
    private static UUID markOwnerOf(LivingEntity e) {
        for (Map.Entry<UUID, Mark> entry : MARKS.entrySet()) {
            if (entry.getValue().prey().equals(e.getUUID())) return entry.getKey();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Damage bonus + kill heal (NeoForge listeners, registered in ExampleMod)
    // ------------------------------------------------------------------

    /** Pack members hit the prey harder. */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (MARKS.isEmpty() || event.getEntity().level().isClientSide()) return;
        UUID owner = markOwnerOf(event.getEntity());
        if (owner == null) return;
        if (event.getSource().getEntity() instanceof LivingEntity attacker
                && owner.equals(SubordinateHelper.getSubordinateOwnerUUID(attacker))) {
            event.setAmount(event.getAmount() * PACK_DAMAGE_MULTIPLIER);
        }
    }

    /** Prey down: heal the pack and end the mark. */
    public static void onDeath(LivingDeathEvent event) {
        if (MARKS.isEmpty() || !(event.getEntity().level() instanceof ServerLevel level)) return;
        LivingEntity prey = event.getEntity();
        UUID owner = markOwnerOf(prey);
        if (owner == null) return;
        MARKS.remove(owner);
        for (Mob member : packAround(level, owner, prey)) {
            member.heal(member.getMaxHealth() * KILL_HEAL_FRACTION);
            level.sendParticles(ParticleTypes.HEART, member.getX(), member.getEyeY() + 0.4, member.getZ(),
                    2, 0.3, 0.2, 0.3, 0.0);
        }
        level.playSound(null, prey.blockPosition(), SoundEvents.WOLF_HOWL, SoundSource.PLAYERS, 1.0f, 1.1f);
    }

    // ------------------------------------------------------------------
    // Cooldown plumbing (same shape as the other covenant relics)
    // ------------------------------------------------------------------

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide() || !(entity instanceof ServerPlayer sp)) return;
        if (level.getGameTime() % 20 != 0) return;
        long left = readyAt(stack) - System.currentTimeMillis();
        if (left > 1000 && !sp.getCooldowns().isOnCooldown(this)) {
            sp.getCooldowns().addCooldown(this, (int) (left / 50));
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tensura_minecolonies.pack_mark.desc")
                .withStyle(ChatFormatting.GRAY));
        long now = System.currentTimeMillis();
        long readyAt = readyAt(stack);
        tooltip.add(now < readyAt
                ? Component.translatable("item.tensura_minecolonies.pack_mark.tooltip_recharging",
                        minutesLeft(readyAt, now)).withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("item.tensura_minecolonies.pack_mark.tooltip_ready")
                        .withStyle(ChatFormatting.GOLD));
    }

    private static long readyAt(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getLong(TAG_READY_AT);
    }

    private static void setReadyAt(ItemStack stack, long time) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putLong(TAG_READY_AT, time);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static long minutesLeft(long readyAt, long now) {
        return Math.max(1, (readyAt - now + 59_999L) / 60_000L);
    }

    /** Forget everything this class keeps in memory about the running world.
     *  Called when the server stops (ExampleMod.onServerStopped): these fields
     *  are static, so in single-player they would otherwise carry over from
     *  one world into the next one opened in the same game session. */
    static void resetSessionState() {
        MARKS.clear();
    }
}
