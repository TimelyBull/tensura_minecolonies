package com.example.examplemod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.event.EventResult;
import io.github.manasmods.tensura.event.TensuraEntityEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mod(ExampleMod.MODID)
public class ExampleMod {

    public static final String MODID = "tensura_minecolonies";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation GOBLIN_ID =
            ResourceLocation.fromNamespaceAndPath("tensura", "goblin");

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items  ITEMS  = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<Block> EXAMPLE_BLOCK =
            BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);
    public static final DeferredItem<Item> EXAMPLE_ITEM =
            ITEMS.registerSimpleItem("example_item", new Item.Properties().food(
                    new FoodProperties.Builder().alwaysEdible().nutrition(1).saturationModifier(2f).build()));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB =
            CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.examplemod"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(EXAMPLE_ITEM.get()))
                    .build());

    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::addCreative);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Tensura uses Architectury's event system — register via .register(), NOT @SubscribeEvent.
        TensuraEntityEvents.NAMING_EVENT.register(this::onGoblinNamed);
    }

    // ------------------------------------------------------------------
    // Stage A — naming creates CitizenData + identity record, no body
    // ------------------------------------------------------------------

    private EventResult onGoblinNamed(LivingEntity entity,
                                      net.minecraft.world.entity.player.Player player,
                                      io.github.manasmods.manascore.network.api.util.Changeable<Double> magicule,
                                      io.github.manasmods.manascore.network.api.util.Changeable<Double> aura,
                                      io.github.manasmods.manascore.network.api.util.Changeable<io.github.manasmods.tensura.network.c2s.RequestNamingMenuPacket.NamingType> namingType,
                                      io.github.manasmods.manascore.network.api.util.Changeable<String> name) {

        if (!GOBLIN_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))) {
            return EventResult.pass();
        }

        LOGGER.info("[TM] goblin named: {}", name.get());

        // Colony work is server-side only. NAMING_EVENT fires on both sides.
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return EventResult.pass();
        }

        IColonyManager colonyManager = IColonyManager.getInstance();
        IColony colony = colonyManager.getIColonyByOwner(serverLevel, player);
        if (colony == null) {
            List<IColony> all = colonyManager.getColonies(serverLevel);
            colony = all.isEmpty() ? null : all.get(0);
        }

        if (colony == null) {
            LOGGER.info("[TM] no colony in world — goblin '{}' stays a plain subordinate", name.get());
            return EventResult.pass();
        }

        // --- Stage A: create CitizenData (count +1), name it, NO body yet ---

        ICitizenData citizenData = colony.getCitizenManager().createAndRegisterCivilianData();
        citizenData.setName(name.get());

        LOGGER.info("[TM] CitizenData created: id={} colony='{}' count={}",
                citizenData.getId(), colony.getName(),
                colony.getCitizenManager().getCurrentCitizenCount());

        // The per-citizen tick (updateEntityIfNecessary) will try to auto-spawn
        // an EntityCitizen every tick if none exists. Suppress it via the
        // travelling manager — isTravelling() is the only gate in that method.
        // We call finishTravellingFor() in the send handler when we want a body.
        colony.getTravellingManager().startTravellingTo(
                citizenData, entity.blockPosition(), Integer.MAX_VALUE);

        LOGGER.info("[TM] citizen {} marked travelling — no body will auto-spawn", citizenData.getId());

        // --- Create persistent identity record ---
        // No snapshot at naming time — the goblin is alive at the player's side.
        // The full entity NBT is captured at send time (before discard).

        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(serverLevel);
        GoblinIdentitySavedData.GoblinIdentity identity = new GoblinIdentitySavedData.GoblinIdentity(
                UUID.randomUUID(),          // stable identity UUID
                citizenData.getId(),
                colony.getID(),
                entity.getUUID(),           // current goblin entity UUID
                GoblinIdentitySavedData.Mode.SUBORDINATE,
                null                        // entitySnapshot — populated at first send
        );
        saved.addIdentity(identity);

        LOGGER.info("[TM] identity {} stored: citizen={} goblin={} mode=SUBORDINATE",
                identity.identityId, identity.citizenId, identity.goblinEntityUUID);

        return EventResult.pass();
    }

    // ------------------------------------------------------------------
    // Stage B — send trigger: sneak-right-click named goblin with empty hand
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        // Client-side fires too — only act on the server.
        if (event.getLevel().isClientSide()) return;
        // Only main-hand interactions (event fires once per hand).
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        // Must be sneaking with an empty hand.
        if (!event.getEntity().isCrouching()) return;
        if (!event.getEntity().getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) return;
        // Target must be a Tensura goblin.
        if (!(event.getTarget() instanceof LivingEntity target)) return;
        if (!GOBLIN_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()))) return;

        ServerLevel serverLevel = (ServerLevel) event.getLevel();
        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(serverLevel);
        GoblinIdentitySavedData.GoblinIdentity identity = saved.getByGoblinUUID(target.getUUID());

        if (identity == null) {
            LOGGER.info("[TM] goblin {} has no tracked identity — not a named subordinate", target.getUUID());
            return;
        }
        if (identity.mode == GoblinIdentitySavedData.Mode.IN_COLONY) {
            LOGGER.info("[TM] goblin {} is already IN_COLONY — nothing to send", target.getUUID());
            return;
        }

        // Consume the interaction so the goblin's normal right-click logic doesn't also fire.
        event.setCanceled(true);

        sendGoblinToColony(target, event.getEntity(), identity, serverLevel, saved);
    }

    // ------------------------------------------------------------------
    // Send logic
    // ------------------------------------------------------------------

    private static void sendGoblinToColony(LivingEntity goblin,
                                           Player triggeringPlayer,
                                           GoblinIdentitySavedData.GoblinIdentity identity,
                                           ServerLevel serverLevel,
                                           GoblinIdentitySavedData saved) {
        LOGGER.info("[TM] send: starting for goblin '{}' (citizen {})",
                goblin.getName().getString(), identity.citizenId);

        IColony colony = IColonyManager.getInstance()
                .getColonyByWorld(identity.colonyId, serverLevel);
        if (colony == null) {
            LOGGER.warn("[TM] send: colony {} not found — aborting", identity.colonyId);
            return;
        }

        ICitizenData citizenData = colony.getCitizenManager().getCivilian(identity.citizenId);
        if (citizenData == null) {
            LOGGER.warn("[TM] send: CitizenData {} not found in colony — aborting", identity.citizenId);
            return;
        }

        // 1. Snapshot the FULL entity NBT BEFORE discarding.
        //    goblin.save(tag) writes "id" (entity type) + position + attributes +
        //    HandItems/ArmorItems + appearance + EvoState + ManasCoreStorage
        //    (all Tensura storages: ExistenceStorage, AbilityStorage, SpiritStorage, etc.)
        //    via the ManasCore MixinEntity injection. Everything needed for a
        //    perfect summon is in this one tag.
        CompoundTag snapshot = new CompoundTag();
        if (!goblin.save(snapshot)) {
            LOGGER.warn("[TM] send: goblin.save() returned false (entity not saveable) — aborting");
            return;
        }
        saved.updateEntitySnapshot(identity, snapshot);
        LOGGER.info("[TM] send: full entity snapshot captured for citizen {} ({} top-level NBT keys)",
                identity.citizenId, snapshot.getAllKeys().size());

        // 2. Find the town hall spawn position.
        if (!colony.getServerBuildingManager().hasTownHall()) {
            LOGGER.warn("[TM] send: colony '{}' has no town hall — aborting", colony.getName());
            return;
        }
        BlockPos townHallPos = colony.getServerBuildingManager().getTownHall().getPosition();

        // 3. Allow the respawn loop to run (finishTravelling makes isTravelling() return false).
        //    Not strictly required — spawnOrCreateCivilian(force=true) bypasses the travelling
        //    check — but it leaves the citizen in a clean non-travelling state.
        colony.getTravellingManager().finishTravellingFor(citizenData);

        // 4. Materialize the EntityCitizen for the existing CitizenData.
        //    spawnOrCreateCivilian(data, level, hints, force=true):
        //      - data non-null → reuses existing CitizenData, count does NOT increase
        //      - force=true    → bypasses the colony's MOVE_IN setting
        //    MineColonies finds a safe spawn point near townHallPos internally.
        ICitizenData spawned = colony.getCitizenManager()
                .spawnOrCreateCivilian(citizenData, serverLevel, List.of(townHallPos), true);

        if (spawned == null || spawned.getEntity().isEmpty()) {
            // spawnOrCreateCivilian returns the data even if the spawn failed (chunk not loaded).
            // Detect failure by checking whether an entity was actually linked.
            LOGGER.warn("[TM] send: EntityCitizen did not spawn (chunk not loaded?) — re-suppressing respawn");
            colony.getTravellingManager().startTravellingTo(citizenData, townHallPos, Integer.MAX_VALUE);
            return;
        }

        LOGGER.info("[TM] send: EntityCitizen spawned near {} for citizen {}",
                townHallPos, identity.citizenId);

        // 5. Transfer items from goblin → citizen inventory (Option B: citizen
        //    is source of truth during colony service). Preserves full ItemStack
        //    components/NBT — vanilla copy() carries everything.
        List<ItemStack> sendOverflow = transferGoblinItemsToCitizen(goblin, citizenData.getInventory());

        // 6. Strip HandItems/ArmorItems from the snapshot — the citizen now
        //    owns the items, the snapshot must not duplicate them. Other entity
        //    state (EvoState, attributes, ManasCoreStorage) stays in the snapshot.
        identity.entitySnapshot.remove("HandItems");
        identity.entitySnapshot.remove("ArmorItems");
        saved.updateEntitySnapshot(identity, identity.entitySnapshot);
        LOGGER.info("[TM] send: items transferred to citizen, snapshot stripped of HandItems/ArmorItems");

        // 7. Drop send-overflow at triggering player's position. Rare path —
        //    only fires if the citizen inventory was already nearly full.
        if (!sendOverflow.isEmpty()) {
            dropOverflowAtPlayer(serverLevel, triggeringPlayer, sendOverflow);
            triggeringPlayer.sendSystemMessage(Component.literal(
                    citizenData.getName() + " cannot carry as much while working for you; the excess items have been returned to you."));
            LOGGER.info("[TM] send: {} overflow stacks dropped at player {}",
                    sendOverflow.size(), triggeringPlayer.getName().getString());
        }

        // 8. Discard the goblin entity — NOT die(), NOT remove(KILLED).
        //    discard() removes the entity from the world without triggering
        //    LivingDeathEvent or any death logic. This is a swap, not a death.
        goblin.discard();
        LOGGER.info("[TM] send: goblin entity {} discarded (swap, not death)", goblin.getUUID());

        // 6. Update the identity record.
        identity.goblinEntityUUID = null; // goblin entity no longer exists
        saved.updateMode(identity, GoblinIdentitySavedData.Mode.IN_COLONY);

        LOGGER.info("[TM] send: complete — '{}' is now IN_COLONY as citizen {} in '{}'",
                citizenData.getName(), identity.citizenId, colony.getName());
    }

    // ------------------------------------------------------------------
    // Stage C1 — summon: /summongoblin <name>
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("summongoblin")
                        .requires(src -> src.hasPermission(0))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(this::handleSummonCommand))
        );
    }

    private int handleSummonCommand(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("/summongoblin must be run by a player"));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(level);
        IColonyManager cm = IColonyManager.getInstance();

        // Find an IN_COLONY identity whose citizen name matches.
        GoblinIdentitySavedData.GoblinIdentity matchIdentity = null;
        IColony matchColony = null;
        ICitizenData matchData = null;
        for (GoblinIdentitySavedData.GoblinIdentity id : saved.all()) {
            if (id.mode != GoblinIdentitySavedData.Mode.IN_COLONY) continue;
            IColony c = cm.getColonyByWorld(id.colonyId, level);
            if (c == null) continue;
            ICitizenData cd = c.getCitizenManager().getCivilian(id.citizenId);
            if (cd == null) continue;
            if (!name.equals(cd.getName())) continue;
            matchIdentity = id;
            matchColony   = c;
            matchData     = cd;
            break;
        }

        if (matchIdentity == null) {
            src.sendFailure(Component.literal("no IN_COLONY identity named '" + name + "' found"));
            return 0;
        }

        summonGoblin(player, level, saved, matchIdentity, matchColony, matchData);

        final String displayName = name;
        src.sendSuccess(() -> Component.literal("summoned '" + displayName + "'"), false);
        return 1;
    }

    private static void summonGoblin(ServerPlayer player,
                                     ServerLevel level,
                                     GoblinIdentitySavedData saved,
                                     GoblinIdentitySavedData.GoblinIdentity identity,
                                     IColony colony,
                                     ICitizenData citizenData) {
        LOGGER.info("[TM] summon: starting for citizen {} ('{}')",
                identity.citizenId, citizenData.getName());

        // 1. Discard the EntityCitizen body (if it currently exists).
        //    discard() removes the entity from the world WITHOUT triggering die().
        //    It posts CitizenRemovedModEvent(DISCARDED) which does NOT delete
        //    CitizenData — count stays the same.
        citizenData.getEntity().ifPresentOrElse(
                citizenEntity -> {
                    citizenEntity.discard();
                    LOGGER.info("[TM] summon: EntityCitizen discarded for citizen {} (count unchanged)",
                            identity.citizenId);
                },
                () -> LOGGER.info("[TM] summon: citizen {} had no live body (chunk unloaded?) — nothing to discard",
                        identity.citizenId)
        );

        // 2. Re-suppress the respawn loop so updateEntityIfNecessary() doesn't
        //    immediately try to re-spawn the EntityCitizen during the summon window.
        colony.getTravellingManager().startTravellingTo(
                citizenData, player.blockPosition(), Integer.MAX_VALUE);
        LOGGER.info("[TM] summon: citizen {} re-marked travelling — respawn loop suppressed",
                identity.citizenId);

        // 3. Reconstruct the entity from the saved NBT.
        //    EntityType.create(tag, level) reads "id" from the tag and constructs
        //    the right entity type (handles future cross-species evolutions too).
        //    The ManasCore MixinEntity load hook will restore every Tensura
        //    storage from the embedded "ManasCoreStorage" sub-tag, and vanilla
        //    Minecraft restores attributes, inventory, EvoState, appearance, etc.
        if (identity.entitySnapshot == null) {
            LOGGER.warn("[TM] summon: no entity snapshot for citizen {} (was it ever sent?) — aborting",
                    identity.citizenId);
            return;
        }
        Optional<Entity> created = EntityType.create(identity.entitySnapshot, level);
        if (created.isEmpty() || !(created.get() instanceof LivingEntity goblin)) {
            LOGGER.warn("[TM] summon: EntityType.create() returned empty or non-LivingEntity — aborting");
            return;
        }

        // 4. CRITICAL — regenerate UUID. The tag carries the OLD goblin's UUID;
        //    if we kept it, the reverse map would still point at a stale identity
        //    record. setUUID before addFreshEntity ensures the world tracks the
        //    new UUID from the start.
        goblin.setUUID(UUID.randomUUID());

        // 5. CRITICAL — override position. The tag carries the OLD position
        //    (where the goblin was when sent). We want it at the player's feet.
        BlockPos spawnPos = player.blockPosition();
        goblin.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                player.getYRot(), 0f);

        // 6. Apply citizen's CURRENT inventory to the goblin BEFORE addFreshEntity
        //    so first client sync carries the items. Citizen is the source of
        //    truth for items — whatever it has now (after any gear it acquired
        //    or lost during colony service) is what the goblin gets back.
        //    Equipment slots (2 hand + 4 armor) map 1:1; the rest of the
        //    27-slot main inventory is overflow.
        InventoryCitizen citizenInv = citizenData.getInventory();
        List<ItemStack> overflow = transferCitizenItemsToGoblin(citizenInv, goblin);

        // 7. Add to the world. Custom name, attributes, evolution state,
        //    and all ManasCore storages are already restored from NBT;
        //    equipment was just applied in step 6.
        level.addFreshEntity(goblin);
        LOGGER.info("[TM] summon: goblin reconstructed from NBT at {} with NEW UUID {}",
                spawnPos, goblin.getUUID());

        // 8. Clear citizen inventory now that everything has been transferred
        //    (equipment → goblin, non-equipment → overflow). Otherwise items
        //    would persist phantom-style for the next send cycle.
        clearCitizenInventory(citizenInv);

        // 9. Drop overflow at the summoning player's position and notify.
        if (!overflow.isEmpty()) {
            dropOverflowAtPlayer(level, player, overflow);
            player.sendSystemMessage(Component.literal(
                    citizenData.getName() + " cannot carry as much while working for you; the excess items have been returned to you."));
            LOGGER.info("[TM] summon: {} overflow stacks dropped at player {}",
                    overflow.size(), player.getName().getString());
        }

        // 10. Update the reverse map with the NEW goblin's UUID — without this,
        //     the send trigger (and future death hook) won't recognise this entity
        //     as belonging to the identity.
        saved.updateGoblinUUID(identity, goblin.getUUID());

        // 11. Update mode to SUBORDINATE.
        saved.updateMode(identity, GoblinIdentitySavedData.Mode.SUBORDINATE);

        LOGGER.info("[TM] summon: complete — '{}' is now SUBORDINATE (goblin uuid={})",
                citizenData.getName(), goblin.getUUID());
    }

    // ------------------------------------------------------------------
    // Item-transfer helpers (vanilla EquipmentSlot APIs only)
    // ------------------------------------------------------------------

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    /**
     * Send-direction: copy goblin's equipment into the citizen's inventory.
     * Returns any stacks that didn't fit (rare — only if citizen inventory
     * was already nearly full).
     *
     * Armor → forceArmorStackToSlot (dedicated armor slots).
     * Hands → first free main-inventory slot + setHeldItem(hand, slotIndex).
     * ItemStack.copy() preserves full DataComponentPatch (enchants, custom
     * names, durability, Tensura item data — all of it).
     */
    private static List<ItemStack> transferGoblinItemsToCitizen(LivingEntity goblin,
                                                                InventoryCitizen inv) {
        List<ItemStack> overflow = new ArrayList<>();

        // Armor: dedicated slots, no overflow possible
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = goblin.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                inv.forceArmorStackToSlot(slot, stack.copy());
            }
        }

        // Main hand: find first empty main-inventory slot, set, point pointer
        ItemStack mainHand = goblin.getMainHandItem();
        int mainSlot = -1;
        if (!mainHand.isEmpty()) {
            mainSlot = findFreeSlot(inv, -1);
            if (mainSlot >= 0) {
                inv.setStackInSlot(mainSlot, mainHand.copy());
                inv.setHeldItem(InteractionHand.MAIN_HAND, mainSlot);
            } else {
                overflow.add(mainHand.copy());
            }
        }

        // Off hand: find next free, skipping the slot we just used
        ItemStack offHand = goblin.getOffhandItem();
        if (!offHand.isEmpty()) {
            int offSlot = findFreeSlot(inv, mainSlot);
            if (offSlot >= 0) {
                inv.setStackInSlot(offSlot, offHand.copy());
                inv.setHeldItem(InteractionHand.OFF_HAND, offSlot);
            } else {
                overflow.add(offHand.copy());
            }
        }

        return overflow;
    }

    /** First empty main-inventory slot, skipping {@code excludeSlot} (or -1 to skip nothing). */
    private static int findFreeSlot(InventoryCitizen inv, int excludeSlot) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (i == excludeSlot) continue;
            if (inv.getStackInSlot(i).isEmpty()) return i;
        }
        return -1;
    }

    /**
     * Summon-direction: apply citizen's current inventory to the freshly
     * reconstructed goblin. Equipment slots map 1:1; everything else in main
     * inventory becomes overflow (goblin only has 6 equipment slots).
     */
    private static List<ItemStack> transferCitizenItemsToGoblin(InventoryCitizen inv,
                                                                LivingEntity goblin) {
        int mainHeldSlot = inv.getHeldItemSlot(InteractionHand.MAIN_HAND);
        int offHeldSlot  = inv.getHeldItemSlot(InteractionHand.OFF_HAND);

        // Armor: read from dedicated slots, apply to goblin
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = inv.getArmorInSlot(slot);
            if (!stack.isEmpty()) {
                goblin.setItemSlot(slot, stack.copy());
            }
        }

        // Held items: read from main inventory via held-pointer, apply to goblin
        ItemStack mainHand = inv.getHeldItem(InteractionHand.MAIN_HAND);
        if (!mainHand.isEmpty()) {
            goblin.setItemSlot(EquipmentSlot.MAINHAND, mainHand.copy());
        }
        ItemStack offHand = inv.getHeldItem(InteractionHand.OFF_HAND);
        if (!offHand.isEmpty()) {
            goblin.setItemSlot(EquipmentSlot.OFFHAND, offHand.copy());
        }

        // Overflow: any non-empty main-inventory slot that isn't one of the
        // two held pointers. The goblin can't carry these — they drop at the
        // player's feet.
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (i == mainHeldSlot || i == offHeldSlot) continue;
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                overflow.add(stack.copy());
            }
        }
        return overflow;
    }

    /**
     * Clear the citizen's entire inventory after summon. Stops items from
     * persisting between swap cycles. Held-pointers are left as-is — they
     * now point at empty slots, which is harmless until the next send sets
     * them again.
     */
    private static void clearCitizenInventory(InventoryCitizen inv) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            inv.forceArmorStackToSlot(slot, ItemStack.EMPTY);
        }
        for (int i = 0; i < inv.getSlots(); i++) {
            inv.setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    /**
     * Spawn ItemEntities at the triggering player's exact position (not the
     * goblin's or citizen's old position).
     */
    private static void dropOverflowAtPlayer(ServerLevel level, Player player,
                                             List<ItemStack> overflow) {
        Vec3 pos = player.position();
        for (ItemStack stack : overflow) {
            ItemEntity ie = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
            level.addFreshEntity(ie);
        }
    }

    // ------------------------------------------------------------------
    // Boilerplate
    // ------------------------------------------------------------------

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[TM] common setup");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[TM] server starting");
    }
}
