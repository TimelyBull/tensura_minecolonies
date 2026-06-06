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
import io.github.manasmods.manascore.storage.api.StorageHolder;
import io.github.manasmods.tensura.registry.attribute.TensuraAttributes;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import io.github.manasmods.tensura.storage.ep.IExistence;
import io.github.manasmods.tensura.util.EnergyHelper;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.event.EventResult;
import io.github.manasmods.tensura.entity.magic.MagicCircle;
import io.github.manasmods.tensura.entity.variant.MagicCircleVariant;
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
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
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

    // Stage E — circle visuals + delayed swap + sink/rise animations.
    private static final int   SWAP_DELAY_TICKS     = 40;    // ~2.0s — body change after this many ticks
    private static final int   RISE_DURATION_TICKS  = 20;    // ~1.0s — how long the rise from underground takes
    private static final int   CIRCLE_DURATION_TICKS = 80;   // ~4.0s — covers sink + delay + rise + afterglow
    private static final float CIRCLE_SIZE          = 3.0f;  // ~3× the default MagicCircle visual scale
    private static final double SINK_DEPTH          = 3.0;   // blocks the dissolve body falls during the delay
    private static final double RISE_START_OFFSET   = 2.0;   // blocks below surface the materialize body spawns

    /** Queued circle entity awaiting discard after its lifetime expires. */
    private record PendingCircleDiscard(UUID circleUUID, ResourceKey<Level> dim, long discardAtTick) {}

    /** Queued swap action awaiting execution after the dramatic delay. */
    private record PendingSwap(UUID playerUUID,
                               UUID identityId,
                               GoblinIdentitySavedData.Mode expectedMode,
                               UUID expectedGoblinUUID,
                               double magiculePaid,
                               long executeAtTick) {}

    /** Queued linear Y-axis animation: at progress p between [startTick, endTick],
     *  entity.setY(startY + p × (targetY - startY)). When {@code clearInvulnerableOnEnd}
     *  is true, the entity's invulnerable flag is reset on the final tick — used
     *  for the materialize-body rise (we set invulnerable during the rise so block
     *  suffocation doesn't drain HP through the floor and leak into the stat sync). */
    private record VerticalMovement(UUID entityUUID,
                                    ResourceKey<Level> dim,
                                    double startY,
                                    double targetY,
                                    long startTick,
                                    long endTick,
                                    boolean clearInvulnerableOnEnd) {}

    /** Return value of {@link #chargeOrPrompt}: whether the action should
     *  proceed and how much magicule was charged (for the refund path). */
    private record ChargeOutcome(boolean charged, double amount) {
        static ChargeOutcome charged(double amount) { return new ChargeOutcome(true, amount); }
        static ChargeOutcome notCharged() { return new ChargeOutcome(false, 0.0); }
    }

    private static final java.util.List<PendingCircleDiscard> pendingCircles           = new java.util.ArrayList<>();
    private static final java.util.List<PendingSwap>          pendingSwaps             = new java.util.ArrayList<>();
    private static final java.util.List<VerticalMovement>     pendingVerticalMovements = new java.util.ArrayList<>();

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

        // Stage C2a — networking foundation. Payload registration is mod-bus.
        modEventBus.addListener(Networking::register);

        // Client-only setup (keybind + tick listener). Guard prevents the
        // server JVM from ever loading client-only classes.
        if (FMLEnvironment.dist.isClient()) {
            ClientEvents.init(modEventBus);
        }
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
                    entity.getUUID(),
                    player.getUUID()          // matches IExistence.permanentOwner set by Tensura
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
                null,                       // entitySnapshot — populated at first send
                player.getUUID()            // owner — matches IExistence.permanentOwner
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

        // Route through the shared cost chokepoint (so sneak-send is taxed
        // identically to a menu click). The player must be a ServerPlayer
        // since we already checked isClientSide().
        if (event.getEntity() instanceof ServerPlayer sp) {
            handleMenuAction(sp, identity.identityId);
        }
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
                    null,                                           // entitySnapshot — populated at first send
                    p.ownerPlayerUUID                               // propagate from pending entry
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
            sendAdvisoryNotice(triggeringPlayer, citizenData.getName() +
                    " couldn't reach the colony — the town hall area may not be loaded. Try again from closer.");
            colony.getTravellingManager().startTravellingTo(citizenData, townHallPos, Integer.MAX_VALUE);
            return;
        }

        LOGGER.info("[TM] send: EntityCitizen spawned near {} for citizen {}",
                townHallPos, identity.citizenId);

        // 5. Transfer items from goblin → citizen inventory (Option B: citizen
        //    is source of truth during colony service). Preserves full ItemStack
        //    components/NBT — vanilla copy() carries everything.
        List<ItemStack> sendOverflow = transferGoblinItemsToCitizen(goblin, citizenData.getInventory());

        // 5b. Stat sync: goblin (active body) → citizen (fresh body). Must
        //     happen before goblin.discard() while both bodies are alive.
        //     First we BUMP the citizen's max-energy attributes up to the
        //     goblin's (so absolute copy doesn't dump goblin-tier values into
        //     a citizen with 0-cap max-magicule, which would trigger
        //     MagiculePoisonEffect and kill it). Then absolute copy. Energy
        //     pools round-trip cleanly because the citizen's elevated max
        //     gives the values somewhere to live.
        spawned.getEntity().ifPresent(citizenBody -> {
            // Capture the citizen's surface Y BEFORE we lower it, then queue
            // the rise. The body appears underground first and visually
            // emerges from the materialize-end circle.
            double citizenSurfaceY = citizenBody.getY();
            markMaterializedBody(serverLevel, citizenBody, citizenSurfaceY);

            bumpBodyMaxAttributes(citizenBody, goblin);
            LOGGER.info("[TM] send: citizen max attributes boosted (max-aura {} max-magicule {} max-SH {} max-HP {})",
                    EnergyHelper.getMaxAura(citizenBody),
                    EnergyHelper.getMaxMagicule(citizenBody),
                    citizenBody.getAttributeValue(TensuraAttributes.MAX_SPIRITUAL_HEALTH),
                    citizenBody.getMaxHealth());

            ExistenceStorage srcExist = readExistence(goblin);
            ExistenceStorage dstExist = readExistence(citizenBody);
            if (srcExist != null && dstExist != null) {
                copyStats(srcExist, dstExist);
                LOGGER.info("[TM] send: stats copied goblin → citizen (aura {} magicule {} SH {} soul {})",
                        dstExist.getAura(), dstExist.getMagicule(),
                        dstExist.getSpiritualHealth(), dstExist.getSoulPoints());
            }

            copyHealthAbsolute(goblin, citizenBody);
            LOGGER.info("[TM] send: HP copied — goblin {}/{} → citizen {}/{}",
                    goblin.getHealth(), goblin.getMaxHealth(),
                    citizenBody.getHealth(), citizenBody.getMaxHealth());
        });

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

        // Find an IN_COLONY identity OWNED by this player whose citizen name matches.
        // The command only routes the lookup; the actual action goes through the
        // shared chokepoint so the magicule cost / collapse-prompt apply identically.
        java.util.UUID matchId = null;
        for (GoblinIdentitySavedData.GoblinIdentity id : saved.all()) {
            if (id.mode != GoblinIdentitySavedData.Mode.IN_COLONY) continue;
            if (!player.getUUID().equals(id.ownerPlayerUUID)) continue;
            IColony c = cm.getColonyByWorld(id.colonyId, level);
            if (c == null) continue;
            ICitizenData cd = c.getCitizenManager().getCivilian(id.citizenId);
            if (cd == null) continue;
            if (!name.equals(cd.getName())) continue;
            matchId = id.identityId;
            break;
        }

        if (matchId == null) {
            src.sendFailure(Component.literal(
                    "no IN_COLONY identity named '" + name + "' that you own"));
            return 0;
        }

        handleMenuAction(player, matchId);
        final String displayName = name;
        src.sendSuccess(() -> Component.literal("summoning '" + displayName + "'"), false);
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

        // 1. Capture a reference to the live citizen body — but do NOT discard
        //    it yet. We need to read its IExistence stats and HP later (step 6b),
        //    after the goblin has been reconstructed. The discard is deferred
        //    until step 7b, after the goblin is in the world. May be empty if
        //    the colony chunk is unloaded; in that case we fall back to the
        //    snapshot's stale stats.
        java.util.Optional<? extends com.minecolonies.api.entity.citizen.AbstractCivilianEntity> citizenBodyOpt =
                citizenData.getEntity();
        if (citizenBodyOpt.isEmpty()) {
            LOGGER.info("[TM] summon: citizen {} had no live body (chunk unloaded?) — stat copy will fall back to snapshot",
                    identity.citizenId);
        }

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

        // 6b. Stat sync: citizen (active body) → goblin (fresh body). Overwrites
        //     the snapshot's stale IExistence values that the NBT reconstruction
        //     just restored. Must happen BEFORE addFreshEntity so the first
        //     client sync carries the right values.
        if (citizenBodyOpt.isPresent()) {
            LivingEntity citizenBody = citizenBodyOpt.get();
            // The goblin already has its race-tier max-energy attributes from
            // the NBT reconstruction — no boost needed on the destination here.
            // Absolute-copy citizen's current values back to the goblin.
            ExistenceStorage srcExist = readExistence(citizenBody);
            ExistenceStorage dstExist = readExistence(goblin);
            if (srcExist != null && dstExist != null) {
                LOGGER.info("[TM] summon: reading citizen pre-copy aura={} magicule={} SH={} soul={}",
                        srcExist.getAura(), srcExist.getMagicule(),
                        srcExist.getSpiritualHealth(), srcExist.getSoulPoints());
                copyStats(srcExist, dstExist);
                LOGGER.info("[TM] summon: stats copied citizen → goblin (now aura {} magicule {} SH {} soul {})",
                        dstExist.getAura(), dstExist.getMagicule(),
                        dstExist.getSpiritualHealth(), dstExist.getSoulPoints());
            }

            copyHealthAbsolute(citizenBody, goblin);
            LOGGER.info("[TM] summon: HP copied — citizen {}/{} → goblin {}/{}",
                    citizenBody.getHealth(), citizenBody.getMaxHealth(),
                    goblin.getHealth(), goblin.getMaxHealth());
        } else {
            LOGGER.info("[TM] summon: no live citizen body — keeping snapshot's stats and HP");
        }

        // 6c. Lower the goblin RISE_START_OFFSET blocks below its destination
        //     Y so addFreshEntity puts it underground. The vertical-movement
        //     tick handler will lift it back to the surface over
        //     RISE_DURATION_TICKS, visually rising out of the materialize-end
        //     circle. Capture surface Y first.
        double goblinSurfaceY = goblin.getY();
        markMaterializedBody(level, goblin, goblinSurfaceY);

        // 7. Add to the world. Custom name, attributes, evolution state,
        //    and all ManasCore storages are already restored from NBT;
        //    equipment was applied in step 6, stats in step 6b.
        level.addFreshEntity(goblin);
        LOGGER.info("[TM] summon: goblin reconstructed from NBT at {} with NEW UUID {}",
                spawnPos, goblin.getUUID());

        // 7b. NOW discard the citizen body — after stat copy, after addFreshEntity.
        //     discard() never fires CitizenDiedModEvent (only die() does) so the
        //     CitizenData / count are untouched; the body just leaves the world.
        citizenBodyOpt.ifPresent(citizenBody -> {
            citizenBody.discard();
            LOGGER.info("[TM] summon: EntityCitizen discarded for citizen {} (count unchanged)",
                    identity.citizenId);
        });

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
    // Stage C2b — menu action dispatcher
    //
    // Called from Networking.onActOnIdentity. Server is the authority:
    // reads identity.mode and routes to the SAME helpers used by the
    // sneak-right-click send and the /summongoblin command. No duplication.
    // ------------------------------------------------------------------

    static void handleMenuAction(ServerPlayer player, java.util.UUID identityId) {
        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(player.serverLevel());
        GoblinIdentitySavedData.GoblinIdentity identity = saved.getById(identityId);
        if (identity == null) {
            sendAdvisoryNotice(player, "That goblin no longer exists.");
            return;
        }
        // Ownership check — only the namer can act on the identity.
        if (identity.ownerPlayerUUID == null
                || !player.getUUID().equals(identity.ownerPlayerUUID)) {
            sendAdvisoryNotice(player, "That goblin isn't yours.");
            return;
        }
        // Resolve the live body whose IExistence we'll read for the cost gate.
        LivingEntity target = resolveTargetBody(player, identity);
        if (target == null) {
            String msg = identity.mode == GoblinIdentitySavedData.Mode.SUBORDINATE
                    ? "Your goblin isn't in any loaded chunk right now — go closer and try again."
                    : "That goblin's citizen body isn't loaded right now — visit the colony first.";
            sendAdvisoryNotice(player, msg);
            return;
        }
        // Shared cost chokepoint. Returns notCharged() if a prompt was sent
        // (action awaits confirmation) or on read failure (advisory shown).
        ChargeOutcome outcome = chargeOrPrompt(player, identity, target);
        if (outcome.charged()) {
            queueDelayedSwap(player, identity, target, outcome.amount());
        }
    }

    /**
     * Server-side confirmation handler for the collapse dialog. Per the design:
     *   - setMagicule(0) — EXACTLY zero, never negative. Tensura's natural
     *     handleSleepMode tick detects magicule ≤ 0 and runs the full Sleep
     *     Mode entry pipeline (ENTER_SLEEP_MODE_EVENT fires, attribute applied,
     *     magicule reset to 1.0, entity unridden, flying disabled).
     *   - We do NOT call applySleepModeAttribute, setSleepModeTime, or fire
     *     the event ourselves. The natural pipeline does it.
     *   - We do NOT cancel the event. Sleep Mode is intended here.
     */
    static void handleConfirmCollapse(ServerPlayer player, java.util.UUID identityId) {
        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(player.serverLevel());
        GoblinIdentitySavedData.GoblinIdentity identity = saved.getById(identityId);
        if (identity == null) {
            sendAdvisoryNotice(player, "That goblin no longer exists.");
            return;
        }
        if (identity.ownerPlayerUUID == null
                || !player.getUUID().equals(identity.ownerPlayerUUID)) {
            sendAdvisoryNotice(player, "That goblin isn't yours.");
            return;
        }
        LivingEntity target = resolveTargetBody(player, identity);
        if (target == null) {
            sendAdvisoryNotice(player,
                    "Couldn't reach the target now — try again from closer.");
            return;
        }

        ExistenceStorage playerExist = readExistence(player);
        if (playerExist == null) {
            sendAdvisoryNotice(player, "Couldn't access your magicule storage.");
            return;
        }
        double beforeMag = playerExist.getMagicule();
        playerExist.setMagicule(0.0); // EXACTLY 0 — never negative
        playerExist.markDirty();
        double afterMag = playerExist.getMagicule();
        boolean infMaterials = player.hasInfiniteMaterials();
        boolean invulnerable = player.isInvulnerable();
        LOGGER.info("[TM] cost: '{}' forced collapse — magicule {} → {}; hasInfiniteMaterials={} isInvulnerable={}{}",
                player.getName().getString(), beforeMag, afterMag, infMaterials, invulnerable,
                (infMaterials || invulnerable)
                        ? " — TENSURA WILL SKIP SLEEP MODE (player must be in survival and not invulnerable)"
                        : " — Sleep Mode will trigger naturally next tick");

        // Paid amount for refund-on-abort = the magicule we just zeroed out
        // (the player's full pre-collapse magicule, which is what they "spent").
        queueDelayedSwap(player, identity, target, beforeMag);
    }

    // ------------------------------------------------------------------
    // Stage E — magic-circle visuals + delayed swap execution
    // ------------------------------------------------------------------

    /**
     * Spawn the dramatic-pause swap: circles at both ends now, body change
     * after SWAP_DELAY_TICKS. The magicule cost has already been deducted
     * upstream — the {@code magiculePaid} value is what the player parts with
     * and is refunded on abort.
     */
    private static void queueDelayedSwap(ServerPlayer player,
                                         GoblinIdentitySavedData.GoblinIdentity identity,
                                         LivingEntity target,
                                         double magiculePaid) {
        ServerLevel level = player.serverLevel();
        long now = level.getServer().getTickCount();

        // Dissolve circle — at the live body's current position
        Vec3 dissolvePos = target.position();
        spawnSwapCircle(level, player, dissolvePos);

        // Materialize circle — destination depends on direction
        Vec3 materializePos;
        if (identity.mode == GoblinIdentitySavedData.Mode.SUBORDINATE) {
            // Send → materialize near the town hall
            IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, level);
            if (colony != null && colony.getServerBuildingManager().hasTownHall()) {
                BlockPos th = colony.getServerBuildingManager().getTownHall().getPosition();
                materializePos = new Vec3(th.getX() + 0.5, th.getY(), th.getZ() + 0.5);
            } else {
                materializePos = dissolvePos; // fallback; the action would fail anyway
            }
        } else {
            // Summon → materialize at the player
            materializePos = player.position();
        }
        spawnSwapCircle(level, player, materializePos);

        // Dissolve-body sink: lower the live body SINK_DEPTH blocks over the
        // delay so it visually falls through its circle into the ground. Set
        // invulnerable to block suffocation damage (which would otherwise leak
        // through the stat sync onto the destination body).
        target.setInvulnerable(true);
        double sinkStartY = target.getY();
        pendingVerticalMovements.add(new VerticalMovement(
                target.getUUID(), level.dimension(),
                sinkStartY, sinkStartY - SINK_DEPTH,
                now, now + SWAP_DELAY_TICKS,
                false /* body gets discarded at execute; no need to clear invuln */));

        // Queue the swap to execute after the delay
        pendingSwaps.add(new PendingSwap(
                player.getUUID(),
                identity.identityId,
                identity.mode,
                identity.goblinEntityUUID,
                magiculePaid,
                now + SWAP_DELAY_TICKS));
        LOGGER.info("[TM] swap: queued for execution in {} ticks (paid={} magicule, dissolve sink {}→{})",
                SWAP_DELAY_TICKS, magiculePaid, sinkStartY, sinkStartY - SINK_DEPTH);
    }

    /**
     * Set up a freshly-spawned body to rise from underground: lower it
     * RISE_START_OFFSET blocks below its current Y, mark invulnerable, and
     * queue a VerticalMovement to raise it back to the surface over
     * RISE_DURATION_TICKS. Call this AFTER spawn/addFreshEntity for the
     * materialize-side body in both send and summon.
     */
    private static void markMaterializedBody(ServerLevel level, LivingEntity body, double surfaceY) {
        long now = level.getServer().getTickCount();
        body.setPos(body.getX(), surfaceY - RISE_START_OFFSET, body.getZ());
        body.setInvulnerable(true);
        pendingVerticalMovements.add(new VerticalMovement(
                body.getUUID(), level.dimension(),
                surfaceY - RISE_START_OFFSET, surfaceY,
                now, now + RISE_DURATION_TICKS,
                true /* clear invuln on completion */));
    }

    /** Spawn a Tensura {@link MagicCircle} entity at {@code pos} with the
     *  SPACE variant. Tracks it for {@link #CIRCLE_DURATION_TICKS}-tick
     *  auto-discard via the ServerTickEvent.Post handler. */
    private static void spawnSwapCircle(ServerLevel level, LivingEntity caster, Vec3 pos) {
        MagicCircle circle = new MagicCircle(level, caster);
        circle.setVariant(MagicCircleVariant.SPACE);
        circle.setSpinning(true);
        circle.setSize(CIRCLE_SIZE);          // inherited from TensuraProjectile
        circle.refreshDimensions();           // recompute bounding box for the new size
        circle.setPos(pos.x, pos.y, pos.z);
        level.addFreshEntity(circle);
        long discardAt = level.getServer().getTickCount() + CIRCLE_DURATION_TICKS;
        pendingCircles.add(new PendingCircleDiscard(circle.getUUID(), level.dimension(), discardAt));
    }

    /**
     * Server tick handler. Drains the two pending lists:
     *   - circle entities older than CIRCLE_DURATION_TICKS → discard
     *   - pending swaps past their executeAtTick → re-validate and run
     */
    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();

        // Discard expired circles
        pendingCircles.removeIf(p -> {
            if (now < p.discardAtTick()) return false;
            ServerLevel lvl = server.getLevel(p.dim());
            if (lvl != null) {
                net.minecraft.world.entity.Entity e = lvl.getEntity(p.circleUUID());
                if (e != null && !e.isRemoved()) {
                    e.discard();
                }
            }
            return true;
        });

        // Advance vertical movements (dissolve sink, materialize rise).
        // Order before pending swaps so the dissolve body's final sink position
        // settles in the same tick the swap discards it.
        java.util.Iterator<VerticalMovement> vit = pendingVerticalMovements.iterator();
        while (vit.hasNext()) {
            VerticalMovement m = vit.next();
            if (now < m.startTick()) continue;
            ServerLevel lvl = server.getLevel(m.dim());
            if (lvl == null) { vit.remove(); continue; }
            net.minecraft.world.entity.Entity e = lvl.getEntity(m.entityUUID());
            if (now >= m.endTick()) {
                if (e instanceof LivingEntity le && !le.isRemoved()) {
                    le.setPos(le.getX(), m.targetY(), le.getZ());
                    if (m.clearInvulnerableOnEnd()) {
                        le.setInvulnerable(false);
                    }
                }
                vit.remove();
                continue;
            }
            if (e == null || e.isRemoved()) continue;
            double progress = (double) (now - m.startTick()) / (m.endTick() - m.startTick());
            double y = m.startY() + (m.targetY() - m.startY()) * progress;
            e.setPos(e.getX(), y, e.getZ());
        }

        // Execute due swaps
        java.util.Iterator<PendingSwap> it = pendingSwaps.iterator();
        while (it.hasNext()) {
            PendingSwap p = it.next();
            if (now < p.executeAtTick()) continue;
            it.remove();
            executePendingSwap(server, p);
        }
    }

    /**
     * Re-validate the queued swap and run it, or abort + refund if state
     * changed during the delay. Validation: player online, identity still
     * exists, mode unchanged, goblin UUID unchanged (for SUBORDINATE),
     * target body resolvable + alive.
     */
    private static void executePendingSwap(MinecraftServer server, PendingSwap pending) {
        ServerPlayer player = server.getPlayerList().getPlayer(pending.playerUUID());
        if (player == null) {
            // Player disconnected during the delay — can't refund safely.
            LOGGER.info("[TM] swap: player {} disconnected before execute; {} magicule lost",
                    pending.playerUUID(), pending.magiculePaid());
            return;
        }

        ServerLevel level = player.serverLevel();
        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(level);
        GoblinIdentitySavedData.GoblinIdentity identity = saved.getById(pending.identityId());

        if (identity == null) {
            sendAdvisoryNotice(player, "Swap aborted — the goblin is gone. Magicule refunded.");
            refundMagicule(player, pending.magiculePaid());
            return;
        }
        if (identity.mode != pending.expectedMode()) {
            sendAdvisoryNotice(player, "Swap aborted — the goblin's state changed. Magicule refunded.");
            refundMagicule(player, pending.magiculePaid());
            return;
        }
        if (pending.expectedMode() == GoblinIdentitySavedData.Mode.SUBORDINATE
                && pending.expectedGoblinUUID() != null
                && !pending.expectedGoblinUUID().equals(identity.goblinEntityUUID)) {
            sendAdvisoryNotice(player, "Swap aborted — the goblin changed. Magicule refunded.");
            refundMagicule(player, pending.magiculePaid());
            return;
        }
        LivingEntity target = resolveTargetBody(player, identity);
        if (target == null || !target.isAlive()) {
            sendAdvisoryNotice(player, "Swap aborted — the target is no longer reachable. Magicule refunded.");
            refundMagicule(player, pending.magiculePaid());
            return;
        }

        // All validations pass — run the actual swap.
        executeAction(player, identity, target);
    }

    /**
     * Refund up to {@code amount} magicule to the player, capped at their
     * max. Used when a queued swap aborts on re-validation.
     */
    private static void refundMagicule(ServerPlayer player, double amount) {
        if (amount <= 0.0) return;
        ExistenceStorage exist = readExistence(player);
        if (exist == null) {
            LOGGER.warn("[TM] refund: couldn't read magicule storage for '{}' — {} magicule lost",
                    player.getName().getString(), amount);
            return;
        }
        double cur = exist.getMagicule();
        double max = EnergyHelper.getMaxMagicule(player);
        double newVal = (max > 0.0) ? Math.min(cur + amount, max) : (cur + amount);
        exist.setMagicule(newVal);
        exist.markDirty();
        LOGGER.info("[TM] refund: {} magicule returned to '{}' (was {}, now {})",
                amount, player.getName().getString(), cur, newVal);
    }

    /**
     * The shared cost gate. Returns {@link ChargeOutcome#charged(double)}
     * (with the deducted amount, used for refund-on-abort) if the action
     * should proceed, or {@link ChargeOutcome#notCharged()} if a confirm
     * prompt was sent OR if a read failed and an advisory was shown. The
     * caller runs the action (well — queues it for delayed execution) only
     * on {@code charged}.
     *
     * Cost = target's current EP × 0.25, deducted from player's magicule.
     */
    private static ChargeOutcome chargeOrPrompt(ServerPlayer player,
                                                GoblinIdentitySavedData.GoblinIdentity identity,
                                                LivingEntity target) {
        ExistenceStorage targetExist = readExistence(target);
        if (targetExist == null) {
            sendAdvisoryNotice(player, "Couldn't read the target's energy state.");
            return ChargeOutcome.notCharged();
        }
        double cost = targetExist.getEP() * 0.25;

        ExistenceStorage playerExist = readExistence(player);
        if (playerExist == null) {
            sendAdvisoryNotice(player, "Couldn't read your magicule.");
            return ChargeOutcome.notCharged();
        }
        double playerMagicule = playerExist.getMagicule();

        // Prompt boundary: spending this cost would leave the player at 0 or
        // below. Per the investigation, Tensura's handleSleepMode triggers
        // Sleep Mode entry on `magicule ≤ 0`.
        if (cost > 0.0 && playerMagicule <= cost) {
            String name = resolveDisplayName(player, identity);
            LOGGER.info("[TM] cost: '{}' has {} magicule, action costs {} for '{}' — would empty or overspend; prompting collapse confirmation",
                    player.getName().getString(), playerMagicule, cost, name);
            PacketDistributor.sendToPlayer(player, new Networking.OpenCollapseConfirmPayload(
                    identity.identityId, name, cost, playerMagicule));
            return ChargeOutcome.notCharged();
        }

        // Safe path: charge UP FRONT before queuing the swap. Refund happens
        // if the delayed execution aborts on re-validation.
        playerExist.setMagicule(playerMagicule - cost);
        playerExist.markDirty();
        LOGGER.info("[TM] cost: deducted {} magicule from '{}' (had {}, now {})",
                cost, player.getName().getString(), playerMagicule, playerMagicule - cost);
        return ChargeOutcome.charged(cost);
    }

    /** Run the existing send or summon helper. No cost checking — the gate
     *  has already been passed (sufficient or confirmed-collapse). */
    private static void executeAction(ServerPlayer player,
                                      GoblinIdentitySavedData.GoblinIdentity identity,
                                      LivingEntity target) {
        ServerLevel level = player.serverLevel();
        GoblinIdentitySavedData saved = GoblinIdentitySavedData.get(level);

        if (identity.mode == GoblinIdentitySavedData.Mode.SUBORDINATE) {
            if (!(target.level() instanceof ServerLevel goblinLevel)) {
                LOGGER.warn("[TM] action: goblin not on ServerLevel — aborting");
                return;
            }
            sendGoblinToColony(target, player, identity, goblinLevel, saved);
        } else {
            IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, level);
            if (colony == null) {
                sendAdvisoryNotice(player, "That goblin's colony no longer exists.");
                return;
            }
            ICitizenData cd = colony.getCitizenManager().getCivilian(identity.citizenId);
            if (cd == null) {
                sendAdvisoryNotice(player, "That goblin's citizen record is missing.");
                return;
            }
            summonGoblin(player, level, saved, identity, colony, cd);
        }
    }

    /** Resolve the live body for an identity. Returns null if not loaded. */
    private static LivingEntity resolveTargetBody(ServerPlayer player,
                                                  GoblinIdentitySavedData.GoblinIdentity identity) {
        if (identity.mode == GoblinIdentitySavedData.Mode.SUBORDINATE) {
            return findLivingEntityAcrossLevels(player.getServer(), identity.goblinEntityUUID);
        }
        IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, player.serverLevel());
        if (colony == null) return null;
        ICitizenData cd = colony.getCitizenManager().getCivilian(identity.citizenId);
        if (cd == null) return null;
        return cd.getEntity().orElse(null);
    }

    /** Lookup citizen name for advisory/prompt display. Falls back to "your goblin". */
    private static String resolveDisplayName(ServerPlayer player,
                                             GoblinIdentitySavedData.GoblinIdentity identity) {
        IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, player.serverLevel());
        if (colony == null) return "your goblin";
        ICitizenData cd = colony.getCitizenManager().getCivilian(identity.citizenId);
        return cd != null ? cd.getName() : "your goblin";
    }

    /** Read ExistenceStorage off any LivingEntity via the ManasCore mixin. */
    private static ExistenceStorage readExistence(LivingEntity entity) {
        if (entity instanceof StorageHolder holder) {
            return holder.manasCore$getStorage(ExistenceStorage.getKey());
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Stat sync helpers — copy IExistence (subset) + HP between bodies
    // ------------------------------------------------------------------

    /**
     * Field-by-field copy of the sync subset from one IExistence to another.
     *
     * Energy pools (aura / magicule / spiritualHealth) are copied as ABSOLUTE
     * values. The caller MUST invoke {@link #bumpEnergyMaxAttributes} on the
     * destination body first when the destination would otherwise have low
     * max-energy attributes (e.g. a default citizen with MAX_MAGICULE = 0).
     * Without that boost, dumping goblin-tier magicule into a citizen would
     * trigger Tensura's MagiculePoisonEffect with massive amplifier and kill
     * the body — see docs/decisions.md → "Energy pool scale mismatch".
     *
     * Aura and magicule are copied DIRECTLY (not via setEP — that splits
     * equally and erases imbalance).
     *
     * Counters and traits stay flat — no body-specific cap.
     */
    private static void copyStats(IExistence src, IExistence dst) {
        // Energy pools — absolute (destination must have been pre-boosted)
        dst.setAura(src.getAura());
        dst.setMagicule(src.getMagicule());
        dst.setSpiritualHealth(src.getSpiritualHealth());

        // Progression / counters — flat copy, no max-cap
        dst.setGainedEP(src.getGainedEP());
        dst.setSoulPoints(src.getSoulPoints());
        dst.setHumanKill(src.getHumanKill());

        // Character traits
        dst.setAlignment(src.getAlignment());
        dst.setOriginalAlignment(src.getOriginalAlignment());

        // Destiny flags
        dst.setDemonLordSeed(src.isDemonLordSeed());
        dst.setTrueDemonLord(src.isTrueDemonLord());
        dst.setBlessed(src.isBlessed());
        dst.setHeroEgg(src.isHeroEgg());
        dst.setTrueHero(src.isTrueHero());

        // Target-neutral list — clear and re-add.
        dst.clearNeutralTargets();
        for (UUID u : new java.util.ArrayList<>(src.getTargetNeutralList())) {
            dst.addNeutralTarget(u);
        }

        dst.markDirty();
    }

    /** ResourceLocation we use to track the swap-side energy-attribute modifiers
     *  so we can remove and re-apply them cleanly. */
    private static final net.minecraft.resources.ResourceLocation SWAP_ENERGY_BOOST_ID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "swap_energy_boost");

    /**
     * Lift the destination body's max-pool attributes up to at least the
     * source body's values. Covers four attributes:
     *   - MAX_AURA, MAX_MAGICULE, MAX_SPIRITUAL_HEALTH (Tensura)
     *   - MAX_HEALTH (vanilla)
     *
     * This makes absolute pool copy safe in all four cases — the destination
     * has enough headroom to hold the source's values without triggering
     * MagiculePoisonEffect (energy pools) or being capped (HP).
     *
     * Implemented as a tracked permanent AttributeModifier with our ResourceLocation,
     * so a second swap onto the same body removes the old modifier and re-applies
     * cleanly. When the body is discarded at the end of the swap (or on death),
     * the modifier goes with it.
     */
    private static void bumpBodyMaxAttributes(LivingEntity dst, LivingEntity src) {
        bumpAttributeTo(dst, TensuraAttributes.MAX_AURA, EnergyHelper.getMaxAura(src));
        bumpAttributeTo(dst, TensuraAttributes.MAX_MAGICULE, EnergyHelper.getMaxMagicule(src));
        bumpAttributeTo(dst, TensuraAttributes.MAX_SPIRITUAL_HEALTH,
                        src.getAttributeValue(TensuraAttributes.MAX_SPIRITUAL_HEALTH));
        bumpAttributeTo(dst, Attributes.MAX_HEALTH, src.getMaxHealth());
    }

    private static void bumpAttributeTo(LivingEntity entity, Holder<Attribute> attrHolder, double target) {
        AttributeInstance instance = entity.getAttribute(attrHolder);
        if (instance == null) return;
        // Remove any prior boost from us so we don't compound on repeat swaps.
        instance.removeModifier(SWAP_ENERGY_BOOST_ID);
        double current = instance.getValue();
        if (current >= target) return;
        double delta = target - current;
        instance.addPermanentModifier(new AttributeModifier(
                SWAP_ENERGY_BOOST_ID, delta, AttributeModifier.Operation.ADD_VALUE));
    }

    /**
     * Copy HP as an absolute value, after the destination's MAX_HEALTH has
     * been bumped to at least the source's via {@link #bumpBodyMaxAttributes}.
     * No percentage scaling — both bodies show the same numeric HP.
     */
    private static void copyHealthAbsolute(LivingEntity src, LivingEntity dst) {
        float srcHealth = src.getHealth();
        if (srcHealth <= 0f) {
            // Defensive: if src was already dead, don't transfer 0 (a swap
            // shouldn't be a death). Leave dst's HP alone.
            return;
        }
        dst.setHealth(Math.min(srcHealth, dst.getMaxHealth()));
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
     * Single chokepoint for green-italic advisory chat text shown to the player.
     *
     * FUTURE FEATURE — Great Sage gating: ALL advisory/analytical text the mod
     * surfaces to the player should eventually only appear when the player has
     * the Tensura "Great Sage" skill (or equivalent analysis skill). See
     * docs/decisions.md → "Advisory messages gated by Great Sage". Every
     * advisory call site routes through this helper so the gate can be added
     * in one place when implemented.
     */
    static void sendAdvisoryNotice(Player player, String text) {
        Component msg = Component.literal(text)
                .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC);
        player.sendSystemMessage(msg);
    }

    private static void sendOverflowNotice(Player player, String goblinName) {
        sendAdvisoryNotice(player, goblinName +
                " cannot carry as much while working for you; the excess items have been returned to you.");
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
