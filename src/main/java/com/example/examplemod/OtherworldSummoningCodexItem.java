package com.example.examplemod;

import java.util.ArrayList;
import java.util.List;

import io.github.manasmods.tensura.data.otherworlder.OtherworlderSpawnDistribution;
import io.github.manasmods.tensura.entity.template.subclass.ISubordinate;
import io.github.manasmods.tensura.entity.variant.MagicCircleVariant;
import io.github.manasmods.tensura.registry.data.TensuraCustomData;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The Otherworlder Summoning Codex — Leon's Covenant reward (2026-09-26,
 * developer design).
 *
 * <p>Reproduces Tensura's own Summon Otherworlder spell as an item (the spell's
 * class needs a caster's live skill instance, so it isn't called directly —
 * its steps are): pick a character from Tensura's
 * {@code otherworlder_spawn_distribution} data (weighted by each entry's
 * chance — 12.5% each by default, and datapack-overridable), open the
 * otherworlder summoning circle, spawn the character beside the player, and
 * tame it to them (temporary owner + tame, exactly as the spell does). Unlike
 * the spell there's no fail chance and no magicule cost — it's a relic.
 *
 * <p>The tame is what makes it the player's: {@link
 * ExampleMod#tryAdoptTamedOtherworlder} registers it as a subordinate, so it
 * appears in the G roster and can be sent to the colony. Otherworlders are
 * never named.
 *
 * <p>Cooldown: {@value #COOLDOWN_HOURS} real-time hours, stamped on the codex's
 * CUSTOM_DATA so it survives relogs (vanilla item cooldowns don't).
 */
public class OtherworldSummoningCodexItem extends Item {

    static final long COOLDOWN_HOURS = 2;
    private static final long COOLDOWN_MS = COOLDOWN_HOURS * 60L * 60L * 1000L;
    private static final String TAG_READY_AT = "tm_codex_ready_at";

    public OtherworldSummoningCodexItem(Properties properties) {
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
            sp.displayClientMessage(Component.translatable(
                    "item.tensura_minecolonies.otherworld_codex.recharging", minutesLeft(readyAt, now))
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }

        ResourceLocation chosen = pickCharacter(serverLevel);
        EntityType<?> type = chosen == null ? null
                : BuiltInRegistries.ENTITY_TYPE.getOptional(chosen).orElse(null);
        Entity created = type == null ? null : type.create(serverLevel);
        if (!(created instanceof Mob mob) || !(created instanceof ISubordinate subordinate)) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.otherworld_codex.failed")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }

        // Where the spell would place it: a couple of blocks in front of the caster.
        Vec3 look = sp.getLookAngle();
        Vec3 at = sp.position().add(look.x * 2.5, 0, look.z * 2.5);
        BlockPos atPos = BlockPos.containing(at);
        mob.moveTo(at.x, at.y, at.z, sp.getYRot() + 180f, 0f);
        mob.finalizeSpawn(serverLevel, serverLevel.getCurrentDifficultyAt(atPos), MobSpawnType.TRIGGERED, null);

        serverLevel.addFreshEntity(mob);
        // Tame it to the player — the spell's own two steps, in the spell's
        // order (after the mob is in the world).
        ExistenceStorage ex = ExampleMod.readExistence(mob);
        if (ex != null) {
            ex.setTemporaryOwner(sp.getUUID());
            ex.markDirty();
        }
        subordinate.tame(sp);

        // The tame event registers it; call directly too in case that event
        // didn't fire for this entity (idempotent — it won't double-register).
        ExampleMod.tryAdoptTamedOtherworlder(serverLevel, mob, sp.getUUID(), sp);

        ExampleMod.spawnMagicCircle(serverLevel, sp, at, MagicCircleVariant.OTHERWORLDER);
        serverLevel.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 1.0, at.z, 80, 0.6, 1.0, 0.6, 0.4);
        serverLevel.playSound(null, atPos, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.5f, 1.4f);

        setReadyAt(stack, now + COOLDOWN_MS);
        sp.getCooldowns().addCooldown(this, (int) (COOLDOWN_MS / 50));
        ExampleMod.LOGGER.info("[TM] codex: {} summoned {} ({})",
                sp.getName().getString(), mob.getName().getString(), chosen);
        return InteractionResultHolder.success(stack);
    }

    /** Weighted pick from Tensura's otherworlder spawn-distribution data; falls
     *  back to our eight known characters (equal odds) if the data is empty. */
    private static ResourceLocation pickCharacter(ServerLevel level) {
        List<OtherworlderSpawnDistribution> entries = new ArrayList<>();
        try {
            level.registryAccess().registry(TensuraCustomData.OTHERWORLDER_SPAWN_DISTRIBUTION)
                    .ifPresent(reg -> reg.forEach(entries::add));
        } catch (Throwable ignored) { }
        entries.removeIf(e -> e.chance() <= 0 || !BuiltInRegistries.ENTITY_TYPE.containsKey(e.entity()));
        if (!entries.isEmpty()) {
            double total = 0;
            for (OtherworlderSpawnDistribution e : entries) total += e.chance();
            double roll = level.getRandom().nextDouble() * total;
            for (OtherworlderSpawnDistribution e : entries) {
                roll -= e.chance();
                if (roll <= 0) return e.entity();
            }
            return entries.get(entries.size() - 1).entity();
        }
        List<ResourceLocation> fallback = Races.otherworlderTypes();
        return fallback.get(level.getRandom().nextInt(fallback.size()));
    }

    /** Keep the hotbar overlay in step with the persisted timer across relogs. */
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
        tooltip.add(Component.translatable("item.tensura_minecolonies.otherworld_codex.desc")
                .withStyle(ChatFormatting.GRAY));
        long now = System.currentTimeMillis();
        long readyAt = readyAt(stack);
        tooltip.add(now < readyAt
                ? Component.translatable("item.tensura_minecolonies.otherworld_codex.tooltip_recharging",
                        minutesLeft(readyAt, now)).withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("item.tensura_minecolonies.otherworld_codex.tooltip_ready")
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
}
