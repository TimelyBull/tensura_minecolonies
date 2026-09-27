package com.example.examplemod;

import java.util.List;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;

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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * The Otherworld Summoning Codex — Leon's Covenant reward (2026-09-26,
 * developer design).
 *
 * <p>Canon: Leon Cromwell spent centuries performing otherworld summonings.
 * Using the codex performs that rite at the player's town hall: one of
 * Tensura's eight summonable otherworlders (equal odds, like Tensura's own
 * Summon Otherworlder spell) arrives and joins the colony as an OTHERWORLDER
 * race citizen, named after the character. Duplicates are allowed.
 *
 * <p>Refuses — WITHOUT spending the cooldown — when the player owns no colony,
 * the colony is full, or the town hall's "move in" setting is off.
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
        IColony colony = IColonyManager.getInstance().getIColonyByOwner(serverLevel, sp);
        if (colony == null) {
            refuse(sp, "item.tensura_minecolonies.otherworld_codex.no_colony");
            return InteractionResultHolder.fail(stack);
        }
        if (!ExampleMod.colonyAllowsMoveIn(colony)) {
            refuse(sp, "item.tensura_minecolonies.otherworld_codex.no_move_in");
            return InteractionResultHolder.fail(stack);
        }
        if (colony.getCitizenManager().getCurrentCitizenCount()
                >= colony.getCitizenManager().getMaxCitizens()) {
            refuse(sp, "item.tensura_minecolonies.otherworld_codex.full");
            return InteractionResultHolder.fail(stack);
        }

        // Equal odds across the eight characters (Tensura's own distribution is
        // 12.5% each). Skip any character type a datapack/version removed.
        List<ResourceLocation> pool = new java.util.ArrayList<>();
        for (ResourceLocation id : Races.otherworlderTypes()) {
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) pool.add(id);
        }
        if (pool.isEmpty()) {
            refuse(sp, "item.tensura_minecolonies.otherworld_codex.failed");
            return InteractionResultHolder.fail(stack);
        }
        ResourceLocation chosen = pool.get(serverLevel.getRandom().nextInt(pool.size()));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(chosen);
        String name = type.getDescription().getString();

        ICitizenData arrived = ExampleMod.summonOtherworlderCitizen(serverLevel, colony, chosen, name);
        if (arrived == null) {
            refuse(sp, "item.tensura_minecolonies.otherworld_codex.failed");
            return InteractionResultHolder.fail(stack);
        }

        setReadyAt(stack, now + COOLDOWN_MS);
        sp.getCooldowns().addCooldown(this, (int) (COOLDOWN_MS / 50));

        // The rite, at the town hall.
        BlockPos at = colony.getServerBuildingManager().hasTownHall()
                ? colony.getServerBuildingManager().getTownHall().getPosition()
                : colony.getCenter();
        ServerLevel colonyLevel = serverLevel.getServer().getLevel(colony.getDimension());
        if (colonyLevel != null) {
            colonyLevel.sendParticles(ParticleTypes.PORTAL, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5,
                    120, 1.0, 1.0, 1.0, 0.5);
            colonyLevel.sendParticles(ParticleTypes.ENCHANT, at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5,
                    80, 1.5, 0.1, 1.5, 0.8);
            colonyLevel.playSound(null, at, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.5f, 1.4f);
        }
        serverLevel.playSound(null, sp.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0f, 0.8f);
        sp.sendSystemMessage(Component.translatable("item.tensura_minecolonies.otherworld_codex.summoned",
                name, colony.getName()).withStyle(ChatFormatting.GOLD));
        ExampleMod.LOGGER.info("[TM] codex: {} summoned {} ({}) into colony {} ('{}')",
                sp.getName().getString(), name, chosen, colony.getID(), colony.getName());
        return InteractionResultHolder.success(stack);
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

    private static void refuse(ServerPlayer sp, String key) {
        sp.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY), true);
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
