package com.example.examplemod;

import io.github.manasmods.tensura.item.misc.SmithingSchematicItem;
import io.github.manasmods.tensura.storage.TensuraStorages;
import io.github.manasmods.tensura.storage.player.ITensuraPlayer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Masterwork Schematic — the Dwargon Covenant reward that unlocks the whole
 * Masterwork weapon line at the Tensura Smithing Bench.
 *
 * <p>This subclasses Tensura's own {@link SmithingSchematicItem} so we inherit its
 * item properties (UNCOMMON rarity, fire-resistant, stacks to 16) and stay
 * compatible with its storage — the unlock is keyed by our registry id
 * ({@code tensura_minecolonies:masterwork_schematic}) through
 * {@link ITensuraPlayer#unlockSchematic(ItemStack)}, and
 * {@code SmithingBenchRecipe} gates on exactly that id.
 *
 * <p>We override {@code use()} rather than inheriting it for one reason: Tensura's
 * version consumes the schematic ONLY on the path where the player doesn't already
 * know it. If the player already knows it, the whole block is skipped and the item
 * just sits in the inventory doing nothing, with no feedback at all. That case is
 * reachable — the Dwargon conquest loot pool is built from the faction's deal
 * rewards, so it can hand out extra schematics. Here, a duplicate says so plainly
 * and is deliberately NOT eaten, so the player never loses an item for nothing.
 */
public class MasterworkSchematicItem extends SmithingSchematicItem {

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // The unlock is server-side state; the client just plays along so the
        // swing/animation doesn't desync.
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        ITensuraPlayer data = TensuraStorages.getPlayerDataFrom(serverPlayer);

        if (data.hasSchematic(stack)) {
            serverPlayer.sendSystemMessage(Component
                    .translatable("tensura_minecolonies.masterwork_schematic.already_known")
                    .withStyle(ChatFormatting.GRAY));
            // Deliberately no shrink — don't destroy a duplicate for no benefit.
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        data.unlockSchematic(stack);
        data.markDirty();

        serverPlayer.sendSystemMessage(Component
                .translatable("tensura_minecolonies.masterwork_schematic.unlocked")
                .withStyle(ChatFormatting.GOLD));
        serverPlayer.level().playSound(null, serverPlayer.blockPosition(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);

        // Consumed: the schematic is spent to learn the recipes.
        stack.shrink(1);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
