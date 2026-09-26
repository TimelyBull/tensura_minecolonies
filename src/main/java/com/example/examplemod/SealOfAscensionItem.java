package com.example.examplemod;

import java.util.List;
import java.util.Locale;

import dev.architectury.event.EventResult;
import io.github.manasmods.tensura.entity.template.subclass.ISubordinate;
import io.github.manasmods.tensura.registry.attribute.TensuraAttributes;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import io.github.manasmods.tensura.util.SubordinateHelper;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * The Seal of Ascension — the Jura-Tempest Federation's Covenant reward
 * ("A Nation of Many Peoples", 2026-09-26, developer design).
 *
 * <p>Canon: Rimuru's naming pours his magicules into a monster and lifts it to
 * a higher stage. Right-click one of YOUR subordinates with the seal and it:
 * <ol>
 *   <li>permanently multiplies the subordinate's EP by {@link #EP_MULTIPLIER}
 *       (its max aura + max magicule base values, current pools scaled to
 *       match — the same storage the Harvest Festival EP gift uses), and</li>
 *   <li>grants {@link #BUFF_MINUTES} minutes of ASCENSION: every EP gain the
 *       subordinate makes during that window is multiplied by
 *       {@link #GROWTH_MULTIPLIER}.</li>
 * </ol>
 * The seal is reusable, on a {@value #COOLDOWN_MINUTES}-minute cooldown.
 *
 * <p><b>Real time, persisted.</b> Both timers use wall-clock time so they
 * survive relogs and restarts (vanilla item cooldowns reset on logout). The
 * cooldown lives on the seal's CUSTOM_DATA; the buff lives in the
 * subordinate's persistent NBT, so it travels with the body through send /
 * summon (the snapshot carries it).
 *
 * <p><b>How the growth buff works.</b> Tensura raises a mob's EP by raising
 * the BASE value of its max-aura / max-magicule attributes, and fires
 * {@code ATTRIBUTE_BASE_CHANGE_EVENT} BEFORE each change with the old and new
 * value (cancellable). {@link #onAttributeBaseChange} cancels an increase on
 * an ascending subordinate and re-applies it at ×1.5. Guards: our own writes
 * are ignored (reentrancy flag), and a body must have existed for
 * {@link #MIN_BODY_AGE_TICKS} ticks — a freshly rebuilt body loading its saved
 * stats is an "increase" too, and must never be inflated.
 *
 * <p>⚠ BALANCE (developer, 2026-09-26): no per-subordinate limit — the same
 * subordinate can be sealed every 45 minutes, and the ×1.25 compounds. Left
 * deliberately; revisit if it proves too strong.
 */
public class SealOfAscensionItem extends Item {

    static final double EP_MULTIPLIER = 1.25;
    static final double GROWTH_MULTIPLIER = 1.5;
    static final long BUFF_MINUTES = 30;
    static final long COOLDOWN_MINUTES = 45;
    private static final long MINUTE_MS = 60_000L;

    /** A body younger than this is still loading its saved stats — never boosted. */
    static final int MIN_BODY_AGE_TICKS = 40;

    private static final String TAG_READY_AT = "tm_seal_ready_at";        // on the seal
    private static final String TAG_ASCEND_UNTIL = "tm_ascension_until";  // on the subordinate

    /** Set while WE change an attribute, so our own write isn't boosted again. */
    private static boolean applying = false;

    public SealOfAscensionItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC).fireResistant());
    }

    // ------------------------------------------------------------------
    // Use: right-click one of your subordinates
    // ------------------------------------------------------------------

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp) || !(player.level() instanceof ServerLevel level)) {
            // Client: claim the click so the entity's own right-click (e.g. a
            // subordinate's command menu) doesn't also fire.
            return target instanceof ISubordinate ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!(target instanceof ISubordinate)
                || !sp.getUUID().equals(SubordinateHelper.getSubordinateOwnerUUID(target))) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.seal_of_ascension.not_yours")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.FAIL;
        }
        long now = System.currentTimeMillis();
        long readyAt = readyAt(stack);
        if (now < readyAt) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.seal_of_ascension.recharging",
                    minutesLeft(readyAt, now)).withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.FAIL;
        }
        ExistenceStorage ex = ExampleMod.readExistence(target);
        if (ex == null) return InteractionResult.FAIL;

        // 1. Permanent EP ×1.25 — base max pools + current pools.
        double before = ex.getEP();
        applying = true;
        try {
            multiplyBase(target, TensuraAttributes.MAX_AURA, EP_MULTIPLIER);
            multiplyBase(target, TensuraAttributes.MAX_MAGICULE, EP_MULTIPLIER);
        } finally {
            applying = false;
        }
        ex.setAura(ex.getAura() * EP_MULTIPLIER);
        ex.setMagicule(ex.getMagicule() * EP_MULTIPLIER);
        ex.markDirty();
        double after = ex.getEP();

        // 2. Ascension: 30 real minutes of ×1.5 EP growth.
        target.getPersistentData().putLong(TAG_ASCEND_UNTIL, now + BUFF_MINUTES * MINUTE_MS);

        // 3. Cooldown — stamped on the seal (persists), mirrored as the vanilla
        //    hotbar overlay.
        setReadyAt(stack, now + COOLDOWN_MINUTES * MINUTE_MS);
        sp.getCooldowns().addCooldown(this, (int) (COOLDOWN_MINUTES * 60 * 20));

        level.sendParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + target.getBbHeight() * 0.6,
                target.getZ(), 40, 0.4, 0.7, 0.4, 0.05);
        level.sendParticles(ParticleTypes.ENCHANT, target.getX(), target.getY() + 0.2,
                target.getZ(), 60, 0.6, 0.2, 0.6, 0.6);
        level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.3f);
        sp.sendSystemMessage(Component.translatable("item.tensura_minecolonies.seal_of_ascension.used",
                target.getDisplayName(),
                String.format(Locale.ROOT, "%,d", Math.round(before)),
                String.format(Locale.ROOT, "%,d", Math.round(after)),
                BUFF_MINUTES).withStyle(ChatFormatting.AQUA));
        ExampleMod.LOGGER.info("[TM] seal of ascension: {} sealed {} — EP {} -> {}",
                sp.getName().getString(), target.getUUID(), before, after);
        return InteractionResult.SUCCESS;
    }

    /** Keep the hotbar cooldown overlay in step with the persisted timer (it is
     *  lost on relog; the real timer isn't). */
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
        tooltip.add(Component.translatable("item.tensura_minecolonies.seal_of_ascension.desc")
                .withStyle(ChatFormatting.GRAY));
        long now = System.currentTimeMillis();
        long readyAt = readyAt(stack);
        tooltip.add(now < readyAt
                ? Component.translatable("item.tensura_minecolonies.seal_of_ascension.tooltip_recharging",
                        minutesLeft(readyAt, now)).withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("item.tensura_minecolonies.seal_of_ascension.tooltip_ready")
                        .withStyle(ChatFormatting.AQUA));
    }

    // ------------------------------------------------------------------
    // The growth buff — TensuraEntityEvents.ATTRIBUTE_BASE_CHANGE_EVENT
    // (registered from ExampleMod's constructor)
    // ------------------------------------------------------------------

    static EventResult onAttributeBaseChange(LivingEntity entity, AttributeInstance instance,
                                             double oldValue, double newValue) {
        if (applying || entity == null || instance == null) return EventResult.pass();
        if (entity.level().isClientSide()) return EventResult.pass();
        if (newValue <= oldValue) return EventResult.pass();                // only growth
        if (entity.tickCount < MIN_BODY_AGE_TICKS) return EventResult.pass(); // body still loading
        Holder<Attribute> attr = instance.getAttribute();
        if (!attr.equals(TensuraAttributes.MAX_AURA) && !attr.equals(TensuraAttributes.MAX_MAGICULE)) {
            return EventResult.pass();
        }
        if (!isAscending(entity)) return EventResult.pass();

        double gain = newValue - oldValue;
        double extra = gain * (GROWTH_MULTIPLIER - 1.0);
        applying = true;
        try {
            instance.setBaseValue(newValue + extra);
        } finally {
            applying = false;
        }
        // Top the current pool up by the extra too, so the gain is felt now.
        ExistenceStorage ex = ExampleMod.readExistence(entity);
        if (ex != null) {
            if (attr.equals(TensuraAttributes.MAX_AURA)) ex.setAura(ex.getAura() + extra);
            else ex.setMagicule(ex.getMagicule() + extra);
            ex.markDirty();
        }
        return EventResult.interruptFalse(); // we applied the boosted value ourselves
    }

    /** True while this entity's Ascension buff is running. */
    static boolean isAscending(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        return data.contains(TAG_ASCEND_UNTIL)
                && System.currentTimeMillis() < data.getLong(TAG_ASCEND_UNTIL);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void multiplyBase(LivingEntity e, Holder<Attribute> attr, double mult) {
        AttributeInstance ai = e.getAttribute(attr);
        if (ai != null) ai.setBaseValue(ai.getBaseValue() * mult);
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
        return Math.max(1, (readyAt - now + MINUTE_MS - 1) / MINUTE_MS);
    }
}
