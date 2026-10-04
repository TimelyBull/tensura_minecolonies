package com.example.examplemod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import io.github.manasmods.tensura.damage.TensuraDamageTypes;
import io.github.manasmods.tensura.registry.effect.TensuraMobEffects;
import io.github.manasmods.tensura.util.SubordinateHelper;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Holy Field Stone — Falmuth's Covenant reward (2026-09-26, developer
 * design).
 *
 * <p>Canon: Falmuth and the Western Holy Church raised a great barrier over
 * Tempest that sealed the monsters' magic before the invasion. Using the stone
 * raises a field of {@value #RADIUS}-block radius around where it was used for
 * {@value #DURATION_SECONDS} seconds. ENEMIES inside are sealed — Tensura's own
 * ANTI_SKILL (checked by every skill's {@code Skill.isAffectedByStatus}) and
 * ANTI_MAGIC (checked by every spell's {@code isCastingBlocked}) — and take
 * light HOLY damage credited to the user, so kills count as the player's.
 *
 * <p>Unlike the canon field it never harms the user's side (a majin player and
 * their monster subordinates would otherwise be caught in it): the user, other
 * players, the user's own subordinates / tamed pets, colony citizens and
 * visitors, and allied fighters are all excluded, as are passive animals. An
 * "enemy" is a hostile monster, a raider, a rival garrison defender, or any mob
 * currently targeting the user or one of their subordinates.
 *
 * <p>Fields are transient (a server restart ends one early — they last 30 s).
 * Cooldown: {@value #COOLDOWN_MINUTES} real-time minutes, persisted on the
 * stone's CUSTOM_DATA.
 */
public class HolyFieldStoneItem extends Item {

    static final double RADIUS = 12.0;
    static final int DURATION_SECONDS = 30;
    static final long COOLDOWN_MINUTES = 30;
    static final float HOLY_DAMAGE_PER_SECOND = 2.0f;   // one heart
    /** Seal effects last a little over the refresh interval, so they lapse ~2 s after leaving. */
    static final int SEAL_EFFECT_TICKS = 40;

    private static final long COOLDOWN_MS = COOLDOWN_MINUTES * 60_000L;
    private static final String TAG_READY_AT = "tm_holy_field_ready_at";

    /** One raised field. */
    private record Field(ResourceKey<Level> dimension, Vec3 center, UUID owner, long endsAtGameTime) {}

    private static final List<Field> ACTIVE = new ArrayList<>();

    public HolyFieldStoneItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC).fireResistant());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.success(stack);
        }
        long now = System.currentTimeMillis();
        long readyAt = readyAt(stack);
        if (now < readyAt) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.holy_field_stone.recharging",
                    minutesLeft(readyAt, now)).withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }
        Vec3 center = sp.position();
        ACTIVE.add(new Field(serverLevel.dimension(), center, sp.getUUID(),
                serverLevel.getGameTime() + DURATION_SECONDS * 20L));

        setReadyAt(stack, now + COOLDOWN_MS);
        sp.getCooldowns().addCooldown(this, (int) (COOLDOWN_MS / 50));
        serverLevel.playSound(null, BlockPos.containing(center), SoundEvents.BEACON_ACTIVATE,
                SoundSource.PLAYERS, 1.0f, 1.2f);
        serverLevel.sendParticles(ParticleTypes.FLASH, center.x, center.y + 1.0, center.z, 1, 0, 0, 0, 0);
        sp.sendSystemMessage(Component.translatable("item.tensura_minecolonies.holy_field_stone.raised",
                DURATION_SECONDS).withStyle(ChatFormatting.YELLOW));
        return InteractionResultHolder.success(stack);
    }

    // ------------------------------------------------------------------
    // Field driver — called every 10 ticks from ExampleMod.onServerTickPost
    // ------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        boolean pulse = server.getTickCount() % 20 == 0;   // effects + damage once a second
        Iterator<Field> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Field f = it.next();
            ServerLevel level = server.getLevel(f.dimension());
            if (level == null || level.getGameTime() >= f.endsAtGameTime()) {
                it.remove();
                if (level != null) {
                    level.playSound(null, BlockPos.containing(f.center()), SoundEvents.BEACON_DEACTIVATE,
                            SoundSource.PLAYERS, 0.8f, 1.2f);
                }
                continue;
            }
            drawField(level, f);
            if (pulse) sealEnemies(level, f);
        }
    }

    private static void drawField(ServerLevel level, Field f) {
        Vec3 c = f.center();
        // A ring of light at the edge, and a faint rising sparkle inside.
        for (int i = 0; i < 36; i++) {
            double a = (Math.PI * 2 * i) / 36.0;
            level.sendParticles(ParticleTypes.END_ROD,
                    c.x + Math.cos(a) * RADIUS, c.y + 0.2, c.z + Math.sin(a) * RADIUS, 1, 0, 0.05, 0, 0);
        }
        level.sendParticles(ParticleTypes.GLOW, c.x, c.y + 1.5, c.z, 12, RADIUS * 0.6, 1.2, RADIUS * 0.6, 0.0);
    }

    private static void sealEnemies(ServerLevel level, Field f) {
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(f.owner());
        DamageSource holy = new DamageSource(
                level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(TensuraDamageTypes.HOLY_DAMAGE),
                owner);   // credited to the user (conquest "your effort" rule, kill credit)
        AABB box = new AABB(f.center(), f.center()).inflate(RADIUS, RADIUS * 0.5, RADIUS);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                x -> x.isAlive() && x.position().distanceTo(f.center()) <= RADIUS)) {
            if (!isFieldEnemy(e, f.owner())) continue;
            e.addEffect(new MobEffectInstance(TensuraMobEffects.ANTI_SKILL, SEAL_EFFECT_TICKS, 0, false, true));
            e.addEffect(new MobEffectInstance(TensuraMobEffects.ANTI_MAGIC, SEAL_EFFECT_TICKS, 0, false, true));
            e.hurt(holy, HOLY_DAMAGE_PER_SECOND);
        }
    }

    /**
     * Who the Holy Field seals: everything {@link #isEnemy} accepts, PLUS wild
     * Tensura monsters and anything attacking a colony citizen.
     *
     * <p>{@code isEnemy} recognises a hostile mob by vanilla's {@code Enemy}
     * marker, which no Tensura entity carries — so on its own the field
     * skipped every wild Tensura monster that was not already attacking the
     * user. Tensura registers its monsters in the MONSTER category; that is
     * the test here. The nameable races (goblin, orc, lizardman, dwarf) are in
     * that category too but are the player's future citizens, so they only
     * count when they are attacking our side. Anything another player owns is
     * left alone.</p>
     *
     * <p>Kept separate from {@code isEnemy} on purpose: the Orb of Domination
     * uses that one to pick a puppet's targets, and this wider rule has not
     * been checked against it.</p>
     */
    static boolean isFieldEnemy(LivingEntity e, UUID owner) {
        if (isEnemy(e, owner)) return true;
        if (e instanceof Player || e instanceof AbstractEntityCitizen) return false;
        if (e.hasData(Attachments.ALLY_TAG.get()) || e.hasData(Attachments.ENVOY_TAG.get())
                || e.hasData(Attachments.SETTLER_TAG.get())) return false;
        // Owned by anyone (the user's own were already excluded by isEnemy's
        // checks; this covers other players' subordinates and pets).
        if (SubordinateHelper.getSubordinateOwnerUUID(e) != null) return false;
        if (e instanceof TamableAnimal t && t.getOwnerUUID() != null) return false;

        if (e instanceof Mob m && m.getTarget() instanceof AbstractEntityCitizen) return true;
        return e.getType().getCategory() == net.minecraft.world.entity.MobCategory.MONSTER
                && Races.of(e.getType()) == null;
    }

    /** Only the user's enemies are sealed — never their own side, never players. */
    static boolean isEnemy(LivingEntity e, UUID owner) {
        if (e instanceof Player) return false;
        if (e instanceof AbstractEntityCitizen) return false;            // any colony's citizens / visitors
        if (e.hasData(Attachments.ALLY_TAG.get())) return false;          // allied raid fighters
        if (e.hasData(Attachments.ENVOY_TAG.get())) return false;
        if (owner.equals(SubordinateHelper.getSubordinateOwnerUUID(e))) return false;   // user's subordinates
        if (e instanceof TamableAnimal t && owner.equals(t.getOwnerUUID())) return false; // user's pets

        if (e instanceof Enemy) return true;                              // hostile monsters
        if (e.hasData(Attachments.RAID_TAG.get())) return true;           // raiders
        if (e.hasData(Attachments.GARRISON_TAG.get())) return true;       // rival garrison defenders
        if (e instanceof Mob m && m.getTarget() != null) {                 // anything hunting our side
            LivingEntity t = m.getTarget();
            return owner.equals(t.getUUID()) || owner.equals(SubordinateHelper.getSubordinateOwnerUUID(t));
        }
        return false;
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
        tooltip.add(Component.translatable("item.tensura_minecolonies.holy_field_stone.desc")
                .withStyle(ChatFormatting.GRAY));
        long now = System.currentTimeMillis();
        long readyAt = readyAt(stack);
        tooltip.add(now < readyAt
                ? Component.translatable("item.tensura_minecolonies.holy_field_stone.tooltip_recharging",
                        minutesLeft(readyAt, now)).withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("item.tensura_minecolonies.holy_field_stone.tooltip_ready")
                        .withStyle(ChatFormatting.YELLOW));
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
        ACTIVE.clear();
    }
}
