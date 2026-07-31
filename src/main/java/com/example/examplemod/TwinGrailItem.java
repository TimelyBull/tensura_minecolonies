package com.example.examplemod;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The "Twin Grail" — Luminous's Covenant reward, the two-faced chalice she forges
 * from the player's holy-water and blood chalices at the end of the Trial of Light
 * &amp; Dark.
 *
 * <p>Right-clicking it acts on her duality, keyed to the sky:
 * <ul>
 *   <li><b>By day</b> — the holy face: a burst of healing that also cleanses all
 *       negative effects, plus lingering Regeneration and Absorption.</li>
 *   <li><b>By night</b> — the vampire's face: Strength, Speed, and a short window of
 *       life-drain (melee hits heal you), the goddess's true nature let loose.</li>
 * </ul>
 * A shared cooldown gates both. The night-time life-drain is realised by
 * {@link #NIGHT_BUFF_TICKS}-long marker handled in {@code ExampleMod}'s combat hook.
 */
public class TwinGrailItem extends Item {

    static final int COOLDOWN_TICKS  = 20 * 45;   // 45 s, both faces
    static final int DAY_BUFF_TICKS  = 20 * 30;   // 30 s of regen/absorption
    static final int NIGHT_BUFF_TICKS = 20 * 30;  // 30 s of might + life-drain

    /** Fraction of melee damage returned as healing while the night blessing holds. */
    static final float NIGHT_LIFESTEAL = 0.25f;

    public TwinGrailItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC).fireResistant());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.pass(stack);
        }
        if (sp.getCooldowns().isOnCooldown(stack.getItem())) {
            return InteractionResultHolder.pass(stack);
        }

        boolean day = serverLevel.isDay();
        if (day) {
            // Holy face — heal, cleanse, and bless.
            sp.removeAllEffects();
            sp.heal(sp.getMaxHealth());
            sp.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DAY_BUFF_TICKS, 1));
            sp.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, DAY_BUFF_TICKS, 1));
            serverLevel.playSound(null, sp.blockPosition(),
                    SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8f, 1.6f);
            sp.sendSystemMessage(Component.translatable("item.tensura_minecolonies.twin_grail.day")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            // Vampire's face — might + life-drain (drain handled in the combat hook).
            sp.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, NIGHT_BUFF_TICKS, 1));
            sp.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, NIGHT_BUFF_TICKS, 0));
            ExampleMod.markGrailNightBlessing(sp, NIGHT_BUFF_TICKS);
            serverLevel.playSound(null, sp.blockPosition(),
                    SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.4f, 1.8f);
            sp.sendSystemMessage(Component.translatable("item.tensura_minecolonies.twin_grail.night")
                    .withStyle(ChatFormatting.DARK_RED));
        }
        sp.getCooldowns().addCooldown(stack.getItem(), COOLDOWN_TICKS);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                               List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tensura_minecolonies.twin_grail.desc.day")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("item.tensura_minecolonies.twin_grail.desc.night")
                .withStyle(ChatFormatting.DARK_RED));
    }
}
