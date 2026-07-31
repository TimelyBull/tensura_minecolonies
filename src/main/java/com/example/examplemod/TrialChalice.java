package com.example.examplemod;

import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

/**
 * A task chalice for Luminous's Covenant deal, "The Trial of Light & Dark".
 *
 * <p>One item type carries both chalices — a {@link Kind#HOLY} chalice that fills
 * with holy water as the "Show of Faith" half is completed, and a {@link Kind#BLOOD}
 * chalice that fills with blood as "The Blood Sacrifice" half is completed. State
 * lives in the vanilla {@link DataComponents#CUSTOM_DATA} component (the mod
 * registers no components of its own), holding:
 * <ul>
 *   <li>{@code kind} — HOLY or BLOOD,</li>
 *   <li>{@code fill} — 0..3 (empty → thirds → full),</li>
 *   <li>{@code owner} — the UUID the chalice is soulbound to (only that player can
 *       fill or turn it in).</li>
 * </ul>
 *
 * <p>The player receives one empty chalice of each kind on accepting the deal
 * ({@code DiplomacyManager.acceptDeal}); the world-side trackers raise {@code fill}
 * as milestones are met, and the deal's custom turn-in consumes a FULL chalice of
 * each kind. The sprite swaps per (kind, fill) through the client model properties
 * registered in {@code ExampleModClient}.
 */
public class TrialChalice extends Item {

    public static final int MAX_FILL = 3;

    private static final String TAG_KIND  = "trial_chalice_kind";
    private static final String TAG_FILL  = "trial_chalice_fill";
    private static final String TAG_OWNER = "trial_chalice_owner";

    public enum Kind {
        HOLY, BLOOD;

        public static Kind byName(String s) {
            return "BLOOD".equals(s) ? BLOOD : HOLY;
        }
    }

    public TrialChalice(Properties properties) {
        super(properties.stacksTo(1).fireResistant());
    }

    // ------------------------------------------------------------------
    // State (stored in CUSTOM_DATA)
    // ------------------------------------------------------------------

    /** Build a fresh empty chalice of {@code kind}, soulbound to {@code owner}. */
    public static ItemStack create(Kind kind, UUID owner) {
        ItemStack stack = new ItemStack(ExampleMod.TRIAL_CHALICE.get());
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_KIND, kind.name());
        tag.putInt(TAG_FILL, 0);
        if (owner != null) tag.putUUID(TAG_OWNER, owner);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static CompoundTag readTag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void writeTag(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static boolean isTrialChalice(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof TrialChalice;
    }

    public static Kind kindOf(ItemStack stack) {
        return Kind.byName(readTag(stack).getString(TAG_KIND));
    }

    public static int fillOf(ItemStack stack) {
        return Math.max(0, Math.min(MAX_FILL, readTag(stack).getInt(TAG_FILL)));
    }

    public static boolean isFull(ItemStack stack) {
        return fillOf(stack) >= MAX_FILL;
    }

    public static UUID ownerOf(ItemStack stack) {
        CompoundTag tag = readTag(stack);
        return tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
    }

    public static boolean isOwnedBy(ItemStack stack, UUID player) {
        UUID owner = ownerOf(stack);
        return owner != null && owner.equals(player);
    }

    /** Set the fill level (clamped 0..MAX_FILL). Returns the new level. */
    public static int setFill(ItemStack stack, int fill) {
        int clamped = Math.max(0, Math.min(MAX_FILL, fill));
        CompoundTag tag = readTag(stack);
        tag.putInt(TAG_FILL, clamped);
        writeTag(stack, tag);
        return clamped;
    }

    // ------------------------------------------------------------------
    // Tooltip
    // ------------------------------------------------------------------

    @Override
    public Component getName(ItemStack stack) {
        Kind kind = kindOf(stack);
        boolean full = isFull(stack);
        String key = "item.tensura_minecolonies.trial_chalice."
                + (kind == Kind.HOLY ? "holy" : "blood")
                + (full ? ".full" : "");
        return Component.translatable(key);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                               List<Component> tooltip, TooltipFlag flag) {
        Kind kind = kindOf(stack);
        int fill = fillOf(stack);
        Component fluid = kind == Kind.HOLY
                ? Component.translatable("item.tensura_minecolonies.trial_chalice.fluid.holy")
                        .withStyle(ChatFormatting.YELLOW)
                : Component.translatable("item.tensura_minecolonies.trial_chalice.fluid.blood")
                        .withStyle(ChatFormatting.DARK_RED);
        tooltip.add(Component.translatable(
                "item.tensura_minecolonies.trial_chalice.progress", fluid, fill, MAX_FILL)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(
                kind == Kind.HOLY
                        ? "item.tensura_minecolonies.trial_chalice.hint.holy"
                        : "item.tensura_minecolonies.trial_chalice.hint.blood")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("item.tensura_minecolonies.trial_chalice.bound")
                .withStyle(ChatFormatting.DARK_PURPLE));
    }
}
