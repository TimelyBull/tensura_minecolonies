package com.example.examplemod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.event.EventResult;
import io.github.manasmods.manascore.storage.api.StorageHolder;
import io.github.manasmods.tensura.event.TensuraEntityEvents;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
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
import net.minecraft.world.entity.LivingEntity;
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

import java.util.List;
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

        CompoundTag existenceSnapshot = snapshotExistence(entity);

        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(serverLevel);
        GoblinIdentitySavedData.GoblinIdentity identity = new GoblinIdentitySavedData.GoblinIdentity(
                UUID.randomUUID(),          // stable identity UUID
                citizenData.getId(),
                colony.getID(),
                entity.getUUID(),           // current goblin entity UUID
                GoblinIdentitySavedData.Mode.SUBORDINATE,
                existenceSnapshot
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

        sendGoblinToColony(target, identity, serverLevel, saved);
    }

    // ------------------------------------------------------------------
    // Send logic
    // ------------------------------------------------------------------

    private static void sendGoblinToColony(LivingEntity goblin,
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

        // 1. Snapshot IExistence BEFORE discarding — the data lives on the entity.
        CompoundTag snapshot = snapshotExistence(goblin);
        saved.updateExistenceSnapshot(identity, snapshot);
        LOGGER.info("[TM] send: IExistence snapshotted for citizen {}", identity.citizenId);

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

        // 5. Discard the goblin entity — NOT die(), NOT remove(KILLED).
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

        // 3. Spawn a fresh Tensura goblin at the player's position.
        EntityType<?> goblinType = BuiltInRegistries.ENTITY_TYPE.get(GOBLIN_ID);
        if (goblinType == null) {
            LOGGER.warn("[TM] summon: tensura:goblin entity type not registered — aborting");
            return;
        }
        Entity raw = goblinType.create(level);
        if (!(raw instanceof LivingEntity goblin)) {
            LOGGER.warn("[TM] summon: goblin create() returned null or non-LivingEntity — aborting");
            return;
        }
        BlockPos spawnPos = player.blockPosition();
        goblin.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                player.getYRot(), 0f);

        // 4. Restore IExistence state from snapshot BEFORE addFreshEntity so
        //    the client receives the right data on first sync.
        if (goblin instanceof StorageHolder holder && identity.existenceSnapshot != null) {
            ExistenceStorage storage = holder.manasCore$getStorage(ExistenceStorage.getKey());
            if (storage != null) {
                storage.load(identity.existenceSnapshot);
                storage.markDirty();
                LOGGER.info("[TM] summon: IExistence restored (name='{}', owner={}, ep={})",
                        storage.getName(), storage.getPermanentOwner(), storage.getEP());
            } else {
                LOGGER.warn("[TM] summon: fresh goblin has no ExistenceStorage — IExistence not restored");
            }
        }

        // 5. Mirror the IExistence name to vanilla custom-name so the nameplate
        //    shows. Tensura's naming code does both of these — load() restored
        //    IExistence.name but NOT the entity's CustomName field.
        goblin.setCustomName(Component.literal(citizenData.getName()));

        // 6. Add to the world. UUID is already assigned by the Entity ctor.
        level.addFreshEntity(goblin);
        LOGGER.info("[TM] summon: goblin spawned at {} with UUID {}", spawnPos, goblin.getUUID());

        // 7. Update the reverse map with the NEW goblin's UUID — without this,
        //    the send trigger (and future death hook) won't recognise this entity
        //    as belonging to the identity.
        saved.updateGoblinUUID(identity, goblin.getUUID());

        // 8. Update mode to SUBORDINATE.
        saved.updateMode(identity, GoblinIdentitySavedData.Mode.SUBORDINATE);

        LOGGER.info("[TM] summon: complete — '{}' is now SUBORDINATE (goblin uuid={})",
                citizenData.getName(), goblin.getUUID());
    }

    // ------------------------------------------------------------------
    // IExistence snapshot helper
    // ------------------------------------------------------------------

    /**
     * Serialise the Tensura IExistence storage for an entity into a CompoundTag.
     * ManasCore mixin-injects StorageHolder onto every Entity, so the cast is
     * safe at runtime. Returns an empty tag if the storage isn't present.
     */
    private static CompoundTag snapshotExistence(LivingEntity entity) {
        if (entity instanceof StorageHolder holder) {
            ExistenceStorage storage = holder.manasCore$getStorage(ExistenceStorage.getKey());
            if (storage != null) {
                CompoundTag tag = new CompoundTag();
                storage.save(tag);
                return tag;
            }
        }
        LOGGER.warn("[TM] snapshotExistence: no ExistenceStorage on {} — returning empty tag",
                entity.getUUID());
        return new CompoundTag();
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
