package com.example.examplemod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.eventbus.events.colony.ColonyCreatedModEvent;
import com.minecolonies.api.eventbus.events.colony.citizens.CitizenDiedModEvent;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.event.EventResult;
import io.github.manasmods.tensura.event.TensuraEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
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
            // Stage 1b: no colony exists yet. Queue this goblin in the pending
            // pool — it stays a plain subordinate at the player's side, but it
            // will be promoted to a real CitizenData (and the count will
            // increase) on the first ColonyCreatedModEvent.
            GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(serverLevel);
            GoblinIdentitySavedData.PendingGoblin p = new GoblinIdentitySavedData.PendingGoblin(
                    UUID.randomUUID(),
                    name.get(),
                    entity.getUUID()
            );
            saved.addPending(p);
            LOGGER.info("[TM] no colony yet — '{}' queued as pending (id={}, goblin={})",
                    name.get(), p.identityId, p.goblinEntityUUID);
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
    // Stage 1b — pending pool drain on colony creation
    // ------------------------------------------------------------------

    /**
     * Promote every still-alive pending goblin into a real CitizenData when
     * the first colony is created. Goblins whose entity died before the
     * colony existed are silently dropped (stale entries cleaned).
     *
     * Single-colony assumption: all pending → this newly-created colony.
     * Multi-colony future would need a per-pending colony assignment policy
     * (see docs/decisions.md). Once drained, the pending list is empty;
     * subsequent ColonyCreatedModEvent firings find nothing to do.
     */
    private void onColonyCreated(ColonyCreatedModEvent event) {
        IColony colony = event.getColony();
        if (!(colony.getWorld() instanceof ServerLevel serverLevel)) return;

        MinecraftServer server = serverLevel.getServer();
        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(serverLevel);

        // Snapshot to a separate list so we can mutate saved.pending during iteration.
        List<GoblinIdentitySavedData.PendingGoblin> drain =
                new java.util.ArrayList<>(saved.getPending());
        if (drain.isEmpty()) {
            LOGGER.info("[TM] colony '{}' created — no pending goblins to promote",
                    colony.getName());
            return;
        }

        LOGGER.info("[TM] colony '{}' created — draining {} pending goblin(s)",
                colony.getName(), drain.size());

        for (GoblinIdentitySavedData.PendingGoblin p : drain) {
            // Stale check: drop pending entry if the goblin entity is gone.
            LivingEntity goblin = findLivingEntityAcrossLevels(server, p.goblinEntityUUID);
            if (goblin == null) {
                LOGGER.info("[TM] pending '{}': goblin {} no longer alive — discarding stale entry",
                        p.name, p.goblinEntityUUID);
                saved.removePending(p);
                continue;
            }

            // Same promotion path as a normal naming-with-colony.
            ICitizenData citizenData = colony.getCitizenManager().createAndRegisterCivilianData();
            citizenData.setName(p.name);
            colony.getTravellingManager().startTravellingTo(
                    citizenData, goblin.blockPosition(), Integer.MAX_VALUE);

            GoblinIdentitySavedData.GoblinIdentity identity = new GoblinIdentitySavedData.GoblinIdentity(
                    p.identityId,                                   // reuse the stable id from pending
                    citizenData.getId(),
                    colony.getID(),
                    p.goblinEntityUUID,
                    GoblinIdentitySavedData.Mode.SUBORDINATE,
                    null                                            // entitySnapshot — populated at first send
            );
            saved.addIdentity(identity);
            saved.removePending(p);

            LOGGER.info("[TM] pending '{}' promoted: citizen id={} in '{}' (now {} citizens)",
                    p.name, citizenData.getId(), colony.getName(),
                    colony.getCitizenManager().getCurrentCitizenCount());
        }
    }

    /**
     * Find a living LivingEntity with the given UUID anywhere on the server.
     * Iterates all server levels so we don't miss a goblin in a different
     * dimension from the new colony.
     */
    private static LivingEntity findLivingEntityAcrossLevels(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(uuid);
            if (e instanceof LivingEntity le && le.isAlive()) {
                return le;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Stage D — death hooks
    //
    // Swap safety (verified by construction, not by runtime check):
    //   - Goblin send uses goblin.discard() → Entity.remove(DISCARDED),
    //     never calls die(), never fires LivingDeathEvent.
    //   - Summon uses citizenEntity.discard() → EntityCitizen.remove(DISCARDED),
    //     posts CitizenRemovedModEvent (which we do NOT subscribe to), NOT
    //     CitizenDiedModEvent.
    // So a swap cannot reach either of these handlers. No runtime guard needed.
    // ------------------------------------------------------------------

    /**
     * Case A — goblin dies while materialized as a subordinate (NeoForge bus).
     * Look up the dying entity in our reverse map; if it's one of ours, remove
     * its CitizenData (count drops) and delete the identity record.
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        // Server-side only. NeoForge fires LivingDeathEvent on the logical server.
        if (!(event.getEntity().level() instanceof ServerLevel serverLevel)) return;

        UUID entityUUID = event.getEntity().getUUID();
        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(serverLevel);

        // Stage 1b cleanup: if this entity was in the pending pool (named before
        // any colony existed), drop the pending entry. No-op for normal goblins.
        saved.removePendingByGoblinUUID(entityUUID);

        GoblinIdentitySavedData.GoblinIdentity identity = saved.getByGoblinUUID(entityUUID);
        if (identity == null) return; // not one of our named goblin-citizens

        LOGGER.info("[TM] death(A): goblin {} ('{}') died — removing citizen {} from colony {}",
                entityUUID, event.getEntity().getName().getString(),
                identity.citizenId, identity.colonyId);

        IColony colony = IColonyManager.getInstance()
                .getColonyByWorld(identity.colonyId, serverLevel);
        if (colony != null) {
            ICitizenData citizenData = colony.getCitizenManager().getCivilian(identity.citizenId);
            if (citizenData != null) {
                // removeCivilian (inherited from IEntityManager): removes from the
                // citizens map (so getCurrentCitizenCount drops), unassigns from
                // home/work buildings, clears work orders, and sends the client
                // view-remove. Full cleanup in one call.
                colony.getCitizenManager().removeCivilian(citizenData);
                LOGGER.info("[TM] death(A): citizen {} removed from '{}' — colony now {} citizens",
                        identity.citizenId, colony.getName(),
                        colony.getCitizenManager().getCurrentCitizenCount());
            } else {
                LOGGER.info("[TM] death(A): citizen {} already gone from colony — skipping removeCivilian",
                        identity.citizenId);
            }
        } else {
            LOGGER.warn("[TM] death(A): colony {} not found — identity will still be cleaned up",
                    identity.colonyId);
        }

        saved.removeIdentity(identity);
        LOGGER.info("[TM] death(A): identity {} removed from SavedData", identity.identityId);
    }

    /**
     * Case B — citizen dies while materialized in the colony (MineColonies bus).
     * MineColonies has already called removeCivilian inside EntityCitizen.die()
     * before posting this event — the count is correct without our help. We
     * only clean up our own SavedData record.
     */
    private void onCitizenDied(CitizenDiedModEvent event) {
        int citizenId = event.getCitizen().getId();
        IColony colony = event.getColony();
        if (!(colony.getWorld() instanceof ServerLevel serverLevel)) return;

        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(serverLevel);
        GoblinIdentitySavedData.GoblinIdentity identity = saved.getByCitizenId(citizenId);
        if (identity == null) return; // not one of ours — vanilla MineColonies citizen

        LOGGER.info("[TM] death(B): citizen {} ('{}') died in colony '{}' — cleaning identity record (count already decremented by MineColonies)",
                citizenId, event.getCitizen().getName(), colony.getName());

        saved.removeIdentity(identity);
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
            sendOverflowNotice(triggeringPlayer, citizenData.getName());
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
            sendOverflowNotice(player, citizenData.getName());
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

        ItemStack mainHand = goblin.getMainHandItem();
        ItemStack offHand  = goblin.getOffhandItem();

        // Reserve TWO distinct main-inventory slots for the held-item pointers
        // BEFORE placing anything. Always call setHeldItem for both hands so
        // the off-hand pointer can never default-collide with the main-hand
        // pointer — that collision was the bug: when the goblin had only a
        // main-hand item, the off-hand pointer kept its default (slot 0),
        // which then aliased to the main-hand stack. On summon both reads
        // returned the same stack and the goblin received two real copies.
        int mainSlot = findFreeSlot(inv, -1);
        int offSlot  = findFreeSlot(inv, mainSlot);

        if (mainSlot >= 0) {
            if (!mainHand.isEmpty()) inv.setStackInSlot(mainSlot, mainHand.copy());
            inv.setHeldItem(InteractionHand.MAIN_HAND, mainSlot);
        } else if (!mainHand.isEmpty()) {
            overflow.add(mainHand.copy());
        }

        if (offSlot >= 0) {
            if (!offHand.isEmpty()) inv.setStackInSlot(offSlot, offHand.copy());
            inv.setHeldItem(InteractionHand.OFF_HAND, offSlot);
        } else if (!offHand.isEmpty()) {
            overflow.add(offHand.copy());
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

        // Defense in depth: if the off-hand pointer collides with the main-hand
        // pointer (e.g. a legacy save from before the send-side fix, or an edge
        // case where send couldn't reserve a distinct slot), treat off as empty.
        // Without this guard, both reads would return the same stack and the
        // goblin would receive a duplicate.
        boolean offValid = offHeldSlot != mainHeldSlot;

        // Armor: read from dedicated slots, apply to goblin
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = inv.getArmorInSlot(slot);
            if (!stack.isEmpty()) {
                goblin.setItemSlot(slot, stack.copy());
            }
        }

        // Main hand: always read
        ItemStack mainHand = inv.getHeldItem(InteractionHand.MAIN_HAND);
        if (!mainHand.isEmpty()) {
            goblin.setItemSlot(EquipmentSlot.MAINHAND, mainHand.copy());
        }

        // Off hand: only read if pointer is distinct from main-hand pointer
        if (offValid) {
            ItemStack offHand = inv.getHeldItem(InteractionHand.OFF_HAND);
            if (!offHand.isEmpty()) {
                goblin.setItemSlot(EquipmentSlot.OFFHAND, offHand.copy());
            }
        }

        // Overflow: any non-empty main-inventory slot that isn't one of the
        // held pointers. If the pointers collide we only exclude main (so we
        // don't accidentally include or doubly-exclude the same slot).
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (i == mainHeldSlot) continue;
            if (offValid && i == offHeldSlot) continue;
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

    /**
     * Send the green-italic overflow advisory to the player.
     *
     * FUTURE FEATURE — Great Sage gating: this advisory (and others like it
     * — analytical/explanatory text the mod surfaces to the player) should
     * eventually only appear when the player has the Tensura "Great Sage"
     * skill (or equivalent analysis skill). Helpful in-world text is a
     * reward for that skill, not free for everyone. See
     * docs/decisions.md → "Advisory messages gated by Great Sage" for the
     * design intent. Do NOT implement the gating here yet — just route
     * advisories through this helper so the gate can be added in one place.
     */
    private static void sendOverflowNotice(Player player, String goblinName) {
        Component msg = Component.literal(goblinName +
                " cannot carry as much while working for you; the excess items have been returned to you.")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC);
        player.sendSystemMessage(msg);
    }

    // ------------------------------------------------------------------
    // Boilerplate
    // ------------------------------------------------------------------

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[TM] common setup");
        // Subscribe to MineColonies' own event bus (separate from NeoForge's).
        event.enqueueWork(() -> {
            // Case B death cleanup. EntityCitizen.die() already called
            // removeCivilian before posting this event — count is correct,
            // we only clean our SavedData record.
            IMinecoloniesAPI.getInstance().getEventBus()
                    .subscribe(CitizenDiedModEvent.class, this::onCitizenDied);

            // Stage 1b pending pool drain. All pending goblins join the
            // newly-created colony (single-colony assumption — see
            // docs/decisions.md).
            //
            // FUTURE FEATURE: when signing the town hall, show a menu asking
            // what citizen TYPE the colony should use (goblin / human / etc.).
            // That ties into the broader race/citizen-type system in the
            // original design doc. The pending pool drain logic would then
            // also filter by type. Not implemented now — recorded in
            // docs/decisions.md → "Town hall citizen-type menu".
            IMinecoloniesAPI.getInstance().getEventBus()
                    .subscribe(ColonyCreatedModEvent.class, this::onColonyCreated);
        });
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
