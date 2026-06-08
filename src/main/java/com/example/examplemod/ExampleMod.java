package com.example.examplemod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.eventbus.events.colony.ColonyCreatedModEvent;
import com.minecolonies.api.eventbus.events.colony.ColonyDeletedModEvent;
import com.minecolonies.api.eventbus.events.colony.citizens.CitizenAddedModEvent;
import com.minecolonies.api.eventbus.events.colony.citizens.CitizenDiedModEvent;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.util.EntityUtils;

import java.util.EnumSet;
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
import io.github.manasmods.tensura.entity.monster.GoblinEntity;
import io.github.manasmods.tensura.entity.monster.LizardmanEntity;
import io.github.manasmods.tensura.entity.monster.OrcEntity;
import io.github.manasmods.tensura.entity.variant.MagicCircleVariant;
import io.github.manasmods.tensura.event.TensuraEntityEvents;
import io.github.manasmods.tensura.entity.template.subclass.ISubordinate;
import io.github.manasmods.tensura.util.SubordinateHelper;
import io.github.manasmods.manascore.skill.api.EntityEvents;
import io.github.manasmods.manascore.network.api.util.Changeable;
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
import net.minecraft.world.entity.Mob;
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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Mod(ExampleMod.MODID)
public class ExampleMod {

    public static final String MODID = "tensura_minecolonies";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Gamerule: when {@code true}, innately-hostile Tensura mobs treat colony
     * citizens as valid prey alongside players (the Option B / mixin behaviour).
     * When {@code false}, citizens are invisible to Tensura's hostile-prey
     * predicate and the mob's normal player/animal targeting is unaffected.
     * Default {@code true} so existing worlds keep working as before.
     * Registered in {@link #commonSetup}; the mixin reads this Key.
     */
    public static net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.BooleanValue> RULE_HOSTILE_TO_CITIZENS;

    /**
     * Gamerule: maximum distinct non-COLONIST envoy races a single player can
     * ever receive across all their colonies. Default {@code 2} — so out of
     * the (eventually) three+ non-colonist races, each player only ever sees
     * envoys from at most two of them. Hit at spawn time: once a player has
     * seen {@code cap}-many distinct non-colonist races' envoys, the
     * scheduler stops proposing further non-colonist envoys for that player.
     * COLONIST envoys are not counted toward this cap.
     */
    public static net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.IntegerValue> RULE_MAX_NON_COLONIST_ENVOYS;

    // Stage E — circle visuals + delayed swap + sink/rise animations.
    private static final int   SWAP_DELAY_TICKS     = 40;    // ~2.0s — body change after this many ticks
    private static final int   RISE_DURATION_TICKS  = 20;    // ~1.0s — how long the rise from underground takes
    private static final int   CIRCLE_DURATION_TICKS = 80;   // ~4.0s — covers sink + delay + rise + afterglow
    private static final float CIRCLE_SIZE          = 3.0f;  // ~3× the default MagicCircle visual scale
    private static final double SINK_DEPTH          = 3.0;   // blocks the dissolve body falls during the delay
    private static final double RISE_START_OFFSET   = 2.0;   // blocks below surface the materialize body spawns

    /** Queued circle entity awaiting discard after its lifetime expires. */
    private record PendingCircleDiscard(UUID circleUUID, ResourceKey<Level> dim, long discardAtTick) {}

    /** Queued swap action awaiting execution after the dramatic delay.
     *  {@code materializePos} is captured at queue time so the materialize body
     *  appears where the circle is, regardless of player movement during the
     *  delay. For summon: raytraced from the player's view. For send: the
     *  colony's town hall position. */
    private record PendingSwap(UUID playerUUID,
                               UUID identityId,
                               RaceIdentitySavedData.Mode expectedMode,
                               UUID expectedGoblinUUID,
                               double magiculePaid,
                               long executeAtTick,
                               Vec3 materializePos) {}

    /** Queued linear Y-axis animation with X/Z locked to a fixed position.
     *  Each tick: setPos(lockX, interpolatedY, lockZ), deltaMovement zeroed,
     *  fallDistance zeroed. Locking X/Z prevents the entity's AI from
     *  walking out of its circle during the sink/rise. Zeroing fallDistance
     *  prevents accumulated fall damage from kicking in when invulnerability
     *  is cleared at the end. When {@code clearInvulnerableOnEnd} is true,
     *  the entity's invulnerable flag is reset on the final tick. */
    private record VerticalMovement(UUID entityUUID,
                                    ResourceKey<Level> dim,
                                    double lockX,
                                    double lockZ,
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
        // "Patrol Colony Outskirts" subordinate command — per-entity tick
        // driver lives in its own handler class. The command-cycle branch is
        // hooked from onEntityInteract below.
        NeoForge.EVENT_BUS.register(new SubordinatePatrol());
        modEventBus.addListener(this::addCreative);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Tensura uses Architectury's event system — register via .register(), NOT @SubscribeEvent.
        TensuraEntityEvents.NAMING_EVENT.register(this::onRaceNamed);

        // Veto subordinate target-acquisition on the player's own colony citizens.
        // RetaliateOrTarget.start() fires this ManasCore event before committing,
        // so returning interruptFalse() here aborts the assist-target without a mixin.
        EntityEvents.LIVING_CHANGE_TARGET.register(ExampleMod::onSubordinateChangeTarget);

        // Stage C2a — networking foundation. Payload registration is mod-bus.
        modEventBus.addListener(Networking::register);

        // Stage F1 — NeoForge data-attachment registry. Registered on the
        // mod bus alongside blocks/items/tabs above.
        Attachments.register(modEventBus);

        // Client-only setup (keybind + tick listener). Guard prevents the
        // server JVM from ever loading client-only classes.
        if (FMLEnvironment.dist.isClient()) {
            ClientEvents.init(modEventBus);
        }
    }

    // ------------------------------------------------------------------
    // Subordinate vs. own-colony-citizen target veto
    // ------------------------------------------------------------------
    private static EventResult onSubordinateChangeTarget(LivingEntity entity, Changeable<LivingEntity> target) {
        if (entity.level().isClientSide()) return EventResult.pass();
        if (!(entity instanceof ISubordinate)) return EventResult.pass();

        LivingEntity proposed = target.get();
        if (proposed == null) return EventResult.pass();

        UUID ownerUuid = SubordinateHelper.getSubordinateOwnerUUID(entity);
        if (ownerUuid == null) return EventResult.pass();

        // (1) Never target colony citizens. (Patrol roams a colony, and the
        // player's subordinates shouldn't assist-attack colonists when the
        // player hits one — see docs/subordinate-citizen-targeting.md.)
        if (proposed instanceof AbstractEntityCitizen) return EventResult.interruptFalse();

        // (2) Never target friendly Tensura races. Goblins and lizardmen are
        // spared unconditionally; orcs are spared only when NOT hostile to the
        // player (a tamed / allied orc is a subordinate, not an enemy). A wild
        // orc falls through and may be targeted, so the patrol still fights
        // hostile orcs. Orc lord / disaster extend OrcEntity, so the same rule
        // covers them.
        if (proposed instanceof GoblinEntity || proposed instanceof LizardmanEntity) {
            return EventResult.interruptFalse();
        }
        if (proposed instanceof OrcEntity orc) {
            boolean friendly = orc.isTame() || entity.isAlliedTo(orc);
            if (friendly) return EventResult.interruptFalse();
            // wild / hostile orc — allow targeting
        }

        return EventResult.pass();
    }

    // ------------------------------------------------------------------
    // Stage A — naming creates CitizenData + identity record, no body
    // ------------------------------------------------------------------

    private EventResult onRaceNamed(LivingEntity entity,
                                      net.minecraft.world.entity.player.Player player,
                                      io.github.manasmods.manascore.network.api.util.Changeable<Double> magicule,
                                      io.github.manasmods.manascore.network.api.util.Changeable<Double> aura,
                                      io.github.manasmods.manascore.network.api.util.Changeable<io.github.manasmods.tensura.network.c2s.RequestNamingMenuPacket.NamingType> namingType,
                                      io.github.manasmods.manascore.network.api.util.Changeable<String> name) {

        // Envoy suppression — diplomatic envoys must never become citizens
        // even if Tensura's naming menu opens on them (we can't block the
        // menu from opening pre-mixin; see docs/envoy-system.md). Bouncing
        // here at NAMING_EVENT.interruptFalse halts Tensura's naming
        // commitment and our citizen-creation pipeline never runs.
        if (entity.hasData(Attachments.ENVOY_TAG.get())) {
            if (player instanceof ServerPlayer) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Envoys cannot be named.")
                        .withStyle(ChatFormatting.RED));
            }
            return EventResult.interruptFalse();
        }

        // Race-aware filter — passes through ANY entity type registered in
        // Races (goblin, orc, future races). Unrecognised types early-out.
        Race race = Races.of(entity.getType());
        if (race == null) {
            return EventResult.pass();
        }

        LOGGER.info("[TM] {} named: {}", race, name.get());

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
            RaceIdentitySavedData saved = RaceIdentitySavedData.get(serverLevel);
            RaceIdentitySavedData.PendingRaceMob p = new RaceIdentitySavedData.PendingRaceMob(
                    UUID.randomUUID(),
                    name.get(),
                    entity.getUUID(),
                    player.getUUID(),         // matches IExistence.permanentOwner set by Tensura
                    race                      // carry race through so the drain tags correctly
            );
            saved.addPending(p);
            LOGGER.info("[TM] no colony yet — '{}' queued as pending (id={}, mob={}, race={})",
                    name.get(), p.identityId, p.mobEntityUUID, race);
            return EventResult.pass();
        }

        // --- Stage A: create CitizenData (count +1), name it, NO body yet ---

        ICitizenData citizenData = colony.getCitizenManager().createAndRegisterCivilianData();
        citizenData.setName(name.get());

        // Apply the race's starting-bias skill profile ON TOP of MC's
        // random init. Once at naming time — persists on CitizenData
        // across send/summon. MC's progression (XP from working,
        // levelUp) continues normally from the biased values; the bias
        // does NOT disable progression.
        RaceSkillProfiles.applyForRace(citizenData, race, serverLevel.getRandom());

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

        RaceIdentitySavedData saved = RaceIdentitySavedData.get(serverLevel);
        RaceIdentitySavedData.RaceIdentity identity = new RaceIdentitySavedData.RaceIdentity(
                UUID.randomUUID(),          // stable identity UUID
                citizenData.getId(),
                colony.getID(),
                entity.getUUID(),           // current mob entity UUID
                RaceIdentitySavedData.Mode.SUBORDINATE,
                null,                       // entitySnapshot — populated at first send
                player.getUUID(),           // owner — matches IExistence.permanentOwner
                race
        );
        saved.addIdentity(identity);

        LOGGER.info("[TM] identity {} stored: citizen={} mob={} race={} mode=SUBORDINATE",
                identity.identityId, identity.citizenId, identity.mobEntityUUID, race);

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

        // ENVOY FIRST: any right-click on an envoy opens the diplomacy
        // dialogue, regardless of sneak or main-hand item. Cancelling the
        // event also suppresses the vanilla villager trade screen for
        // colonist envoys.
        if (event.getTarget().hasData(Attachments.ENVOY_TAG.get())
                && event.getEntity() instanceof ServerPlayer sp) {
            EnvoyTag tag = event.getTarget().getData(Attachments.ENVOY_TAG.get());
            if (tag != null && tag.state() == EnvoyTag.State.ALIVE) {
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                PacketDistributor.sendToPlayer(sp, new Networking.OpenEnvoyDialoguePayload(
                        event.getTarget().getId(),
                        (byte) tag.member().getId(),
                        tag.colonyId(),
                        tag.conditionMask()));
                return;
            }
        }

        // Shift-right-click send-to-colony was removed by request. Sending
        // a subordinate to the colony now goes through the roster menu only
        // (G keybind → click the entry, or the bulk-send action), which
        // routes through the same {@link #handleMenuAction} chokepoint.
        // Leaving this method present (and the envoy branch above) keeps
        // the envoy dialogue working — only the send-by-sneak path is gone.

        // "Patrol Colony Outskirts" — a fourth command added INTO Tensura's
        // existing command set (FOLLOW → WANDER → STAY → PATROL → FOLLOW),
        // activated the same way the native commands are: sneak + right-click
        // + empty main hand (the gesture that already reaches cycleCommands
        // for humanoid subordinates; plain right-click opens the inventory /
        // mounts a mount). We intercept ONLY the two edges that touch PATROL
        // and let the event pass through for the other two, so Tensura's own
        // cycleCommands emits the native FOLLOW→WANDER and WANDER→STAY steps
        // (and their AQUA messages) unchanged — no double-cycling.
        if (event.getEntity() instanceof ServerPlayer sp
                && sp.isSecondaryUseActive()
                && sp.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()
                && event.getTarget() instanceof Mob targetMob
                && SubordinatePatrol.isNamedSubordinateOf(targetMob, sp)) {
            if (SubordinatePatrol.isPatrolling(targetMob)) {
                // PATROL → FOLLOW (ours): cancel so native cycle doesn't run.
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                SubordinatePatrol.exitPatrolToFollow(targetMob, sp);
            } else if (targetMob instanceof ISubordinate sub && sub.isOrderedToSit()) {
                // STAY → PATROL (ours): cancel so native STAY→FOLLOW doesn't run.
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                SubordinatePatrol.beginPatrol(targetMob, sp);
            }
            // FOLLOW → WANDER / WANDER → STAY: not cancelled — Tensura's
            // native cycleCommands handles these the moment mobInteract runs.
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

        // Stage 3a — record creation tick so the colonist-envoy "3 days old"
        // condition has a stable origin. Idempotent.
        ColonyRaceConfigSavedData.get(serverLevel).recordColonyCreation(
                colony.getID(), serverLevel.getGameTime());

        MinecraftServer server = serverLevel.getServer();
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(serverLevel);

        // Snapshot to a separate list so we can mutate saved.pending during iteration.
        List<RaceIdentitySavedData.PendingRaceMob> drain =
                new java.util.ArrayList<>(saved.getPending());
        if (drain.isEmpty()) {
            LOGGER.info("[TM] colony '{}' created — no pending goblins to promote",
                    colony.getName());
            // Fall through — drain done (nothing to do), now open the picker.
            openPickerForNewColony(serverLevel, colony);
            return;
        }

        LOGGER.info("[TM] colony '{}' created — draining {} pending goblin(s)",
                colony.getName(), drain.size());

        for (RaceIdentitySavedData.PendingRaceMob p : drain) {
            // Stale check: drop pending entry if the goblin entity is gone.
            LivingEntity goblin = findLivingEntityAcrossLevels(server, p.mobEntityUUID);
            if (goblin == null) {
                LOGGER.info("[TM] pending '{}': goblin {} no longer alive — discarding stale entry",
                        p.name, p.mobEntityUUID);
                saved.removePending(p);
                continue;
            }

            // Same promotion path as a normal naming-with-colony.
            ICitizenData citizenData = colony.getCitizenManager().createAndRegisterCivilianData();
            citizenData.setName(p.name);
            // Apply race skill profile here too — pending-pool drain is
            // semantically equivalent to "named-then-immediately-promoted",
            // so we want the same starting bias.
            RaceSkillProfiles.applyForRace(citizenData, p.race, serverLevel.getRandom());
            colony.getTravellingManager().startTravellingTo(
                    citizenData, goblin.blockPosition(), Integer.MAX_VALUE);

            RaceIdentitySavedData.RaceIdentity identity = new RaceIdentitySavedData.RaceIdentity(
                    p.identityId,                                   // reuse the stable id from pending
                    citizenData.getId(),
                    colony.getID(),
                    p.mobEntityUUID,
                    RaceIdentitySavedData.Mode.SUBORDINATE,
                    null,                                           // entitySnapshot — populated at first send
                    p.ownerPlayerUUID,                              // propagate from pending entry
                    p.race                                          // propagate race so renderer picks correctly
            );
            saved.addIdentity(identity);
            saved.removePending(p);

            LOGGER.info("[TM] pending '{}' promoted: citizen id={} race={} in '{}' (now {} citizens)",
                    p.name, citizenData.getId(), p.race, colony.getName(),
                    colony.getCitizenManager().getCurrentCitizenCount());
        }

        // After drain: now open the picker.
        openPickerForNewColony(serverLevel, colony);
    }

    /**
     * Mark the new colony as pending race choice and send the picker
     * payload to its owner. The pending state suppresses ALL population
     * growth in {@code onCitizenAdded} until the player picks.
     *
     * Owner lookup: {@code colony.getPermissions().getOwner()} gives
     * the UUID; we resolve it to a ServerPlayer via the player list.
     * If the owner isn't currently online we just record the pending
     * state — the {@code onPlayerLoggedIn} handler re-sends the picker
     * when they connect.
     */
    private static void openPickerForNewColony(ServerLevel serverLevel, IColony colony) {
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(serverLevel);

        // Idempotent — markPending no-ops if already set, e.g. on a
        // reload or duplicate event delivery.
        config.markPending(colony.getID());
        LOGGER.info("[TM] colony '{}' (id={}) marked PENDING race choice",
                colony.getName(), colony.getID());

        UUID ownerUuid = colony.getPermissions().getOwner();
        if (ownerUuid == null) return;
        ServerPlayer owner = serverLevel.getServer().getPlayerList().getPlayer(ownerUuid);
        if (owner == null) {
            // Will re-send on PlayerLoggedInEvent — see onPlayerLoggedIn below.
            // The advancement is also re-granted there (idempotent — award()
            // returns false when already held).
            LOGGER.info("[TM] colony '{}': owner not online — picker will re-open on login",
                    colony.getName());
            return;
        }
        grantRookieRuler(owner);
        PacketDistributor.sendToPlayer(owner,
                new Networking.OpenRacePickerPayload(colony.getID(), colony.getName()));
    }

    /**
     * Grant the "Rookie Ruler" advancement to {@code player}. Idempotent —
     * {@link net.minecraft.server.PlayerAdvancements#award} returns false
     * when the player already holds it, so re-calling on login or repeat
     * colony creation is safe.
     *
     * The advancement uses {@code minecraft:impossible} as its trigger (see
     * {@code data/tensura_minecolonies/advancement/rookie_ruler.json}) so it
     * can only be granted programmatically by this method.
     */
    private static void grantRookieRuler(ServerPlayer player) {
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server == null) return;
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, "rookie_ruler");
        net.minecraft.advancements.AdvancementHolder adv = server.getAdvancements().get(id);
        if (adv == null) {
            LOGGER.warn("[TM] advancement {} not found — datapack not loaded?", id);
            return;
        }
        boolean newlyAwarded = player.getAdvancements().award(adv, "create_colony");
        if (newlyAwarded) {
            LOGGER.info("[TM] granted 'Rookie Ruler' to {}", player.getName().getString());
        }
    }

    // ------------------------------------------------------------------
    // Stage B — race-aware population spawn
    //
    // Intercept the INITIAL CitizenAddedModEvent (fired after
    // CitizenManager.onColonyTick has already spawned the citizen) and,
    // for colonies with a chosen race, undo the spawn and substitute a
    // wild unnamed Tensura race-mob the player can then name through
    // the existing onRaceNamed pipeline.
    //
    // Source filter: INITIAL only. BORN (citizen babies growing in
    // colony), HIRED (recruitment interaction), RESURRECTED (revive
    // mechanic), COMMANDS (manual /mc citizens) all keep their vanilla
    // behaviour.
    //
    // Discard side effects: citizen.discard() fires
    // CitizenRemovedModEvent (we do NOT subscribe to it; only MC's own
    // EntityCitizen self-listens, no third party reads it). It does
    // NOT fire CitizenDiedModEvent (that's reserved for die()), so the
    // identity-death cleanup in onCitizenDied is untouched.
    //
    // Spawn-then-undo flash on clients: empirically inspected against
    // the MC entity tracker — the ClientboundAddEntityPacket is dispatched
    // at end-of-tick, and our discard sets the entity's RemovalReason
    // within the same synchronous server-tick, so the tracker sees the
    // entity as removed before any packet is sent. ColonyView messages
    // remove an unknown id on the client (no-op).
    //
    // Accepted risk recorded in decisions.md: third-party mods that
    // subscribe to CitizenAddedModEvent(INITIAL) would observe a stale
    // citizen reference for one event dispatch. The clean fix (mixin
    // to short-circuit before createAndRegisterCivilianData runs) is
    // deferred — not worth the coremod cost until empirically needed.
    // ------------------------------------------------------------------

    private void onCitizenAdded(CitizenAddedModEvent event) {
        if (event.getSource() != CitizenAddedModEvent.CitizenAddedSource.INITIAL) return;

        ICitizenData citizenData = (ICitizenData) event.getCitizen();
        IColony colony = citizenData.getColony();
        if (!(colony.getWorld() instanceof ServerLevel serverLevel)) return;

        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(serverLevel);
        int colonyId = colony.getID();

        // Tri-state lookup:
        //   1. pendingChoice  → suppress ALL growth (discard, no race mob)
        //   2. raceByColony null AND not pending → vanilla MC citizen (covers
        //      DEFAULT-chosen AND legacy colonies, both indistinguishable
        //      by design — no-entry = "use vanilla")
        //   3. raceByColony has GOBLIN/ORC → discard + spawn wild race mob
        if (config.isPending(colonyId)) {
            // Suppress: discard the freshly-spawned citizen and undo the
            // count. Do NOT spawn a wild mob — the colony grows nothing
            // until the player picks. Same discard+removeCivilian path as
            // the race-mob branch, just no spawnWildRaceMob.
            citizenData.getEntity().ifPresent(net.minecraft.world.entity.Entity::discard);
            colony.getCitizenManager().removeCivilian(citizenData);
            LOGGER.info("[TM] race spawn: colony '{}' is pending race choice — citizen discarded, no mob spawned",
                    colony.getName());
            return;
        }

        // Pick one member at random from the colony's composition set.
        // Single-member sets (the common case) just return that member;
        // multi-member sets get a fresh draw per spawn tick.
        ColonyMember picked = config.pickRandomMember(colonyId, serverLevel.getRandom());
        if (picked == null) {
            // No entry → legacy / pre-menu colony → vanilla MC behaviour.
            return;
        }
        java.util.Optional<Race> raceOpt = picked.toRace();
        if (raceOpt.isEmpty()) {
            // COLONIST drawn this tick — leave the vanilla citizen alive.
            // Mixed colonies (e.g. {COLONIST, GOBLIN}) hit this branch
            // proportionally to COLONIST's share of the set.
            return;
        }
        Race race = raceOpt.get();

        // Capture spawn position BEFORE discarding the citizen entity.
        // Prefer the entity's actual landed position (MC's safe-spot search
        // may have offset from the town hall), fall back to town hall.
        net.minecraft.core.BlockPos spawnPos = citizenData.getEntity()
                .map(net.minecraft.world.entity.Entity::blockPosition)
                .orElseGet(() -> colony.getServerBuildingManager().hasTownHall()
                        ? colony.getServerBuildingManager().getTownHall().getPosition()
                        : null);
        if (spawnPos == null) {
            LOGGER.warn("[TM] race spawn: colony '{}' has no town hall and no live citizen body — aborting interception",
                    colony.getName());
            return;
        }

        // 1. Discard the EntityCitizen. discard() → remove(DISCARDED) on
        //    EntityCitizen, which fires CitizenRemovedModEvent (not Died).
        citizenData.getEntity().ifPresent(net.minecraft.world.entity.Entity::discard);

        // 2. Undo the count + assignments by calling removeCivilian
        //    (which drops the CitizenData from the colony map, unassigns
        //    from all buildings — a fresh INITIAL citizen has no assignments
        //    so this is a no-op-on-buildings — and clears any work orders).
        colony.getCitizenManager().removeCivilian(citizenData);

        // 3. Spawn the wild unnamed race-mob at the captured position.
        spawnWildRaceMob(serverLevel, spawnPos, race, colony.getName());
    }

    /**
     * Spawn an unnamed wild race-mob of the colony's chosen race at
     * {@code spawnPos}. Uses {@code MobSpawnType.SPAWN_EGG} so the mob
     * does not despawn when the player wanders away — it must persist
     * until the player chooses to name it (or kills it).
     *
     * {@code finalizeSpawn} is called BEFORE {@code addFreshEntity} —
     * that's the hook Tensura uses to randomise the mob's variant
     * fields (gender, skin, face, hair, colours, clothing). Skipping
     * it would spawn identical default-appearance mobs every time.
     */
    private static void spawnWildRaceMob(ServerLevel serverLevel,
                                         net.minecraft.core.BlockPos spawnPos,
                                         Race race,
                                         String colonyName) {
        ResourceLocation typeId = Races.idFor(race);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(typeId);
        if (type == null) {
            LOGGER.error("[TM] race spawn: EntityType '{}' for race {} not registered — aborting",
                    typeId, race);
            return;
        }

        Entity created = type.create(serverLevel);
        if (!(created instanceof net.minecraft.world.entity.Mob mob)) {
            LOGGER.error("[TM] race spawn: factory for '{}' returned {} (not a Mob) — aborting",
                    typeId, created != null ? created.getClass().getName() : "null");
            return;
        }

        mob.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                serverLevel.getRandom().nextFloat() * 360f, 0f);

        // finalizeSpawn → Tensura's variant randomisation runs here.
        // SPAWN_EGG prevents the wild mob from despawning when far from
        // a player (required — it must wait around to be named).
        mob.finalizeSpawn(
                serverLevel,
                serverLevel.getCurrentDifficultyAt(spawnPos),
                net.minecraft.world.entity.MobSpawnType.SPAWN_EGG,
                null
        );

        boolean added = serverLevel.addFreshEntity(mob);
        if (added) {
            LOGGER.info("[TM] race spawn: '{}' wild mob spawned for colony '{}' at {} (uuid={})",
                    race, colonyName, spawnPos, mob.getUUID());
        } else {
            LOGGER.warn("[TM] race spawn: addFreshEntity returned false for {} at {}",
                    typeId, spawnPos);
        }
    }

    // ------------------------------------------------------------------
    // Envoy system — Stages 1-3a:
    //   1: spawn + marker + naming suppression
    //   2: dialogue + accept/decline diplomacy + nameplate + roam radius
    //   3a: unlock conditions + scheduler (this section)
    //   3b: kill-gate (not yet)
    // ------------------------------------------------------------------

    /** One in-game day in ticks. Vanilla day length. */
    private static final long TICKS_PER_DAY = 24000L;
    /** Required gap between envoy resolves at a single colony. */
    private static final long ENVOY_RESOLVE_GAP_TICKS = 3L * TICKS_PER_DAY;
    /** Required colony age before the first COLONIST envoy is eligible. */
    private static final long COLONIST_UNLOCK_AGE_TICKS = 3L * TICKS_PER_DAY;
    /** Named-goblin count required for GOBLIN envoy eligibility. */
    private static final int GOBLIN_UNLOCK_NAMED_COUNT = 3;
    /** Citizen count required for ORC envoy eligibility. */
    private static final int ORC_UNLOCK_CITIZEN_COUNT = 25;
    /** Citizen count required for LIZARDMAN envoy eligibility. The
     *  marsh-tribe's "is this colony worth our notice" threshold sits below
     *  the orc bar — lizardmen scout earlier than orcs commit. */
    private static final int LIZARDMAN_UNLOCK_CITIZEN_COUNT = 15;
    /** Original placeholder branch — kept as ONE OF the dwarf eligibility
     *  alternatives. The real deferred-content conditions (20 days,
     *  dwarven village, demon lord, hero) are now also wired in as
     *  alternatives; any one qualifies. */
    private static final int DWARF_UNLOCK_CITIZEN_COUNT_PLACEHOLDER = 30;
    /** Registry path of MineColonies' Miner building (the "Miner's Hut"). */
    private static final String MC_BUILDING_MINER = "miner";

    /** In-game days the owning player must go without dying before the
     *  dwarf 20-days-no-death condition unlocks. */
    private static final long DWARF_UNLOCK_DAYS_NO_DEATH = 20L;
    /** Penalty applied to the 20-days-no-death timer on a dwarf kill —
     *  the {@code lastOwnerDeathTick} anchor moves forward by this many
     *  in-game days (capped at "now" so penalty is never negative). The
     *  spec calls this "10 days lost" — partial reset, not a full one. */
    private static final long DWARF_KILL_GATE_DAY_PENALTY = 10L;

    /** Cached structure key for {@code tensura:dwarf_village}. Used by the
     *  per-tick player-in-village poll for the dwarven-village dwarf
     *  condition. Lazy lookup — the registry is per-server. */
    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure>
            DWARF_VILLAGE_STRUCTURE_KEY = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.STRUCTURE,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("tensura", "dwarf_village"));

    /** Does {@code colony} have at least one Miner / Miner's Hut building?
     *  Iterates the colony's registered structures and checks each one's
     *  {@code BuildingEntry.getRegistryName().getPath()} against
     *  {@link #MC_BUILDING_MINER}. */
    private static boolean colonyHasMiner(IColony colony) {
        // IColony.getCommonBuildingManager() returns the dimension-agnostic
        // manager whose getBuildings() map covers both server colonies and
        // colony views. Iterate values and compare each building's registry
        // path against the Miner ID.
        for (Object obj : colony.getCommonBuildingManager().getBuildings().values()) {
            if (!(obj instanceof com.minecolonies.api.colony.buildings.ICommonBuilding cb)) continue;
            var type = cb.getBuildingType();
            if (type == null) continue;
            var name = type.getRegistryName();
            if (name != null && MC_BUILDING_MINER.equals(name.getPath())) {
                return true;
            }
        }
        return false;
    }
    /**
     * Scheduler tick cadence. Fires often so {@code /time add} advancement
     * is picked up promptly (using {@code server.getTickCount()} every-day
     * cadence misaligned with {@code level.getGameTime()} jumps from time
     * commands and meant scheduled envoys never appeared during testing).
     * The day-based gates inside {@link #tryScheduleEnvoy} use
     * {@code level.getGameTime()} so the actual spawn timing is unchanged
     * in normal play — 20 ticks (1 s) just bounds how long after a time
     * jump the next eligible envoy appears.
     */
    private static final long ENVOY_SCHEDULER_PERIOD_TICKS = 20L;

    /** Count named-goblin identities the player has registered in {@code colonyId}.
     *  Iterates the global identity store; N is small for a typical save. */
    private static int countNamedGoblinsInColony(ServerLevel level, int colonyId) {
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        int count = 0;
        for (RaceIdentitySavedData.RaceIdentity id : saved.all()) {
            if (id.colonyId == colonyId && id.race == Race.GOBLIN) count++;
        }
        return count;
    }

    /** Is the colony eligible for an envoy of {@code member} right now?
     *  Per-member condition + not-already-in-set + not-already-accepted +
     *  Stage 3b kill-gate resets honored.
     *
     *  Kill-gate semantics per condition shape:
     *  - COLONIST timer: anchor at {@code max(colonyCreationTick, killResetTick)}.
     *  - GOBLIN cumulative: compare against {@code goblinNamedBaseline} so
     *    only goblins named AFTER the reset count toward the unlock.
     *  - ORC current-value: require {@code currentCount > orcCitizenSnapshot}
     *    so the colony has to GROW past the kill-time count (re-cross the
     *    threshold). Snapshot is cleared once the eligibility is satisfied
     *    by clearing in the envoy-resolve path.
     */
    static boolean isEnvoyEligible(ServerLevel level, IColony colony, ColonyMember member,
                                   ColonyRaceConfigSavedData config) {
        int colonyId = colony.getID();
        if (config.getMembers(colonyId).contains(member)) return false;          // already in set
        if (config.acceptedEnvoys(colonyId).contains(member)) return false;     // already accepted
        long now = level.getGameTime();
        return switch (member) {
            case COLONIST -> {
                long created = config.getColonyCreationTick(colonyId, now);
                long killReset = config.getColonistKillResetTick(colonyId, Long.MIN_VALUE);
                long anchor = Math.max(created, killReset);
                yield (now - anchor) >= COLONIST_UNLOCK_AGE_TICKS;
            }
            case GOBLIN -> {
                int count = countNamedGoblinsInColony(level, colonyId);
                int baseline = config.getGoblinNamedBaseline(colonyId);
                yield (count - baseline) >= GOBLIN_UNLOCK_NAMED_COUNT;
            }
            case ORC -> {
                // Alternative 1: ≥25 citizens (existing — gated by the
                // kill-snapshot reset). Alternative 2: orc disaster
                // defeated (per-player, immune to all resets).
                UUID owner = colony.getPermissions().getOwner();
                if (owner != null && config.hasDefeatedOrcDisaster(owner)) yield true;
                int current = colony.getCitizenManager().getCurrentCitizenCount();
                if (current < ORC_UNLOCK_CITIZEN_COUNT) yield false;
                int snapshot = config.getOrcCitizenSnapshot(colonyId);
                // Snapshot of -1 means "no kill reset pending"; threshold-only.
                // Otherwise the colony must have grown past the kill-time count.
                yield snapshot < 0 || current > snapshot;
            }
            case LIZARDMAN -> {
                // Alternative 1: ≥15 citizens (existing). Alternative 2:
                // ifrit defeated (per-player, cleared on lizardman kill).
                UUID owner = colony.getPermissions().getOwner();
                if (owner != null && config.hasDefeatedIfrit(owner)) yield true;
                int current = colony.getCitizenManager().getCurrentCitizenCount();
                if (current < LIZARDMAN_UNLOCK_CITIZEN_COUNT) yield false;
                int snapshot = config.getLizardmanCitizenSnapshot(colonyId);
                yield snapshot < 0 || current > snapshot;
            }
            case DWARF -> isDwarfEligible(level, colony, config, colonyId, now);
        };
    }

    /** Dwarf eligibility — multi-alternative. Returns true if ANY of the
     *  five conditions is met. Pulled out into its own method because the
     *  alternatives don't fit cleanly in an arrow-case body.
     *
     *  Alternatives (any one qualifies):
     *  <ol>
     *    <li>Placeholder: ≥30 citizens AND a Miner / Miner's Hut built
     *        (gated by {@code dwarfCitizenSnapshot} reset).</li>
     *    <li>20 in-game days since the owning player last died (per-colony,
     *        anchor advances by +10 days on dwarf kill — partial penalty).</li>
     *    <li>Owning player has entered a {@code tensura:dwarf_village}
     *        (per-player flag, cleared on dwarf kill).</li>
     *    <li>Owning player is currently a true demon lord AND their
     *        demon-lord-path-disabled flag is unset (live
     *        {@code IExistence.isTrueDemonLord()} read).</li>
     *    <li>Owning player is currently a true hero AND their
     *        hero-path-disabled flag is unset.</li>
     *  </ol>
     */
    private static boolean isDwarfEligible(ServerLevel level, IColony colony,
                                           ColonyRaceConfigSavedData config,
                                           int colonyId, long now) {
        UUID owner = colony.getPermissions().getOwner();

        // 1. Placeholder branch (citizen count + Miner's Hut).
        int current = colony.getCitizenManager().getCurrentCitizenCount();
        if (current >= DWARF_UNLOCK_CITIZEN_COUNT_PLACEHOLDER && colonyHasMiner(colony)) {
            int snapshot = config.getDwarfCitizenSnapshot(colonyId);
            if (snapshot < 0 || current > snapshot) return true;
        }

        // 2. 20 in-game days since owner's last death. If no death has
        //    been recorded, the colony's creation tick is the anchor so a
        //    long-running colony with a never-dying owner qualifies on
        //    the colony's 20th day.
        long anchor = config.getLastOwnerDeathTick(colonyId,
                config.getColonyCreationTick(colonyId, now));
        if ((now - anchor) / TICKS_PER_DAY >= DWARF_UNLOCK_DAYS_NO_DEATH) return true;

        // The remaining three conditions are per-owning-player. No owner
        // (system-owned / orphan colony) → these branches all fall through.
        if (owner == null) return false;

        // 3. Dwarven village found.
        if (config.hasEnteredDwarvenVillage(owner)) return true;

        // 4 / 5. Live demon-lord / hero status. Disable flags veto. The
        //        lookup is by online ServerPlayer — if the owning player
        //        is offline, we can't read their IExistence, so the
        //        condition silently doesn't qualify until they next log
        //        in. (The eligible set will then update on the next
        //        scheduler tick once they're online.)
        ServerPlayer ownerPlayer = level.getServer().getPlayerList().getPlayer(owner);
        if (ownerPlayer != null) {
            IExistence ex = readExistenceSafe(ownerPlayer);
            if (ex != null) {
                if (ex.isTrueDemonLord() && !config.isDemonLordPathDisabled(owner)) return true;
                if (ex.isTrueHero() && !config.isHeroPathDisabled(owner)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Deferred-content envoy conditions — event hooks
    // ------------------------------------------------------------------

    /** Death-tick bump for the colony's owning player. Walks every colony
     *  on this level and updates the per-colony {@code lastOwnerDeathTick}
     *  to {@code now} for any colony the dying player owns. Used by the
     *  dwarf 20-days-no-death alternative. */
    private static void processColonyOwnerDeath(ServerLevel level, LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer dyingPlayer)) return;
        UUID dyingUuid = dyingPlayer.getUUID();
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
        long now = level.getGameTime();
        for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
            UUID owner = colony.getPermissions().getOwner();
            if (owner == null || !owner.equals(dyingUuid)) continue;
            config.setLastOwnerDeathTick(colony.getID(), now);
            LOGGER.info("[TM] envoy: owner of colony {} ('{}') died — 20-day timer anchor reset to {}",
                    colony.getID(), colony.getName(), now);
        }
    }

    /** Boss-kill flag flips for orc disaster and ifrit. The kill must be
     *  credited to a player (direct DamageSource entity OR last-attacker
     *  via {@link net.minecraft.world.entity.LivingEntity#getKillCredit}).
     *  Orc disaster sets the permanent flag; ifrit sets the per-player
     *  cleared-on-lizardman-kill flag. */
    private static void processBossKillFlags(ServerLevel level, LivingDeathEvent event) {
        net.minecraft.world.entity.LivingEntity victim = event.getEntity();
        boolean isOrcDisaster = victim instanceof io.github.manasmods.tensura.entity.monster.OrcDisasterEntity;
        boolean isIfrit = victim instanceof io.github.manasmods.tensura.entity.monster.IfritEntity;
        if (!isOrcDisaster && !isIfrit) return;

        // Killer attribution: prefer the explicit DamageSource entity (for
        // direct projectile / melee kills where vanilla unwraps the
        // shooter), falling back to LivingEntity.getKillCredit() which
        // tracks last-damage-by-player within ~5s — used for kills via
        // skill DoT or environmental setups the player set up.
        ServerPlayer killer = null;
        net.minecraft.world.entity.Entity sourceEntity = event.getSource().getEntity();
        if (sourceEntity instanceof ServerPlayer sp) killer = sp;
        if (killer == null) {
            net.minecraft.world.entity.LivingEntity credit = victim.getKillCredit();
            if (credit instanceof ServerPlayer sp) killer = sp;
        }
        if (killer == null) return;

        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
        if (isOrcDisaster) {
            if (config.markOrcDisasterDefeated(killer.getUUID())) {
                LOGGER.info("[TM] envoy: player {} defeated the Orc Disaster — permanent orc envoy unlock",
                        killer.getName().getString());
            }
        } else { // isIfrit
            if (config.markIfritDefeated(killer.getUUID())) {
                LOGGER.info("[TM] envoy: player {} defeated Ifrit — lizardman envoy unlock (cleared on lizardman kill)",
                        killer.getName().getString());
            }
        }
    }

    /** Character reset scroll Finish hook. Clears both demon-lord and
     *  hero disable flags. Belt-and-braces — the scheduler's tick pass
     *  also clears the flag when {@code isTrueDemonLord/isTrueHero} is
     *  observed false, since {@code resetEverything} wipes
     *  {@link IExistence} (including these statuses). Hooking the item
     *  event fires immediately on use; the tick fallback covers
     *  admin-command resets and any other strip paths. */
    @SubscribeEvent
    public void onLivingUseItemFinish(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getItem().getItem() instanceof io.github.manasmods.tensura.item.misc.ResetScrollItem ri)) return;
        if (ri.getResetType() != io.github.manasmods.tensura.item.misc.ResetScrollItem.ResetType.RESET_ALL) return;

        ServerLevel level = player.serverLevel();
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
        UUID uuid = player.getUUID();
        boolean clearedDl = config.isDemonLordPathDisabled(uuid);
        boolean clearedHero = config.isHeroPathDisabled(uuid);
        if (clearedDl) config.clearDemonLordPathDisabled(uuid);
        if (clearedHero) config.clearHeroPathDisabled(uuid);
        if (clearedDl || clearedHero) {
            LOGGER.info("[TM] envoy: player {} used a character reset scroll — cleared paths (demonLord={}, hero={})",
                    player.getName().getString(), clearedDl, clearedHero);
        }
    }

    /**
     * Snapshot the {@link EnvoyCondition} set that is satisfied right now
     * for {@code member} at this colony — what the envoy "is here because
     * of." Re-runs each per-race eligibility branch in isolation; multiple
     * branches commonly satisfy at once (e.g. a dwarf envoy for a colony
     * with both ≥30 citizens AND a true-demon-lord owner).
     *
     * <p>The mask is captured ONCE at spawn and persisted on the envoy's
     * {@link EnvoyTag} — dialogue copy reflects the state at spawn-time
     * even if the player loses a condition before responding (the envoy
     * doesn't "withdraw" because the demon-lord status was reset between
     * spawn and click).
     *
     * <p>Empty result = legacy / unknown — the dialogue composer falls
     * back to base-only.
     */
    static java.util.EnumSet<EnvoyCondition> captureMetConditions(ServerLevel level,
                                                                  IColony colony,
                                                                  ColonyMember member,
                                                                  ColonyRaceConfigSavedData config) {
        java.util.EnumSet<EnvoyCondition> out = java.util.EnumSet.noneOf(EnvoyCondition.class);
        int colonyId = colony.getID();
        UUID owner = colony.getPermissions().getOwner();
        long now = level.getGameTime();
        switch (member) {
            case COLONIST -> {
                long created = config.getColonyCreationTick(colonyId, now);
                long killReset = config.getColonistKillResetTick(colonyId, Long.MIN_VALUE);
                long anchor = Math.max(created, killReset);
                if ((now - anchor) >= COLONIST_UNLOCK_AGE_TICKS) out.add(EnvoyCondition.TIMER);
            }
            case GOBLIN -> {
                int count = countNamedGoblinsInColony(level, colonyId);
                int baseline = config.getGoblinNamedBaseline(colonyId);
                if ((count - baseline) >= GOBLIN_UNLOCK_NAMED_COUNT) out.add(EnvoyCondition.COUNT);
            }
            case ORC -> {
                if (owner != null && config.hasDefeatedOrcDisaster(owner))
                    out.add(EnvoyCondition.ORC_DISASTER_DEFEATED);
                int current = colony.getCitizenManager().getCurrentCitizenCount();
                int snapshot = config.getOrcCitizenSnapshot(colonyId);
                if (current >= ORC_UNLOCK_CITIZEN_COUNT
                        && (snapshot < 0 || current > snapshot))
                    out.add(EnvoyCondition.COUNT);
            }
            case LIZARDMAN -> {
                if (owner != null && config.hasDefeatedIfrit(owner))
                    out.add(EnvoyCondition.IFRIT_DEFEATED);
                int current = colony.getCitizenManager().getCurrentCitizenCount();
                int snapshot = config.getLizardmanCitizenSnapshot(colonyId);
                if (current >= LIZARDMAN_UNLOCK_CITIZEN_COUNT
                        && (snapshot < 0 || current > snapshot))
                    out.add(EnvoyCondition.COUNT);
            }
            case DWARF -> {
                int current = colony.getCitizenManager().getCurrentCitizenCount();
                if (current >= DWARF_UNLOCK_CITIZEN_COUNT_PLACEHOLDER && colonyHasMiner(colony)) {
                    int snapshot = config.getDwarfCitizenSnapshot(colonyId);
                    if (snapshot < 0 || current > snapshot) out.add(EnvoyCondition.COUNT);
                }
                long anchor = config.getLastOwnerDeathTick(colonyId,
                        config.getColonyCreationTick(colonyId, now));
                if ((now - anchor) / TICKS_PER_DAY >= DWARF_UNLOCK_DAYS_NO_DEATH)
                    out.add(EnvoyCondition.TIMER);
                if (owner != null) {
                    if (config.hasEnteredDwarvenVillage(owner))
                        out.add(EnvoyCondition.DWARVEN_VILLAGE);
                    ServerPlayer op = level.getServer().getPlayerList().getPlayer(owner);
                    if (op != null) {
                        IExistence ex = readExistenceSafe(op);
                        if (ex != null) {
                            if (ex.isTrueDemonLord() && !config.isDemonLordPathDisabled(owner))
                                out.add(EnvoyCondition.TRUE_DEMON_LORD);
                            if (ex.isTrueHero() && !config.isHeroPathDisabled(owner))
                                out.add(EnvoyCondition.TRUE_HERO);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Defensive wrapper around the existence storage read — Tensura's
     *  attachment isn't guaranteed to be present on every entity. */
    private static IExistence readExistenceSafe(net.minecraft.world.entity.LivingEntity entity) {
        try {
            return io.github.manasmods.tensura.storage.TensuraStorages.getExistenceFrom(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Compute the full set of {@link ColonyMember}s currently eligible for
     *  an envoy at this colony, accounting for per-player gamerule caps. */
    static EnumSet<ColonyMember> computeEligibleEnvoys(ServerLevel level, IColony colony,
                                                       ColonyRaceConfigSavedData config) {
        EnumSet<ColonyMember> eligible = EnumSet.noneOf(ColonyMember.class);
        UUID ownerUuid = colony.getPermissions().getOwner();
        int maxNonColonist = level.getGameRules().getInt(RULE_MAX_NON_COLONIST_ENVOYS);
        EnumSet<ColonyMember> playerHistory = ownerUuid == null
                ? EnumSet.noneOf(ColonyMember.class)
                : config.playerNonColonistEnvoysSeen(ownerUuid);
        boolean nonColonistCapReached = playerHistory.size() >= maxNonColonist;

        for (ColonyMember m : ColonyMember.values()) {
            if (!isEnvoyEligible(level, colony, m, config)) continue;
            // Gamerule cap: non-colonist races count toward the cap. If the
            // player is at the cap, only races they've ALREADY seen can still
            // appear (in case the previous one was declined and is being
            // re-proposed) — that keeps the locked set fixed at the first
            // {cap}-many distinct races the player encounters.
            if (m != ColonyMember.COLONIST && nonColonistCapReached && !playerHistory.contains(m)) {
                continue;
            }
            eligible.add(m);
        }
        return eligible;
    }

    /** Try to spawn an envoy at this colony if the scheduling gates are met.
     *  Called once per in-game day per colony. Side-effects: spawns the envoy
     *  entity, sets {@code activeEnvoyUuid}, records the player's history.
     *  No-op if any gate fails. */
    private static void tryScheduleEnvoy(ServerLevel level, IColony colony,
                                         ColonyRaceConfigSavedData config) {
        int colonyId = colony.getID();

        // Gate 1: at most one active envoy per colony.
        UUID active = config.getActiveEnvoyUuid(colonyId);
        if (active != null) {
            net.minecraft.world.entity.Entity stillThere = level.getEntity(active);
            if (stillThere != null && !stillThere.isRemoved()
                    && stillThere.hasData(Attachments.ENVOY_TAG.get())) {
                return;   // still alive — keep waiting
            }
            // Stale reference: entity gone but never resolved. Treat as a
            // silent resolve so the gap timer can start.
            config.setActiveEnvoyUuid(colonyId, null);
            config.setLastEnvoyResolveTick(colonyId, level.getGameTime());
            LOGGER.info("[TM] envoy scheduler: stale active envoy at colony {} ('{}') — cleared",
                    colonyId, colony.getName());
            return;
        }

        // Gate 2: 3-day cooldown since the last resolve.
        long lastResolve = config.getLastEnvoyResolveTick(colonyId, -1L);
        if (lastResolve >= 0 && (level.getGameTime() - lastResolve) < ENVOY_RESOLVE_GAP_TICKS) {
            return;
        }

        // Gate 3: there must be SOMETHING eligible.
        EnumSet<ColonyMember> eligible = computeEligibleEnvoys(level, colony, config);
        if (eligible.isEmpty()) return;

        // Pick one at random.
        java.util.List<ColonyMember> choices = new java.util.ArrayList<>(eligible);
        ColonyMember pick = choices.get(level.getRandom().nextInt(choices.size()));

        net.minecraft.world.entity.Entity envoy = spawnEnvoy(level, colony, pick);
        if (envoy == null) {
            LOGGER.warn("[TM] envoy scheduler: spawnEnvoy returned null for colony {} member {}",
                    colonyId, pick);
            return;
        }
        config.setActiveEnvoyUuid(colonyId, envoy.getUUID());
        UUID ownerUuid = colony.getPermissions().getOwner();
        if (ownerUuid != null) {
            config.recordPlayerEnvoySeen(ownerUuid, pick);
        }
        LOGGER.info("[TM] envoy scheduler: colony {} ('{}') receives a {} envoy",
                colonyId, colony.getName(), pick);
    }

    /**
     * Kill-gate dispatch. Resolves the killed entity's {@link ColonyMember}
     * (or returns silently if unrecognised / excluded), identifies the
     * killer's owned colonies, and applies the per-shape reset for each.
     *
     * Killer identification: walks the damage source to its root entity to
     * cover indirect kills (arrows, magic projectiles, tamed pets). Only
     * Player kills count — environmental deaths and mob-on-mob fights
     * don't trigger the gate, since the brief specifies "the killer's own
     * colonies."
     */
    private static void processEnvoyKillGate(ServerLevel level, LivingDeathEvent event) {
        // Orc lord / orc disaster are separately registered EntityTypes;
        // Races.of returns null for them, so they naturally don't count
        // here. Defensive: also check the explicit BLOCKED set in case
        // a future Race adds them as renderable members.
        net.minecraft.world.entity.EntityType<?> killedType = event.getEntity().getType();
        if (Races.isBlocked(killedType)) return;
        Race killedRace = Races.of(killedType);
        if (killedRace == null) return;
        ColonyMember killedMember = ColonyMember.fromRace(killedRace);

        // Resolve the killer to a player. Walks through projectile sources
        // so a player who shot a goblin with an arrow still gets credit.
        net.minecraft.world.entity.Entity rawKiller = event.getSource().getEntity();
        if (rawKiller == null) return;
        if (!(rawKiller instanceof Player killer)) {
            // Indirect projectile case — DamageSource.getEntity returns the
            // arrow, while .getDirectEntity is the same. Vanilla wraps the
            // shooter into getEntity for owned projectiles, so a non-player
            // here means "not a player kill."
            return;
        }

        UUID killerUuid = killer.getUUID();
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
        long now = level.getGameTime();

        // For every colony the killer owns, apply the reset.
        for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
            UUID ownerUuid = colony.getPermissions().getOwner();
            if (ownerUuid == null || !ownerUuid.equals(killerUuid)) continue;
            int colonyId = colony.getID();

            // Accepted-immune: diplomacy is permanent.
            if (config.acceptedEnvoys(colonyId).contains(killedMember)) {
                LOGGER.info("[TM] kill-gate: {} kill ignored for colony {} ('{}') — race already accepted (immune)",
                        killedMember, colonyId, colony.getName());
                continue;
            }

            // Per-shape condition reset.
            switch (killedMember) {
                case COLONIST -> {
                    // Reset the 3-day timer anchor to NOW. The eligibility
                    // check takes max(creation, killReset) so this defers
                    // the next colonist envoy by 3 in-game days.
                    config.recordColonistKillReset(colonyId, now);
                    LOGGER.info("[TM] kill-gate: COLONIST kill — colony {} ('{}') 3-day timer reset to {}",
                            colonyId, colony.getName(), now);
                }
                case GOBLIN -> {
                    // Snapshot the current count; eligibility requires
                    // (currentCount - baseline) >= 3 — so 3 NEW named
                    // goblins after this kill.
                    int baseline = countNamedGoblinsInColony(level, colonyId);
                    config.recordGoblinNamedBaseline(colonyId, baseline);
                    LOGGER.info("[TM] kill-gate: GOBLIN kill — colony {} ('{}') named-goblin baseline = {} (need {} more)",
                            colonyId, colony.getName(), baseline, GOBLIN_UNLOCK_NAMED_COUNT);
                }
                case ORC -> {
                    // Snapshot the current citizen count; eligibility
                    // requires currentCount > snapshot AND >= 25.
                    int snapshot = colony.getCitizenManager().getCurrentCitizenCount();
                    config.recordOrcCitizenSnapshot(colonyId, snapshot);
                    LOGGER.info("[TM] kill-gate: ORC kill — colony {} ('{}') citizen snapshot = {} (need >{} citizens)",
                            colonyId, colony.getName(), snapshot, snapshot);
                }
                case LIZARDMAN -> {
                    // Existing: current-value snapshot for the 15-citizens
                    // alternative.
                    int snapshot = colony.getCitizenManager().getCurrentCitizenCount();
                    config.recordLizardmanCitizenSnapshot(colonyId, snapshot);
                    LOGGER.info("[TM] kill-gate: LIZARDMAN kill — colony {} ('{}') citizen snapshot = {} (need >{} citizens)",
                            colonyId, colony.getName(), snapshot, snapshot);
                    // New: also clear the ifrit-defeated flag for the
                    // killer (Ifrit is repeatable; they must defeat
                    // another). Per-player, applies to ALL their colonies
                    // simultaneously — the loop body just happens to run
                    // once per owned colony, but the clear is idempotent.
                    config.clearIfritDefeated(killerUuid);
                }
                case DWARF -> {
                    // Existing placeholder branch: current-value citizen
                    // snapshot keeps the ≥30-citizens placeholder gated.
                    int snapshot = colony.getCitizenManager().getCurrentCitizenCount();
                    config.recordDwarfCitizenSnapshot(colonyId, snapshot);
                    LOGGER.info("[TM] kill-gate: DWARF kill — colony {} ('{}') citizen snapshot = {} (need >{} citizens)",
                            colonyId, colony.getName(), snapshot, snapshot);
                    // 20-days-no-death penalty: −10 in-game days of
                    // progress. Move the lastDeath anchor forward by 10
                    // days, capped at NOW so the penalty is never
                    // negative (you can't end up with future progress).
                    long prevAnchor = config.getLastOwnerDeathTick(colonyId,
                            config.getColonyCreationTick(colonyId, now));
                    long penalized = Math.min(now, prevAnchor + DWARF_KILL_GATE_DAY_PENALTY * TICKS_PER_DAY);
                    config.setLastOwnerDeathTick(colonyId, penalized);
                    LOGGER.info("[TM] kill-gate: DWARF kill — colony {} ('{}') 20-day timer anchor advanced by {} days ({} → {})",
                            colonyId, colony.getName(), DWARF_KILL_GATE_DAY_PENALTY, prevAnchor, penalized);
                    // Dwarven village: clear per-player flag — the killer
                    // must find a fresh village.
                    config.clearDwarvenVillageEntered(killerUuid);
                    // Demon-lord / hero paths: disable globally per-player
                    // ONLY if the killer is currently a true demon lord /
                    // hero. Non-status players don't poison the path.
                    // Cleared by character reset (or fallback) — see
                    // {@link #onLivingUseItemFinish} and the scheduler's
                    // disable-clear pass.
                    IExistence killerEx = readExistenceSafe(killer);
                    if (killerEx != null) {
                        if (killerEx.isTrueDemonLord()
                                && config.setDemonLordPathDisabled(killerUuid)) {
                            LOGGER.info("[TM] kill-gate: DWARF kill — player {} demon-lord path DISABLED until character reset",
                                    killer.getName().getString());
                        }
                        if (killerEx.isTrueHero()
                                && config.setHeroPathDisabled(killerUuid)) {
                            LOGGER.info("[TM] kill-gate: DWARF kill — player {} hero path DISABLED until character reset",
                                    killer.getName().getString());
                        }
                    }
                }
            }

            // If the active envoy at this colony is of the killed race,
            // drive it off (despawn) and clear the active reference so the
            // scheduler can propose a fresh envoy once the condition is
            // re-met. The cooldown stays as-is — the brief says "the next
            // envoy must re-earn the condition," not "skip the cooldown."
            UUID active = config.getActiveEnvoyUuid(colonyId);
            if (active != null) {
                net.minecraft.world.entity.Entity envoy = level.getEntity(active);
                if (envoy != null && !envoy.isRemoved()
                        && envoy.hasData(Attachments.ENVOY_TAG.get())) {
                    EnvoyTag tag = envoy.getData(Attachments.ENVOY_TAG.get());
                    if (tag != null && tag.member() == killedMember
                            && tag.state() == EnvoyTag.State.ALIVE) {
                        // Visual: same poof effect as accept/decline.
                        despawnEnvoyWithEffect(level, envoy);
                        // Colonist envoys need the VisitorData unwound.
                        if (envoy instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen ce
                                && ce.getCitizenData() != null) {
                            colony.getVisitorManager().removeCivilian(ce.getCitizenData());
                        }
                        envoy.discard();
                        config.setLastEnvoyResolveTick(colonyId, now);
                        config.setActiveEnvoyUuid(colonyId, null);
                        LOGGER.info("[TM] kill-gate: {} kill — active {} envoy at colony {} driven off",
                                killedMember, killedMember, colonyId);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Stage 3b — kill-gate
    // ------------------------------------------------------------------
    //
    // When a player kills a Tensura race, every colony THAT PLAYER OWNS
    // has its unlock condition for that race reset — UNLESS the race is
    // already in the colony's accepted set (diplomatic immunity). Orc
    // lord and orc disaster are explicitly excluded — they're a separate
    // EntityType from base orc and shouldn't gate the orc envoy.
    //
    // If an active unaccepted envoy of that race is alive when its kin
    // is killed, the envoy despawns too (driven off by the murder of
    // their kin) — the next envoy must re-earn the condition.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Subordinate trade — dawn restock
    // ------------------------------------------------------------------

    /** Last in-game day number we processed a dawn restock for, per
     *  dimension. {@code -1} means "no restock yet this session" — the
     *  first tick after server start initialises it without firing a
     *  restock so freshly-loaded subordinates don't all refresh on login.
     *  Keyed by dimension so two dimensions on different day timers
     *  (e.g. the Nether's frozen tick) each get their own threshold. */
    private static final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Long>
            lastRestockDayPerDim = new java.util.HashMap<>();

    /**
     * Once per in-game day, walk every tracked race identity and call
     * {@code restock()} on the live entity if it's a {@link io.github.manasmods.tensura.entity.template.TensuraMerchantEntity}.
     * The check is keyed off {@code level.getDayTime() / 24000} so the
     * restock fires exactly once per day-rollover regardless of how many
     * ticks pass between calls. Day boundary is sunrise (gameTime == 0
     * within a day cycle).
     *
     * <p>Reading day from each dimension separately keeps colonies in
     * non-overworld dimensions on their own clock, though in practice
     * every colony lives in the overworld and the loop is a no-op for
     * the others.
     */
    private static void tickDawnRestock(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            long currentDay = level.getDayTime() / 24000L;
            Long lastDay = lastRestockDayPerDim.get(level.dimension());
            if (lastDay == null) {
                // First sighting this dim — anchor without firing. Avoids a
                // login-time double restock and matches Tensura's own
                // restock cadence semantics (it tracks last-restock day on
                // each entity too).
                lastRestockDayPerDim.put(level.dimension(), currentDay);
                continue;
            }
            if (currentDay == lastDay) continue;
            lastRestockDayPerDim.put(level.dimension(), currentDay);

            RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
            int restockedLive = 0;
            int restockedSnapshot = 0;

            // Track which identityIds have an active in-progress trade
            // session. We skip those — overwriting their snapshot now
            // would race with the close-event persist path
            // ({@link #onPlayerContainerClose}), which writes the
            // mid-trade merchant state back on menu close. The next
            // day rollover will catch them.
            java.util.Set<java.util.UUID> activeTradeIdentities =
                    new java.util.HashSet<>();
            for (TransientMerchantSession s : TRANSIENT_MERCHANTS.values()) {
                activeTradeIdentities.add(s.identityId());
            }

            for (RaceIdentitySavedData.RaceIdentity identity : saved.all()) {
                // Pass A — SUBORDINATE mode: wild form is in the world.
                // Restock the live entity directly.
                if (identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE
                        && identity.mobEntityUUID != null) {
                    net.minecraft.world.entity.Entity e = level.getEntity(identity.mobEntityUUID);
                    if (e instanceof io.github.manasmods.tensura.entity.template.TensuraMerchantEntity merchant
                            && merchant.isAlive()) {
                        try {
                            merchant.restock();
                            restockedLive++;
                        } catch (Throwable t) {
                            LOGGER.warn("[TM] dawn restock: restock() threw for subordinate {}",
                                    identity.mobEntityUUID, t);
                        }
                    }
                    continue;
                }

                // Pass B — CITIZEN mode: merchant lives in the snapshot.
                // Reconstruct, restock, save back. Without this, the
                // citizen-form trade button reconstructs from a snapshot
                // whose offers' use counts never reset, so trades drain
                // and never refill — "trades reset every morning"
                // silently broken.
                if (identity.mode != RaceIdentitySavedData.Mode.IN_COLONY) continue;
                if (identity.entitySnapshot == null) continue;
                if (activeTradeIdentities.contains(identity.identityId)) {
                    // Trade in progress — the close hook will persist
                    // the merchant's CURRENT state, which would clobber
                    // a freshly restocked snapshot we wrote here. Skip
                    // and let tomorrow's pass handle it.
                    continue;
                }

                try {
                    java.util.Optional<net.minecraft.world.entity.Entity> created =
                            net.minecraft.world.entity.EntityType.create(identity.entitySnapshot, level);
                    if (created.isEmpty()
                            || !(created.get() instanceof io.github.manasmods.tensura.entity.template.TensuraMerchantEntity merchant)) {
                        continue; // non-merchant race (orc) or decode failure
                    }
                    merchant.restock();

                    net.minecraft.nbt.CompoundTag fresh = new net.minecraft.nbt.CompoundTag();
                    if (merchant.save(fresh)) {
                        saved.updateEntitySnapshot(identity, fresh);
                        restockedSnapshot++;
                    } else {
                        LOGGER.warn("[TM] dawn restock: merchant.save returned false for citizen identity {}",
                                identity.identityId);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[TM] dawn restock: snapshot restock threw for citizen identity {}",
                            identity.identityId, t);
                }
            }

            if (restockedLive > 0 || restockedSnapshot > 0) {
                LOGGER.info("[TM] dawn restock in {} — day {}: {} live subordinate(s), {} citizen snapshot(s)",
                        level.dimension().location(), currentDay,
                        restockedLive, restockedSnapshot);
            }
        }
    }

    /** Per-tick envoy scheduler. Called from the existing
     *  {@code onServerTickPost} handler so we share the existing periodic
     *  loop instead of adding a second one. */
    private static void runEnvoyScheduler(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
            // Iterate colonies in this level. We use the colony manager so
            // colonies we've never written state for still get scheduled.
            for (IColony colony : IColonyManager.getInstance().getColonies(level)) {
                // Ensure we have a creation tick. If absent, anchor it NOW —
                // a colony that predates this code starts its 3-day timer
                // from first observation.
                config.recordColonyCreation(colony.getID(), level.getGameTime());
                try {
                    tryScheduleEnvoy(level, colony, config);
                } catch (Throwable t) {
                    // Catch silent exceptions so one bad colony doesn't kill
                    // the whole scheduler tick. Log loud so we know.
                    LOGGER.error("[TM] envoy scheduler: tryScheduleEnvoy threw for colony {} ('{}')",
                            colony.getID(), colony.getName(), t);
                }
            }
            // Deferred-content per-player passes: structure containment
            // (dwarven village discovery) + disable-flag fallback (catches
            // any path that strips demon-lord/hero status outside the
            // reset-scroll event hook).
            try {
                runPerPlayerEnvoyPasses(level, config);
            } catch (Throwable t) {
                LOGGER.error("[TM] envoy scheduler: per-player pass threw on level {}",
                        level.dimension().location(), t);
            }
        }
    }

    /** Per-player scheduler passes, run once per scheduler tick per level.
     *
     *  <p>Pass A — Dwarven village discovery: poll each online player's
     *  containing structures via the {@code StructureManager}. If the
     *  player is inside a {@code tensura:dwarf_village} bounding box and
     *  the structure start is valid, mark the player as "entered." The
     *  flag survives until cleared by a dwarf kill.
     *
     *  <p>Pass B — Demon-lord / hero disable-flag fallback: if the player
     *  is flagged disabled but their current IExistence status is false
     *  (status got stripped — character reset, admin command, Tensura
     *  reincarnation flow, etc.), clear the disable. Matches the
     *  reset-scroll item event but covers paths the event hook misses. */
    private static void runPerPlayerEnvoyPasses(ServerLevel level, ColonyRaceConfigSavedData config) {
        net.minecraft.world.level.levelgen.structure.Structure dwarfVillage =
                level.registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                        .get(DWARF_VILLAGE_STRUCTURE_KEY);
        // Null = the registry doesn't contain tensura:dwarf_village (e.g.
        // Tensura disabled, or datapack override). Skip the village pass
        // silently in that case; other passes still run.
        for (ServerPlayer player : level.players()) {
            UUID uuid = player.getUUID();

            // Pass A — village containment.
            if (dwarfVillage != null && !config.hasEnteredDwarvenVillage(uuid)) {
                net.minecraft.world.level.levelgen.structure.StructureStart start =
                        level.structureManager().getStructureAt(player.blockPosition(), dwarfVillage);
                if (start.isValid()) {
                    if (config.markDwarvenVillageEntered(uuid)) {
                        LOGGER.info("[TM] envoy: player {} entered a dwarven village — dwarf envoy unlock",
                                player.getName().getString());
                    }
                }
            }

            // Pass B — disable-flag clear when status is false. Skip the
            // IExistence read if both flags are already clear (the common
            // case for most players, every tick).
            boolean dlDisabled = config.isDemonLordPathDisabled(uuid);
            boolean heroDisabled = config.isHeroPathDisabled(uuid);
            if (!dlDisabled && !heroDisabled) continue;
            IExistence ex = readExistenceSafe(player);
            if (ex == null) continue;
            if (dlDisabled && !ex.isTrueDemonLord()) {
                config.clearDemonLordPathDisabled(uuid);
                LOGGER.info("[TM] envoy: player {} no longer true demon lord — disable flag cleared",
                        player.getName().getString());
            }
            if (heroDisabled && !ex.isTrueHero()) {
                config.clearHeroPathDisabled(uuid);
                LOGGER.info("[TM] envoy: player {} no longer true hero — disable flag cleared",
                        player.getName().getString());
            }
        }
    }

    // ------------------------------------------------------------------
    // Envoy system — Stage 1: spawn + marker + naming suppression
    // ------------------------------------------------------------------

    /**
     * Spawn an envoy for {@code colony}, representing {@code member}.
     * Entity type per member: GOBLIN → tensura:goblin, ORC → tensura:orc,
     * COLONIST → vanilla villager. The envoy is marked with
     * {@link Attachments#ENVOY_TAG} carrying {@code (colonyId, member, ALIVE)}.
     *
     * Spawn position: {@link EntityUtils#getSpawnPoint} (MC's 5-block-radius
     * 2-block-clearance helper — the project's own findSafeColonySpawn was
     * reverted in an earlier change, so we use MC's). Falls back to the raw
     * town hall position if {@code getSpawnPoint} returns null.
     *
     * @return the spawned entity, or null on failure (no town hall, unknown
     *     entity type, addFreshEntity returned false). Caller-facing
     *     errors (no colony, missing town hall) are the caller's job.
     */
    static net.minecraft.world.entity.Entity spawnEnvoy(ServerLevel level,
                                                        IColony colony,
                                                        ColonyMember member) {
        if (!colony.getServerBuildingManager().hasTownHall()) {
            LOGGER.warn("[TM] envoy spawn: colony '{}' has no town hall — aborting",
                    colony.getName());
            return null;
        }
        BlockPos th = colony.getServerBuildingManager().getTownHall().getPosition();
        BlockPos spawnAt = EntityUtils.getSpawnPoint(level, th);
        if (spawnAt == null) {
            LOGGER.warn("[TM] envoy spawn: getSpawnPoint returned null near {} — falling back to town hall block",
                    th);
            spawnAt = th;
        }

        // For COLONIST, use MineColonies' VisitorCitizen so the envoy looks
        // like a colonist rather than a vanilla villager. We MUST also
        // register the visitor in the colony's VisitorManager — without a
        // real IVisitorData, VisitorColonyHandler.registerWithColony sees
        // citizenId == 0 on the first aiStep and discards the entity.
        // Bookkeeping is cleaned up on accept/decline in handleEnvoyResponse.
        com.minecolonies.api.colony.IVisitorData colonistVisitorData = null;
        EntityType<?> type;
        if (member == ColonyMember.COLONIST) {
            type = com.minecolonies.api.entity.ModEntities.VISITOR;
            if (type == null) {
                LOGGER.error("[TM] envoy spawn: ModEntities.VISITOR not initialised — aborting");
                return null;
            }
            // IEntityManager.createAndRegisterCivilianData() declares ICivilianData
            // in the interface; VisitorManager actually returns IVisitorData. The
            // cast is safe — concrete VisitorManager is the only implementor.
            colonistVisitorData = (com.minecolonies.api.colony.IVisitorData)
                    colony.getVisitorManager().createAndRegisterCivilianData();
            // initForNewCivilian assigned a random colonist name (e.g. "Bob");
            // override to "Colonist Envoy" so the floating name shows only the
            // envoy label — MC renders the citizen data name as part of the
            // entity tag, separate from setCustomName.
            colonistVisitorData.setName("Colonist Envoy");
        } else {
            ResourceLocation typeId = switch (member) {
                case GOBLIN -> Races.idFor(Race.GOBLIN);
                case ORC -> Races.idFor(Race.ORC);
                case LIZARDMAN -> Races.idFor(Race.LIZARDMAN);
                case DWARF -> Races.idFor(Race.DWARF);
                default -> throw new IllegalStateException("unhandled envoy member: " + member);
            };
            type = BuiltInRegistries.ENTITY_TYPE.get(typeId);
            if (type == null) {
                LOGGER.error("[TM] envoy spawn: EntityType '{}' not registered — aborting", typeId);
                return null;
            }
        }
        Entity created = type.create(level);
        if (!(created instanceof net.minecraft.world.entity.Mob mob)) {
            LOGGER.error("[TM] envoy spawn: factory for '{}' returned {} — not a Mob",
                    type, created != null ? created.getClass().getName() : "null");
            return null;
        }

        // For the VisitorCitizen path, match the entity UUID to the data
        // BEFORE addFreshEntity so the entity tracker indexes consistently.
        // Without this, registerWithColony writes a separate UUID into the
        // visitor map than the entity actually has, causing tavern-UI
        // mismatches and broken cleanup.
        if (colonistVisitorData != null) {
            mob.setUUID(colonistVisitorData.getUUID());
        }

        mob.moveTo(spawnAt.getX() + 0.5, spawnAt.getY(), spawnAt.getZ() + 0.5,
                level.getRandom().nextFloat() * 360f, 0f);

        // finalizeSpawn → variant randomisation (Tensura goblin/orc fields,
        // villager profession). SPAWN_EGG also marks non-despawnable so the
        // envoy waits for the player even if they wander off.
        mob.finalizeSpawn(
                level,
                level.getCurrentDifficultyAt(spawnAt),
                net.minecraft.world.entity.MobSpawnType.SPAWN_EGG,
                null
        );

        // Mark BEFORE addFreshEntity so even the same-tick chunk-tracking
        // pass sees a fully-marked envoy. The attachment persists via NBT
        // so reload restores it.
        // Stage J2 — snapshot WHICH conditions are satisfied right now so
        // the dialogue copy can call them out by name. The mask is stable
        // for the life of this envoy; if the player loses a condition
        // between spawn and accept, the dialogue still reflects what was
        // true at spawn (envoy already committed to coming).
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
        java.util.EnumSet<EnvoyCondition> captured = captureMetConditions(level, colony, member, config);
        byte conditionMask = EnvoyCondition.toMask(captured);
        mob.setData(Attachments.ENVOY_TAG.get(),
                new EnvoyTag(colony.getID(), member, EnvoyTag.State.ALIVE, conditionMask));
        // PersistenceRequired = a second non-despawn lock alongside SPAWN_EGG.
        // SPAWN_EGG alone covers most cases; PersistenceRequired covers the
        // edge case where a mob's finalizeSpawn overrides the flag.
        mob.setPersistenceRequired();

        // Colored nameplate signals "interact with me." Vanilla custom-name
        // path — distinct from Tensura's IExistence.name() naming system,
        // so the envoy is NOT a named subordinate, just a labelled wild mob.
        mob.setCustomName(EnvoyDialogue.nameplate(member));
        mob.setCustomNameVisible(true);

        // Roam-within-radius: PathfinderMob.restrictTo(home, radius) gates
        // both vanilla LandRandomPos / DefaultRandomPos (used by Villager
        // wander goals AND SmartBrainLib's SetRandomWalkTarget for the
        // Goblin/Orc brain) via isWithinRestriction. The envoy still wanders
        // and rotates normally, but never paths beyond ENVOY_ROAM_RADIUS
        // blocks of the town hall.
        BlockPos home = colony.getServerBuildingManager().getTownHall().getPosition();
        if (mob instanceof net.minecraft.world.entity.PathfinderMob pathfinder) {
            pathfinder.restrictTo(home, ENVOY_ROAM_RADIUS);
        }

        if (!level.addFreshEntity(mob)) {
            LOGGER.warn("[TM] envoy spawn: addFreshEntity returned false for {} at {}",
                    type, spawnAt);
            // Clean up the dangling VisitorData if we made one — otherwise it
            // sticks in the visitor map referencing no entity.
            if (colonistVisitorData != null) {
                colony.getVisitorManager().removeCivilian(colonistVisitorData);
            }
            return null;
        }
        // VisitorCitizen post-spawn wiring (after addFreshEntity, matching
        // VisitorManager.spawnOrCreateCivilian order). Without this the
        // first aiStep calls registerWithColony with citizenId=0 and the
        // entity is discarded immediately — which is the bug we're fixing.
        if (colonistVisitorData != null
                && mob instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizenEnvoy) {
            citizenEnvoy.setCitizenId(colonistVisitorData.getId());
            citizenEnvoy.getCitizenColonyHandler().setColonyId(colony.getID());
            if (citizenEnvoy.isAddedToLevel()) {
                citizenEnvoy.getCitizenColonyHandler().registerWithColony(
                        colony.getID(), colonistVisitorData.getId());
            }
        }
        LOGGER.info("[TM] envoy spawn: {} envoy spawned for colony '{}' at {} (uuid={})",
                member, colony.getName(), spawnAt, mob.getUUID());
        return mob;
    }

    /**
     * Handle the C2S {@link Networking.EnvoyResponsePayload}. Re-validates
     * the envoy (entity exists, still tagged, ALIVE, player owns the colony)
     * before applying:
     *
     * - ACCEPT → add {@code member} to the colony's composition set,
     *   mark the envoy's tag {@link EnvoyTag.State#ACCEPTED}, mark the
     *   member as never-again-envoy-eligible for this colony, discard
     *   the entity, send a flavoured confirmation.
     * - DECLINE → tag {@link EnvoyTag.State#DECLINED}, discard, send a
     *   flavoured dismissal. The decline does NOT set the
     *   never-again lock — Stage 3's scheduler decides re-eligibility.
     *
     * Any re-validation failure (entity gone, ownership lost, etc.)
     * sends a quiet advisory and no-ops state. No half-applied
     * mutations: composition is mutated only after every check passes.
     */
    /**
     * Open the merchant trading screen for a named subordinate.
     *
     * <p>Named Tensura race-mobs (goblin, lizardman, dwarf) all extend
     * {@code TensuraMerchantEntity} which already round-trips its
     * profession, merchant level, gossips, and persisted offers through
     * the entity's {@code addAdditionalSaveData} / {@code readAdditionalSaveData}.
     * Naming the mob (which marks it tame) only changes Tensura's interaction
     * branch ({@code handleCommanding} routes tame entities to the inventory
     * screen instead of trading) — it does NOT clear the merchant state.
     * So opening the trading screen from here gives the player the same
     * trades they would have had pre-naming, and trading XP / level-ups
     * continue to fire because they're driven by the standard
     * {@code Merchant.notifyTrade} chain that the menu invokes after each
     * swap.
     *
     * <p>Validation: entity exists, is a {@code TensuraMerchantEntity},
     * the player owns it (per the identity store), and it isn't already
     * trading with someone. Failures send a quiet advisory.
     */
    static void handleOpenSubordinateTrade(ServerPlayer player, int entityId) {
        ServerLevel level = player.serverLevel();
        net.minecraft.world.entity.Entity entity = level.getEntity(entityId);
        if (!(entity instanceof io.github.manasmods.tensura.entity.template.TensuraMerchantEntity merchant)) {
            sendAdvisoryNotice(player, "That subordinate cannot trade.");
            return;
        }
        // Ownership via the identity store — only the namer can open trading.
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity identity = saved.getByMobUUID(entity.getUUID());
        if (identity == null
                || identity.ownerPlayerUUID == null
                || !identity.ownerPlayerUUID.equals(player.getUUID())) {
            sendAdvisoryNotice(player, "That subordinate isn't yours.");
            return;
        }
        // Dead / baby checks only. The {@code wantsToTrade()} check
        // Tensura uses (false while sleeping or not-working — i.e. at
        // night) is intentionally skipped here so the player can browse
        // their subordinate's stock at any hour. Restocking still only
        // happens at dawn (see {@link #tickDawnRestock}); the menu just
        // becomes view-only when the merchant would normally refuse.
        if (merchant.isBaby() || !merchant.isAlive()) {
            sendAdvisoryNotice(player, "Your subordinate doesn't want to trade right now.");
            return;
        }
        if (merchant.getTradingPlayer() != null) {
            sendAdvisoryNotice(player, "Someone else is already trading with this subordinate.");
            return;
        }
        net.minecraft.world.item.trading.MerchantOffers offers = merchant.getOffers();
        if (offers.isEmpty()) {
            sendAdvisoryNotice(player, "Your subordinate has no trades available.");
            return;
        }
        // Bind player → entity for the trade session and open the standard
        // vanilla merchant screen. The default Merchant.openTradingScreen
        // implementation handles menu opening + sending offers + level + xp.
        merchant.setTradingPlayer(player);
        merchant.openTradingScreen(player, entity.getDisplayName(), merchant.getMerchantLevel());
        LOGGER.info("[TM] subordinate trade opened: player {} ↔ subordinate {} (profession={}, level={})",
                player.getName().getString(), entity.getUUID(),
                merchant.getProfession(), merchant.getMerchantLevel());
    }

    /**
     * Server-side handler for the Trade button on a citizen's MC info
     * window. The trade happens entirely in CITIZEN FORM — no need to
     * summon the subordinate back. We reconstruct a transient merchant
     * from the identity's entity-snapshot NBT, open trade against it,
     * and persist any merchant-state changes (uses count, demand, xp,
     * level) back to the snapshot when the player closes the trade
     * screen.
     *
     * <p><b>Why the transient merchant works.</b> {@link Merchant} is
     * an interface; {@link Merchant#openTradingScreen} just opens a
     * {@code MerchantMenu} with this merchant as the trading partner.
     * The menu only calls {@code Merchant} interface methods (getOffers,
     * notifyTrade, setTradingPlayer, etc.) and never reaches into the
     * world for the merchant — so the reconstructed entity doesn't
     * have to be in the world for the trade to function. We do NOT
     * call {@code addFreshEntity}, so the merchant is invisible to
     * the world; the trade screen is the only place it surfaces.
     *
     * <p>The transient merchant is held in {@link #TRANSIENT_MERCHANTS}
     * keyed by the trading player; the close-event hook
     * {@link #onPlayerContainerClose} saves the merchant back to the
     * identity's snapshot and drops the entry.
     */
    static void handleOpenCitizenTrade(ServerPlayer player, int citizenEntityId) {
        ServerLevel level = player.serverLevel();
        net.minecraft.world.entity.Entity ent = level.getEntity(citizenEntityId);
        if (!(ent instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen)) {
            sendAdvisoryNotice(player, "That isn't a citizen.");
            return;
        }
        RaceTag tag = citizen.getData(Attachments.RACE_TAG.get());
        if (tag == null) {
            sendAdvisoryNotice(player, "That citizen has no race identity.");
            return;
        }
        // Merchant-capable races: goblin, lizardman, dwarf (NOT orc).
        if (tag.race() == Race.ORC) {
            sendAdvisoryNotice(player, "Orcs do not trade.");
            return;
        }
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity identity =
                saved.getById(tag.identityId());
        if (identity == null) {
            sendAdvisoryNotice(player, "Citizen identity not found.");
            return;
        }
        if (identity.ownerPlayerUUID == null
                || !identity.ownerPlayerUUID.equals(player.getUUID())) {
            sendAdvisoryNotice(player, "That citizen isn't yours.");
            return;
        }
        if (identity.entitySnapshot == null) {
            sendAdvisoryNotice(player,
                    "That citizen has no merchant snapshot yet — send them to the colony once first.");
            return;
        }

        // Reject re-entry: one trade session per player at a time.
        // Otherwise reopening would build a second transient merchant
        // before the previous one had a chance to persist back.
        if (TRANSIENT_MERCHANTS.containsKey(player.getUUID())) {
            sendAdvisoryNotice(player, "You're already in a trade — close it before starting another.");
            return;
        }

        // Reconstruct the merchant from the identity's snapshot. The
        // snapshot was captured by {@code goblin.save(tag)} at the
        // last send, so it carries every Tensura storage including the
        // merchant's offers, level, profession, and xp.
        java.util.Optional<net.minecraft.world.entity.Entity> created =
                net.minecraft.world.entity.EntityType.create(identity.entitySnapshot, level);
        if (created.isEmpty()
                || !(created.get() instanceof io.github.manasmods.tensura.entity.template.TensuraMerchantEntity merchant)) {
            LOGGER.warn("[TM] citizen trade: snapshot reconstruction did not yield a TensuraMerchantEntity for identity {}",
                    identity.identityId);
            sendAdvisoryNotice(player,
                    "Couldn't open the merchant — their snapshot doesn't decode as a merchant.");
            return;
        }

        // Position the transient merchant at the citizen so any
        // level-relative call inside the merchant pipeline (Tensura's
        // restock onMerchantUpdate, vanilla notifyTrade sounds, etc.)
        // has a sensible nearby location. We don't addFreshEntity, so
        // the merchant is never visible to anyone — this is purely a
        // bookkeeping pose.
        merchant.moveTo(citizen.getX(), citizen.getY(), citizen.getZ(),
                citizen.getYRot(), 0f);

        if (merchant.isBaby() || !merchant.isAlive()) {
            sendAdvisoryNotice(player, "Your subordinate isn't trade-ready.");
            return;
        }
        net.minecraft.world.item.trading.MerchantOffers offers = merchant.getOffers();
        if (offers.isEmpty()) {
            sendAdvisoryNotice(player, "They have no trades available.");
            return;
        }

        merchant.setTradingPlayer(player);
        merchant.openTradingScreen(player, citizen.getDisplayName(), merchant.getMerchantLevel());

        // Register the session — the close hook will persist offers /
        // level / xp back to identity.entitySnapshot when the player
        // closes the trade screen.
        TRANSIENT_MERCHANTS.put(player.getUUID(),
                new TransientMerchantSession(merchant, identity.identityId));
        LOGGER.info("[TM] citizen trade opened (transient merchant) — player {} ↔ citizen {} identity {}",
                player.getName().getString(), citizen.getUUID(), identity.identityId);
    }

    /** Per-trading-player session — the reconstructed merchant entity
     *  and the identity it came from. The close-hook uses these to
     *  save merchant state back to the right snapshot. */
    private record TransientMerchantSession(
            io.github.manasmods.tensura.entity.template.TensuraMerchantEntity merchant,
            java.util.UUID identityId) {}

    /** Active trade sessions keyed by player UUID. Lives only in
     *  memory — restart loses any in-flight trades (the merchant data
     *  was last persisted at the previous SEND so nothing is corrupted,
     *  just whatever uses happened mid-trade are discarded). */
    private static final java.util.Map<java.util.UUID, TransientMerchantSession> TRANSIENT_MERCHANTS
            = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Persist the transient merchant's state back to the identity's
     * entity snapshot when the player closes the trade screen.
     *
     * <p>{@code PlayerContainerEvent.Close} fires whenever the player's
     * container menu closes — this includes the trade-screen close
     * (player presses ESC, walks away, logs out, etc.). We don't have
     * to distinguish "trade closed" specifically because we only enter
     * the session map when opening a trade.
     *
     * <p>Saves the transient merchant's full NBT (via
     * {@code merchant.save(tag)}) into the identity snapshot — this
     * preserves whatever Tensura's merchant updated during the session
     * (uses counts, demand factor, xp, profession) for the next trade
     * or for the next summon.
     */
    @SubscribeEvent
    public void onPlayerContainerClose(
            net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        TransientMerchantSession session = TRANSIENT_MERCHANTS.remove(sp.getUUID());
        if (session == null) return;

        try {
            // Drop the trading-player binding so the merchant releases
            // any in-flight state. setTradingPlayer(null) is idempotent.
            session.merchant().setTradingPlayer(null);

            // Persist back to snapshot.
            ServerLevel sl = sp.serverLevel();
            RaceIdentitySavedData saved = RaceIdentitySavedData.get(sl);
            RaceIdentitySavedData.RaceIdentity identity = saved.getById(session.identityId());
            if (identity == null) {
                LOGGER.warn("[TM] citizen trade close: identity {} gone — session dropped without persisting",
                        session.identityId());
                return;
            }
            net.minecraft.nbt.CompoundTag fresh = new net.minecraft.nbt.CompoundTag();
            if (session.merchant().save(fresh)) {
                saved.updateEntitySnapshot(identity, fresh);
                LOGGER.info("[TM] citizen trade closed — merchant snapshot updated for identity {} ({} top-level NBT keys)",
                        session.identityId(), fresh.getAllKeys().size());
            } else {
                LOGGER.warn("[TM] citizen trade close: merchant.save returned false — snapshot NOT updated for identity {}",
                        session.identityId());
            }
        } catch (Throwable t) {
            LOGGER.error("[TM] citizen trade close: persist back to snapshot threw for identity {}",
                    session.identityId(), t);
        }
    }

    static void handleEnvoyResponse(ServerPlayer player, int entityId, boolean accepted) {
        ServerLevel level = player.serverLevel();
        net.minecraft.world.entity.Entity entity = level.getEntity(entityId);
        if (entity == null || !entity.hasData(Attachments.ENVOY_TAG.get())) {
            sendAdvisoryNotice(player, "That envoy is no longer here.");
            return;
        }
        EnvoyTag tag = entity.getData(Attachments.ENVOY_TAG.get());
        if (tag == null || tag.state() != EnvoyTag.State.ALIVE) {
            sendAdvisoryNotice(player, "That envoy has already moved on.");
            return;
        }
        IColony colony = IColonyManager.getInstance().getColonyByWorld(tag.colonyId(), level);
        if (colony == null) {
            sendAdvisoryNotice(player, "That envoy's colony no longer exists.");
            entity.discard();
            return;
        }
        UUID ownerUuid = colony.getPermissions().getOwner();
        if (ownerUuid == null || !player.getUUID().equals(ownerUuid)) {
            sendAdvisoryNotice(player, "Only the colony owner may answer the envoy.");
            return;
        }

        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);

        // Poof effect at the envoy's position so the despawn is clearly
        // visible — particle burst + sound, then discard. Fires for both
        // accept and decline.
        despawnEnvoyWithEffect(level, entity);

        // Colonist envoys are registered IVisitorData — must be removed via
        // the visitor manager (which also discards the entity), not just by
        // a raw entity.discard(). Without this the visitor lingers in the
        // colony's visitor map referencing a removed entity.
        if (entity instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizenEnvoy
                && citizenEnvoy.getCitizenData() != null) {
            colony.getVisitorManager().removeCivilian(citizenEnvoy.getCitizenData());
        }

        // Stage 3a — record the resolve so the 3-day cooldown takes effect,
        // and clear the active-envoy reference so the scheduler is unblocked
        // for the next eligible spawn.
        config.setLastEnvoyResolveTick(colony.getID(), level.getGameTime());
        config.setActiveEnvoyUuid(colony.getID(), null);

        // Stage 3b — clear the ORC kill-snapshot once an ORC envoy resolves,
        // so future ORC eligibility goes back to the plain ≥25 threshold.
        // (No equivalent cleanup needed for the COLONIST timer or GOBLIN
        // baseline — they're naturally "consumed" by the resolve timeline.)
        if (tag.member() == ColonyMember.ORC) {
            config.clearOrcCitizenSnapshot(colony.getID());
        } else if (tag.member() == ColonyMember.LIZARDMAN) {
            config.clearLizardmanCitizenSnapshot(colony.getID());
        } else if (tag.member() == ColonyMember.DWARF) {
            config.clearDwarfCitizenSnapshot(colony.getID());
        }

        if (accepted) {
            config.addMember(colony.getID(), tag.member());
            config.markEnvoyAccepted(colony.getID(), tag.member());
            entity.setData(Attachments.ENVOY_TAG.get(), tag.withState(EnvoyTag.State.ACCEPTED));
            entity.discard();
            // Condition-aware accept text — a dwarven envoy that came
            // because the player is a true hero / demon lord parts with
            // a title-acknowledging line; everyone else uses the
            // standard race line.
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    EnvoyDialogue.acceptMessage(tag.member(), tag.conditions()))
                    .withStyle(ChatFormatting.GREEN));
            LOGGER.info("[TM] envoy ACCEPTED: colony {} ('{}') gained member {}",
                    colony.getID(), colony.getName(), tag.member());
        } else {
            entity.setData(Attachments.ENVOY_TAG.get(), tag.withState(EnvoyTag.State.DECLINED));
            entity.discard();
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    EnvoyDialogue.declineMessage(tag.member(), tag.conditions()))
                    .withStyle(ChatFormatting.GRAY));
            LOGGER.info("[TM] envoy DECLINED: colony {} ('{}') turned away {} envoy",
                    colony.getID(), colony.getName(), tag.member());
        }
    }

    /** Poof burst + sound at the envoy's position so the despawn reads
     *  visually. Called immediately before {@code discard()} so the
     *  particles spawn at the live position. */
    private static void despawnEnvoyWithEffect(ServerLevel level,
                                               net.minecraft.world.entity.Entity envoy) {
        double cx = envoy.getX();
        double cy = envoy.getY() + envoy.getBbHeight() * 0.5;
        double cz = envoy.getZ();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                cx, cy, cz, 24, 0.4, 0.6, 0.4, 0.02);
        level.playSound(null, cx, cy, cz,
                net.minecraft.sounds.SoundEvents.PLAYER_TELEPORT,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.8f, 1.2f);
    }

    /** {@code /envoystate} — print Stage 3a envoy state for the player's
     *  resolved colony. Per-race eligibility, timers, accepted set, active
     *  envoy, and the player's gamerule history. */
    private int handleEnvoyStateCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("/envoystate must be run by a player"));
            return 0;
        }
        IColony colony = resolveCommandColony(src, player);
        if (colony == null) return 0;
        ServerLevel level = player.serverLevel();
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
        int colonyId = colony.getID();
        long now = level.getGameTime();

        EnumSet<ColonyMember> members = config.getMembers(colonyId);
        EnumSet<ColonyMember> accepted = config.acceptedEnvoys(colonyId);
        long created = config.getColonyCreationTick(colonyId, now);
        long lastResolve = config.getLastEnvoyResolveTick(colonyId, -1L);
        UUID active = config.getActiveEnvoyUuid(colonyId);
        EnumSet<ColonyMember> playerHistory = config.playerNonColonistEnvoysSeen(player.getUUID());
        int maxNonColonist = level.getGameRules().getInt(RULE_MAX_NON_COLONIST_ENVOYS);
        int namedGoblins = countNamedGoblinsInColony(level, colonyId);
        int citizenCount = colony.getCitizenManager().getCurrentCitizenCount();
        long ageDays = (now - created) / TICKS_PER_DAY;
        long sinceResolveDays = lastResolve < 0 ? -1L : (now - lastResolve) / TICKS_PER_DAY;
        EnumSet<ColonyMember> eligible = computeEligibleEnvoys(level, colony, config);

        // Stage 3b — kill-gate state per race for diagnostic.
        long colonistKillReset = config.getColonistKillResetTick(colonyId, Long.MIN_VALUE);
        int goblinBaseline = config.getGoblinNamedBaseline(colonyId);
        int orcSnapshot = config.getOrcCitizenSnapshot(colonyId);
        int lizardmanSnapshot = config.getLizardmanCitizenSnapshot(colonyId);
        int dwarfSnapshot = config.getDwarfCitizenSnapshot(colonyId);

        // Stage J — deferred-content envoy condition state. Per-player
        // reads use the COLONY OWNER's UUID (not the command-runner), so
        // the diagnostic is accurate even when an admin runs it. Some
        // colonies have no owner (system-owned / orphan); in that case
        // the per-player flags read as false.
        UUID ownerUuid = colony.getPermissions().getOwner();
        long lastOwnerDeath = config.getLastOwnerDeathTick(colonyId, created);
        boolean hasDeathRecord = config.hasOwnerDeathRecord(colonyId);
        long daysSinceDeath = (now - lastOwnerDeath) / TICKS_PER_DAY;
        boolean villageEntered = ownerUuid != null && config.hasEnteredDwarvenVillage(ownerUuid);
        boolean orcDisasterDone = ownerUuid != null && config.hasDefeatedOrcDisaster(ownerUuid);
        boolean ifritDone = ownerUuid != null && config.hasDefeatedIfrit(ownerUuid);
        boolean dlDisabled = ownerUuid != null && config.isDemonLordPathDisabled(ownerUuid);
        boolean heroDisabled = ownerUuid != null && config.isHeroPathDisabled(ownerUuid);
        // Live demon-lord/hero read needs an online ServerPlayer. If the
        // owner is offline we can't read existence, so we report it as
        // "unknown" rather than a stale flag.
        ServerPlayer ownerOnline = ownerUuid == null ? null
                : level.getServer().getPlayerList().getPlayer(ownerUuid);
        IExistence ownerExistence = ownerOnline == null ? null : readExistenceSafe(ownerOnline);
        boolean isTrueDemonLord = ownerExistence != null && ownerExistence.isTrueDemonLord();
        boolean isTrueHero = ownerExistence != null && ownerExistence.isTrueHero();

        // Per-race eligibility diagnostic — explain WHY each race is or isn't
        // eligible so the player doesn't have to guess.
        StringBuilder perRace = new StringBuilder();
        for (ColonyMember m : ColonyMember.values()) {
            perRace.append("\n  ").append(m).append(": ");
            if (members.contains(m)) {
                perRace.append("ineligible — already in members (remove with /setcolonyrace remove ")
                        .append(m.name().toLowerCase(Locale.ROOT)).append(")");
            } else if (accepted.contains(m)) {
                perRace.append("ineligible — already accepted (IMMUNE to kill-gate)");
            } else if (m != ColonyMember.COLONIST
                    && playerHistory.size() >= maxNonColonist
                    && !playerHistory.contains(m)) {
                perRace.append("ineligible — your non-colonist envoy cap (")
                        .append(maxNonColonist)
                        .append(") is reached and this race isn't one you've seen");
            } else {
                switch (m) {
                    case COLONIST -> {
                        long anchor = Math.max(created, colonistKillReset);
                        long since = (now - anchor) / TICKS_PER_DAY;
                        String anchorNote = colonistKillReset > created
                                ? " (anchored at last kill-reset)"
                                : "";
                        if (since >= 3) perRace.append("ELIGIBLE (")
                                .append(since).append("d since anchor").append(anchorNote).append(")");
                        else perRace.append("ineligible — ")
                                .append(since).append("d since anchor (need 3)").append(anchorNote);
                    }
                    case GOBLIN -> {
                        int sinceBaseline = namedGoblins - goblinBaseline;
                        String baselineNote = goblinBaseline > 0
                                ? " (baseline " + goblinBaseline + " from kill-reset)"
                                : "";
                        if (sinceBaseline >= GOBLIN_UNLOCK_NAMED_COUNT)
                            perRace.append("ELIGIBLE (").append(sinceBaseline)
                                    .append(" since baseline").append(baselineNote).append(")");
                        else perRace.append("ineligible — ")
                                .append(sinceBaseline).append("/")
                                .append(GOBLIN_UNLOCK_NAMED_COUNT)
                                .append(" goblins named since baseline").append(baselineNote);
                    }
                    case ORC -> {
                        boolean threshold = citizenCount >= ORC_UNLOCK_CITIZEN_COUNT;
                        boolean grewPastSnapshot = orcSnapshot < 0 || citizenCount > orcSnapshot;
                        boolean altCitizens = threshold && grewPastSnapshot;
                        String snapNote = orcSnapshot >= 0
                                ? " (snapshot " + orcSnapshot + " from kill-reset)"
                                : "";
                        // Alternative-style report — show both branches.
                        if (altCitizens || orcDisasterDone)
                            perRace.append("ELIGIBLE");
                        else
                            perRace.append("ineligible");
                        perRace.append("\n      [citizens] ");
                        if (altCitizens)
                            perRace.append("OK — ").append(citizenCount).append(" citizens").append(snapNote);
                        else if (!threshold)
                            perRace.append("no — ").append(citizenCount).append("/")
                                    .append(ORC_UNLOCK_CITIZEN_COUNT).append(" citizens").append(snapNote);
                        else
                            perRace.append("no — citizen count ").append(citizenCount)
                                    .append(" must grow past snapshot ").append(orcSnapshot);
                        perRace.append("\n      [orc disaster defeated] ")
                                .append(orcDisasterDone ? "YES — permanent (IMMUNE to all resets)" : "no");
                    }
                    case LIZARDMAN -> {
                        boolean threshold = citizenCount >= LIZARDMAN_UNLOCK_CITIZEN_COUNT;
                        boolean grewPastSnapshot = lizardmanSnapshot < 0 || citizenCount > lizardmanSnapshot;
                        boolean altCitizens = threshold && grewPastSnapshot;
                        String snapNote = lizardmanSnapshot >= 0
                                ? " (snapshot " + lizardmanSnapshot + " from kill-reset)"
                                : "";
                        if (altCitizens || ifritDone)
                            perRace.append("ELIGIBLE");
                        else
                            perRace.append("ineligible");
                        perRace.append("\n      [citizens] ");
                        if (altCitizens)
                            perRace.append("OK — ").append(citizenCount).append(" citizens").append(snapNote);
                        else if (!threshold)
                            perRace.append("no — ").append(citizenCount).append("/")
                                    .append(LIZARDMAN_UNLOCK_CITIZEN_COUNT).append(" citizens").append(snapNote);
                        else
                            perRace.append("no — citizen count ").append(citizenCount)
                                    .append(" must grow past snapshot ").append(lizardmanSnapshot);
                        perRace.append("\n      [ifrit defeated] ")
                                .append(ifritDone
                                        ? "YES (cleared on lizardman kill)"
                                        : "no");
                    }
                    case DWARF -> {
                        // Five alternatives — placeholder, 20-day, village,
                        // demon lord, hero. The output explicitly lists each
                        // alternative so the player can see exactly which
                        // gate is the closest to unlock.
                        boolean countOk = citizenCount >= DWARF_UNLOCK_CITIZEN_COUNT_PLACEHOLDER;
                        boolean minerOk = colonyHasMiner(colony);
                        boolean grewPastSnapshot = dwarfSnapshot < 0 || citizenCount > dwarfSnapshot;
                        boolean altPlaceholder = countOk && minerOk && grewPastSnapshot;
                        boolean altDays = daysSinceDeath >= DWARF_UNLOCK_DAYS_NO_DEATH;
                        boolean altVillage = villageEntered;
                        boolean altDemonLord = isTrueDemonLord && !dlDisabled;
                        boolean altHero = isTrueHero && !heroDisabled;
                        boolean anyAlt = altPlaceholder || altDays || altVillage
                                || altDemonLord || altHero;
                        if (anyAlt) perRace.append("ELIGIBLE");
                        else perRace.append("ineligible");

                        // 1. Placeholder branch.
                        String snapNote = dwarfSnapshot >= 0
                                ? " (snapshot " + dwarfSnapshot + " from kill-reset)"
                                : "";
                        perRace.append("\n      [placeholder ≥30 citizens + Miner's Hut] ");
                        if (altPlaceholder) perRace.append("OK");
                        else if (!countOk)
                            perRace.append("no — ").append(citizenCount).append("/")
                                    .append(DWARF_UNLOCK_CITIZEN_COUNT_PLACEHOLDER).append(" citizens, miner ")
                                    .append(minerOk ? "present" : "missing").append(snapNote);
                        else if (!minerOk)
                            perRace.append("no — no Miner / Miner's Hut built (citizens OK)").append(snapNote);
                        else
                            perRace.append("no — citizen count ").append(citizenCount)
                                    .append(" must grow past snapshot ").append(dwarfSnapshot);

                        // 2. 20-day no-death.
                        perRace.append("\n      [20 days no owner death] ");
                        if (altDays) perRace.append("OK — ").append(daysSinceDeath).append("d");
                        else perRace.append("no — ").append(daysSinceDeath).append("/")
                                .append(DWARF_UNLOCK_DAYS_NO_DEATH).append("d");
                        if (hasDeathRecord) perRace.append(" (anchored at last owner death)");
                        else perRace.append(" (anchored at colony creation)");

                        // 3. Village.
                        perRace.append("\n      [dwarven village entered] ")
                                .append(altVillage ? "YES" : "no (cleared on dwarf kill)");

                        // 4 + 5. Live status. If owner is offline we say so.
                        if (ownerUuid == null) {
                            perRace.append("\n      [true demon lord] n/a — colony has no owner");
                            perRace.append("\n      [true hero] n/a — colony has no owner");
                        } else if (ownerOnline == null) {
                            perRace.append("\n      [true demon lord] owner offline — disable=")
                                    .append(dlDisabled ? "YES" : "no");
                            perRace.append("\n      [true hero] owner offline — disable=")
                                    .append(heroDisabled ? "YES" : "no");
                        } else {
                            perRace.append("\n      [true demon lord] status=")
                                    .append(isTrueDemonLord ? "YES" : "no")
                                    .append(", disable=")
                                    .append(dlDisabled ? "YES" : "no");
                            if (altDemonLord) perRace.append(" → OK");
                            perRace.append("\n      [true hero] status=")
                                    .append(isTrueHero ? "YES" : "no")
                                    .append(", disable=")
                                    .append(heroDisabled ? "YES" : "no");
                            if (altHero) perRace.append(" → OK");
                        }
                    }
                }
            }
        }

        // Spawn gate diagnostic — even if there's an eligible race, the
        // scheduler may still be blocked by a cooldown or active envoy.
        String spawnGate;
        if (active != null) {
            spawnGate = "BLOCKED — an active envoy already exists (uuid " + active + ")";
        } else if (lastResolve >= 0 && (now - lastResolve) < ENVOY_RESOLVE_GAP_TICKS) {
            long left = (ENVOY_RESOLVE_GAP_TICKS - (now - lastResolve)) / TICKS_PER_DAY;
            spawnGate = "BLOCKED — 3-day cooldown not elapsed (" + left + "d remaining)";
        } else if (eligible.isEmpty()) {
            spawnGate = "BLOCKED — no race currently eligible (see per-race reasons above)";
        } else {
            spawnGate = "READY — next scheduler tick will spawn from " + eligible;
        }

        final IColony c = colony;
        final String perRaceFinal = perRace.toString();
        final String spawnGateFinal = spawnGate;
        src.sendSuccess(() -> Component.literal(""
                + "=== Envoy state for '" + c.getName() + "' (id=" + colonyId + ") ===\n"
                + "Members: " + members + "\n"
                + "Accepted envoys (locked): " + accepted + "\n"
                + "GameTime: " + now + "  Created: " + created + "  Age: " + ageDays + "d\n"
                + "Last envoy resolved: "
                  + (sinceResolveDays < 0 ? "never" : sinceResolveDays + " day(s) ago (gap: 3d)") + "\n"
                + "Active envoy uuid: " + (active == null ? "<none>" : active.toString()) + "\n"
                + "Your non-colonist envoy history: " + playerHistory + " / cap " + maxNonColonist + "\n"
                + "Per-race eligibility:" + perRaceFinal + "\n"
                + "Eligible set: " + eligible + "\n"
                + "Next spawn: " + spawnGateFinal),
                false);
        return 1;
    }

    /** {@code /envoyresetcooldown} — clear the 3-day post-resolve cooldown
     *  so the next scheduler tick is unblocked. Debug-only. */
    private int handleEnvoyResetCooldownCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("/envoyresetcooldown must be run by a player"));
            return 0;
        }
        IColony colony = resolveCommandColony(src, player);
        if (colony == null) return 0;
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(player.serverLevel());
        // Set lastResolveTick far enough in the past that the gap check
        // always passes. Using -1 isn't ideal because the gate special-cases
        // it; instead anchor to "current time minus the full gap" so the
        // computation reads as "cooldown just expired."
        long now = player.serverLevel().getGameTime();
        config.setLastEnvoyResolveTick(colony.getID(), now - ENVOY_RESOLVE_GAP_TICKS);
        final IColony c = colony;
        src.sendSuccess(() -> Component.literal(
                "envoy cooldown reset for colony '" + c.getName()
                + "' — next /envoyforce should proceed"), true);
        return 1;
    }

    /** {@code /envoyforce} — single immediate scheduler tick for the player's
     *  colony with verbose chat diagnostics. Use when {@code /envoystate}
     *  reports READY but no envoy actually appears. */
    private int handleEnvoyForceCommand(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("/envoyforce must be run by a player"));
            return 0;
        }
        IColony colony = resolveCommandColony(src, player);
        if (colony == null) return 0;
        ServerLevel level = player.serverLevel();
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
        int colonyId = colony.getID();

        src.sendSuccess(() -> Component.literal("[envoyforce] starting for colony id=" + colonyId), false);

        // Gate 1: active envoy
        UUID active = config.getActiveEnvoyUuid(colonyId);
        if (active != null) {
            net.minecraft.world.entity.Entity stillThere = level.getEntity(active);
            if (stillThere != null && !stillThere.isRemoved()
                    && stillThere.hasData(Attachments.ENVOY_TAG.get())) {
                src.sendSuccess(() -> Component.literal(
                        "[envoyforce] BLOCKED — live envoy " + active + " still exists"), false);
                return 0;
            }
            src.sendSuccess(() -> Component.literal(
                    "[envoyforce] stale active uuid " + active + " — clearing"), false);
            config.setActiveEnvoyUuid(colonyId, null);
        }

        // Gate 2: cooldown
        long lastResolve = config.getLastEnvoyResolveTick(colonyId, -1L);
        long now = level.getGameTime();
        if (lastResolve >= 0 && (now - lastResolve) < ENVOY_RESOLVE_GAP_TICKS) {
            long leftTicks = ENVOY_RESOLVE_GAP_TICKS - (now - lastResolve);
            long leftDays = leftTicks / TICKS_PER_DAY;
            long leftRemainderTicks = leftTicks % TICKS_PER_DAY;
            src.sendSuccess(() -> Component.literal(
                    "[envoyforce] BLOCKED — cooldown " + leftDays + "d " + leftRemainderTicks
                    + "t remaining (" + leftTicks + " total ticks)\n"
                    + "  GameTime=" + now + " lastResolve=" + lastResolve
                    + " gap=" + ENVOY_RESOLVE_GAP_TICKS + "\n"
                    + "  Reset with /envoyresetcooldown or advance time more"), false);
            return 0;
        }

        // Gate 3: eligibility
        EnumSet<ColonyMember> eligible = computeEligibleEnvoys(level, colony, config);
        if (eligible.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "[envoyforce] BLOCKED — eligible set empty"), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "[envoyforce] eligible: " + eligible), false);

        // Pick deterministically: first in iteration order so the user can
        // predict which race spawns. (computeEligibleEnvoys uses
        // ColonyMember.values() order — COLONIST, GOBLIN, ORC.)
        ColonyMember pick = eligible.iterator().next();
        src.sendSuccess(() -> Component.literal(
                "[envoyforce] picking " + pick + " — calling spawnEnvoy"), false);

        net.minecraft.world.entity.Entity envoy;
        try {
            envoy = spawnEnvoy(level, colony, pick);
        } catch (Throwable t) {
            LOGGER.error("[TM] /envoyforce: spawnEnvoy threw", t);
            src.sendFailure(Component.literal(
                    "[envoyforce] EXCEPTION in spawnEnvoy: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + " — see server log"));
            return 0;
        }
        if (envoy == null) {
            src.sendFailure(Component.literal(
                    "[envoyforce] spawnEnvoy returned null — see server log for the reason "
                    + "(missing town hall / unknown entity type / addFreshEntity rejected)"));
            return 0;
        }
        config.setActiveEnvoyUuid(colonyId, envoy.getUUID());
        UUID ownerUuid = colony.getPermissions().getOwner();
        if (ownerUuid != null) config.recordPlayerEnvoySeen(ownerUuid, pick);

        net.minecraft.world.entity.Entity spawned = envoy;
        src.sendSuccess(() -> Component.literal(
                "[envoyforce] SPAWNED " + pick + " envoy at "
                + spawned.blockPosition() + " uuid=" + spawned.getUUID()), true);
        return 1;
    }

    /**
     * Temp command: {@code /spawnenvoy <goblin|orc|colonist>}. Spawns an
     * envoy at the executing player's owned-colony town hall (with the
     * same lookup fallback as /setcolonyrace). No unlock conditions, no
     * dialogue — pure Stage-1 manual test handle. Removed when the
     * scheduler lands in Stage 3.
     */
    private int handleSpawnEnvoyCommand(CommandContext<CommandSourceStack> ctx) {
        String memberArg = StringArgumentType.getString(ctx, "member").toLowerCase(java.util.Locale.ROOT);
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("/spawnenvoy must be run by a player"));
            return 0;
        }
        ColonyMember member = parseMember(memberArg);
        if (member == null) {
            src.sendFailure(Component.literal(
                    "unknown member '" + memberArg + "' — valid: goblin, orc, colonist"));
            return 0;
        }
        IColony colony = resolveCommandColony(src, player);
        if (colony == null) return 0;
        final IColony resolvedColony = colony;
        net.minecraft.world.entity.Entity envoy = spawnEnvoy(player.serverLevel(), resolvedColony, member);
        if (envoy == null) {
            src.sendFailure(Component.literal(
                    "envoy spawn failed — see server log (missing town hall, unknown entity, or addFreshEntity rejected)"));
            return 0;
        }
        // Treat the debug spawn just like a scheduled spawn so state stays
        // consistent: register active envoy + record player history. Means
        // the scheduler won't double-spawn while the debug envoy is alive,
        // and the player's gamerule cap counts /spawnenvoy uses too.
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(player.serverLevel());
        config.setActiveEnvoyUuid(resolvedColony.getID(), envoy.getUUID());
        UUID ownerUuid = resolvedColony.getPermissions().getOwner();
        if (ownerUuid != null) config.recordPlayerEnvoySeen(ownerUuid, member);

        final ColonyMember finalMember = member;
        src.sendSuccess(() -> Component.literal(
                finalMember + " envoy spawned for colony '" + resolvedColony.getName() + "'"), true);
        return 1;
    }

    /** Stage B cleanup — drop the colony's race entry when MC deletes
     *  the colony so a future re-creation under the same id starts
     *  fresh. */
    private void onColonyDeleted(ColonyDeletedModEvent event) {
        IColony colony = event.getColony();
        if (!(colony.getWorld() instanceof ServerLevel serverLevel)) return;
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(serverLevel);
        config.clearMembers(colony.getID());
        config.clearPending(colony.getID());
        LOGGER.info("[TM] colony '{}' deleted — cleared its race config entry",
                colony.getName());
    }

    /**
     * Server-side handler for the picker's choice payload. Dispatches
     * the choice byte (0=DEFAULT/1=GOBLIN/2=ORC) into the config, then
     * sends the race-specific flavour message + the "harder than normal"
     * advisory for mob races.
     *
     * Ownership check — only the colony owner can answer the picker for
     * their colony. Defensive against a malicious client spoofing the
     * colonyId; for the legitimate flow the picker can only have been
     * opened against the owner's colony in the first place.
     */
    static void handleRaceChoice(ServerPlayer player, int colonyId, byte choice) {
        ServerLevel level = player.serverLevel();
        IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
        if (colony == null) {
            LOGGER.warn("[TM] race choice from {}: colony {} not found",
                    player.getName().getString(), colonyId);
            return;
        }
        // Ownership gate
        UUID ownerUuid = colony.getPermissions().getOwner();
        if (ownerUuid == null || !ownerUuid.equals(player.getUUID())) {
            LOGGER.warn("[TM] race choice from {}: not the colony owner (owner={}), ignoring",
                    player.getName().getString(), ownerUuid);
            return;
        }

        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
        config.clearPending(colonyId);

        switch (choice) {
            case Networking.RaceChoicePayload.CHOICE_DEFAULT -> {
                // DEFAULT now writes an EXPLICIT {COLONIST} member rather
                // than "no entry". Functionally identical at spawn time
                // (both yield vanilla citizens) but distinguishes "player
                // chose colonists" from "legacy / pre-menu colony" — which
                // the envoy system will need to mix in additional members.
                config.setSingleMember(colonyId, ColonyMember.COLONIST);
                // DELIBERATELY no message sent here. The vanilla MC
                // "colony_founded" / "colony_reactivated" chat already fires
                // for the DEFAULT pick path (the CreateColonyMessageMixin
                // wraps the sendTo at ordinals 2 and 3, but in current MC
                // builds these still pass through for the DEFAULT-equivalent
                // outcome the player sees). Re-issuing it from here produced
                // a visible duplicate. For GOBLIN / ORC the mixin's
                // suppression keeps the MC message hidden and we issue our
                // race-specific flavour message in those branches instead.
                LOGGER.info("[TM] race choice: colony {} ('{}') → COLONIST (vanilla MC citizens)",
                        colonyId, colony.getName());
            }
            case Networking.RaceChoicePayload.CHOICE_GOBLIN -> {
                config.setSingleMember(colonyId, ColonyMember.GOBLIN);
                player.sendSystemMessage(Component.translatable(
                        "tensura_minecolonies.colony.created.goblin"));
                LOGGER.info("[TM] race choice: colony {} ('{}') → GOBLIN",
                        colonyId, colony.getName());
            }
            case Networking.RaceChoicePayload.CHOICE_ORC -> {
                config.setSingleMember(colonyId, ColonyMember.ORC);
                player.sendSystemMessage(Component.translatable(
                        "tensura_minecolonies.colony.created.orc"));
                LOGGER.info("[TM] race choice: colony {} ('{}') → ORC",
                        colonyId, colony.getName());
            }
            default -> {
                LOGGER.warn("[TM] race choice from {}: unknown choice byte {}, ignoring",
                        player.getName().getString(), choice);
                // Re-mark pending so the player can re-pick — the picker
                // had a corrupt button state.
                config.markPending(colonyId);
            }
        }
    }

    /**
     * Re-send the picker on login if the player owns any colony that's
     * still in the pending-choice set. Covers the case where the player
     * created a colony, ESC'd the picker, then logged out.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        ServerLevel level = sp.serverLevel();
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);

        for (Integer colonyId : config.pendingColonies()) {
            IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
            if (colony == null) continue;
            UUID ownerUuid = colony.getPermissions().getOwner();
            if (ownerUuid == null || !ownerUuid.equals(sp.getUUID())) continue;
            // Catch-up grant for the owner-was-offline-at-creation path.
            // Idempotent: award() returns false if already held.
            grantRookieRuler(sp);
            PacketDistributor.sendToPlayer(sp,
                    new Networking.OpenRacePickerPayload(colonyId, colony.getName()));
            LOGGER.info("[TM] colony '{}' pending — re-sending picker to {} on login",
                    colony.getName(), sp.getName().getString());
        }
    }

    /**
     * Re-engagement: right-clicking the town hall block of a pending
     * colony re-opens the picker. Covers the "I ESC'd by accident"
     * recovery case without requiring a relog.
     *
     * Detection: at the right-clicked position, look up the closest
     * colony; if its town hall is at that position AND the colony is
     * still in {@code pendingChoice} AND the right-clicker is the
     * colony owner, send the picker.
     */
    @SubscribeEvent
    public void onTownHallRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();
        IColony colony = IColonyManager.getInstance().getClosestColony(level, pos);
        if (colony == null) return;
        if (!colony.getServerBuildingManager().hasTownHall()) return;
        if (!colony.getServerBuildingManager().getTownHall().getPosition().equals(pos)) return;

        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(level);
        if (!config.isPending(colony.getID())) return;

        UUID ownerUuid = colony.getPermissions().getOwner();
        if (ownerUuid == null || !ownerUuid.equals(sp.getUUID())) return;

        PacketDistributor.sendToPlayer(sp,
                new Networking.OpenRacePickerPayload(colony.getID(), colony.getName()));
        LOGGER.info("[TM] colony '{}' pending — re-sending picker on town hall right-click",
                colony.getName());
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

        // Stage 3b — kill-gate. Killing a Tensura race (that you haven't
        // accepted via diplomacy) resets that race's envoy unlock condition
        // for every colony YOU own. Runs before the named-citizen cleanup
        // below so a player murdering their own named-race subordinate
        // also resets the condition (consistent with "kin killed").
        processEnvoyKillGate(serverLevel, event);

        // Deferred-content envoy conditions — three death-detection passes
        // independent of the kill-gate (which handles race-mob kills only):
        //   (a) Player owning a colony dies → bump per-colony death tick.
        //   (b) Orc Disaster killed by a player → permanent flag set.
        //   (c) Ifrit killed by a player → per-player flag set.
        processColonyOwnerDeath(serverLevel, event);
        processBossKillFlags(serverLevel, event);

        UUID entityUUID = event.getEntity().getUUID();
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(serverLevel);

        // Stage 1b cleanup: if this entity was in the pending pool (named before
        // any colony existed), drop the pending entry. No-op for normal goblins.
        saved.removePendingByMobUUID(entityUUID);

        RaceIdentitySavedData.RaceIdentity identity = saved.getByMobUUID(entityUUID);
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

        RaceIdentitySavedData saved = RaceIdentitySavedData.get(serverLevel);
        RaceIdentitySavedData.RaceIdentity identity = saved.getByCitizenId(citizenId);
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
                                           RaceIdentitySavedData.RaceIdentity identity,
                                           ServerLevel serverLevel,
                                           RaceIdentitySavedData saved) {
        LOGGER.info("[TM] send: starting for goblin '{}' (citizen {})",
                goblin.getName().getString(), identity.citizenId);

        // Defense-in-depth — handleMenuAction already gates on Races.isBlocked,
        // but check again here in case some future entry path skips that
        // chokepoint. Blocking evolved orc tiers (orc_lord, orc_disaster)
        // until per-tier shadow pools are implemented.
        if (Races.isBlocked(goblin.getType())) {
            LOGGER.warn("[TM] send: aborted — entity type {} is blocked from the citizen pipeline",
                    BuiltInRegistries.ENTITY_TYPE.getKey(goblin.getType()));
            sendAdvisoryNotice(triggeringPlayer,
                    "Orc lords and orc disasters cannot serve as colony citizens.");
            return;
        }

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

        // 2. Find the spawn position — town hall anchor.
        if (!colony.getServerBuildingManager().hasTownHall()) {
            LOGGER.warn("[TM] send: colony '{}' has no town hall — aborting", colony.getName());
            return;
        }
        BlockPos townHallPos = colony.getServerBuildingManager().getTownHall().getPosition();

        // 2b. EARLY variant capture — must happen BEFORE the citizen body
        //     is spawned. This is the most throw-prone read on the goblin
        //     (it crashed historically on Tensura's -1 head sentinel), so
        //     running it first means a failure aborts cleanly with the
        //     goblin still at the player's side and no orphaned citizen
        //     in the world. Only depends on the live goblin entity.
        RaceVariantData variant;
        try {
            variant = captureRaceVariant(goblin, identity.race);
        } catch (Throwable t) {
            LOGGER.error("[TM] send: variant capture threw for identity {} — aborting before spawn",
                    identity.identityId, t);
            sendAdvisoryNotice(triggeringPlayer,
                    "Couldn't read your subordinate's appearance — try again.");
            return;
        }

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
        //
        //     Wrapped in try/catch with rollback: any throwable from
        //     markMaterializedBody / setData / broadcast / bumpAttr /
        //     copyStats / HP copy WOULD otherwise leave an orphaned
        //     citizen in the world (body spawned, no race tag attached,
        //     no stats copied — persists across reload as a normal-skin
        //     citizen counting in the colony). The rollback discards the
        //     body and re-suppresses travelling so the CitizenData stays
        //     valid but bodyless, identical shape to the existing
        //     "chunk not loaded" failure handling above. Identity stays
        //     intact — the goblin is still at the player's side.
        java.util.Optional<? extends com.minecolonies.api.entity.citizen.AbstractCivilianEntity> bodyOpt
                = spawned.getEntity();
        if (bodyOpt.isEmpty()) {
            // Defensive — we already checked above, but the Optional could
            // race to empty if MineColonies' internals clear it. Treat as
            // chunk-not-loaded.
            sendAdvisoryNotice(triggeringPlayer, citizenData.getName() +
                    " couldn't reach the colony — try again from closer.");
            colony.getTravellingManager().startTravellingTo(citizenData, townHallPos, Integer.MAX_VALUE);
            return;
        }
        com.minecolonies.api.entity.citizen.AbstractCivilianEntity citizenBody = bodyOpt.get();

        try {
            // Capture the citizen's surface Y BEFORE we lower it, then queue
            // the rise. The body appears underground first and visually
            // emerges from the materialize-end circle.
            double citizenSurfaceY = citizenBody.getY();
            markMaterializedBody(serverLevel, citizenBody, citizenSurfaceY);

            // Sync the goblin's equipment onto the citizen ENTITY's slots
            // so it visually renders with the same armor + weapon. Items
            // are already in citizenData.getInventory() from step 5; the
            // entity equipment slots are a SEPARATE channel that the
            // HumanoidArmorLayer / ItemInHandLayer in GoblinCitizenRenderer
            // reads from. Without this the citizen would look bare-handed
            // and bare-skinned regardless of inventory contents.
            applyEquipmentFromInventory(citizenBody, citizenData.getInventory());

            // Propagate baby/child state. Vanilla Mob.isBaby() drives
            // hitbox via getAgeScale() (auto, since EntityCitizen.getAgeScale
            // returns 0.62 for child) and the model's `young` flag via
            // LivingEntityRenderer (auto). MineColonies' citizen exposes
            // setIsChild on AbstractEntityCitizen — the AbstractCivilianEntity
            // type spawnOrCreateCivilian returns is the abstract parent, so
            // we guard with instanceof before casting. Summon path round-trips
            // the baby state automatically via the entity NBT snapshot
            // (vanilla AgeableMob saves/loads the Age int).
            if (goblin.isBaby() &&
                    citizenBody instanceof AbstractEntityCitizen citizenAsCitizen) {
                citizenAsCitizen.setIsChild(true);
                LOGGER.info("[TM] send: citizen {} marked as child (source goblin was baby)",
                        identity.citizenId);
            }

            // Stage F1 — attach the RaceTag to the freshly-spawned
            // citizen entity and broadcast it to every player currently
            // tracking the body. Players who start tracking later get
            // the tag via PlayerEvent.StartTracking.
            RaceTag tag = RaceTag.of(identity.identityId, identity.race, variant);
            citizenBody.setData(Attachments.RACE_TAG.get(), tag);
            PacketDistributor.sendToPlayersTrackingEntity(citizenBody,
                    Networking.SyncRaceTagPayload.of(citizenBody.getUUID(), tag));
            // Race-discriminated log: pattern-match the sealed variant so
            // each race logs its own fingerprint instead of forcing
            // goblin-shaped fields onto the wire.
            String variantFingerprint = switch (variant) {
                case GoblinVariantData g -> String.format(
                        "g%d/s%d/f%d/h%d/hc%s/b%s/evo%d",
                        g.gender(), g.skin(), g.face(), g.hair(),
                        Integer.toHexString(g.hairColor()), g.bandages(),
                        g.evolutionState());
                case OrcVariantData o -> String.format(
                        "var%d/n%d/nc%s/t%d/tc%s/btmC%s/bltC%s/btsC%s/bg%s/nl%s/evo%d/evg%d",
                        o.variantId(), o.neckId(),
                        Integer.toHexString(o.neckColor()), o.topId(),
                        Integer.toHexString(o.topColor()),
                        Integer.toHexString(o.bottomColor()),
                        Integer.toHexString(o.beltColor()),
                        Integer.toHexString(o.bootsColor()),
                        o.bandage(), o.necklace(),
                        o.evolutionState(), o.evolving());
                case LizardmanVariantData l -> String.format(
                        "var%d/h%d/hc%s/t%d/tc%s/btmC%s/bg%s/evo%d/evg%d",
                        l.variantId(), l.hairId(),
                        Integer.toHexString(l.hairColor()), l.topId(),
                        Integer.toHexString(l.topColor()),
                        Integer.toHexString(l.bottomColor()),
                        l.bandage(), l.evolutionState(), l.evolving());
                case DwarfVariantData d -> String.format(
                        "g%d/s%d/f%d/sc%d/h%d/fh%d/t%d/b%d/ft%d/hc%s/tc%s/bc%s/fc%s/scl%.3f",
                        d.gender(), d.skin(), d.face(), d.scar(),
                        d.hair(), d.facialHair(), d.top(), d.bottom(), d.feet(),
                        Integer.toHexString(d.hairColor()),
                        Integer.toHexString(d.topColor()),
                        Integer.toHexString(d.bottomColor()),
                        Integer.toHexString(d.feetColor()),
                        d.scale());
            };
            LOGGER.info("[TM] send: race tag attached to citizen entity {} (identity {} race={}) variant={}",
                    citizenBody.getUUID(), identity.identityId, identity.race,
                    variantFingerprint);

            // Wrinkle 2 (dwarf) — set vanilla SCALE attribute so the
            // citizen body renders AND collides at half size. SCALE drives
            // both LivingEntity.getDimensions (hitbox) and the renderer's
            // intrinsic scale (LivingEntityRenderer reads entity.getScale()).
            // Persists on the AttributeInstance — survives save/load.
            applyRaceScaleAttribute(citizenBody, identity.race);

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
        } catch (Throwable t) {
            LOGGER.error("[TM] send: post-spawn body setup failed for citizen {} — rolling back",
                    citizenData.getId(), t);
            // ROLLBACK — same shape as the "chunk not loaded" branch above:
            // discard the body, re-suppress respawn via travelling, advise
            // the player. CitizenData stays valid (count unchanged); the
            // identity is preserved; the goblin is still at the player's
            // side (we haven't discarded it yet). Items have already been
            // copied into citizenData.getInventory() at step 5 — they'll
            // come back to the goblin on a successful retry via the same
            // transfer-citizen-to-goblin path used by summon.
            citizenBody.discard();
            colony.getTravellingManager().startTravellingTo(
                    citizenData, townHallPos, Integer.MAX_VALUE);
            sendAdvisoryNotice(triggeringPlayer,
                    citizenData.getName() + " couldn't be sent — try again.");
            return;
        }

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
        identity.mobEntityUUID = null; // goblin entity no longer exists
        saved.updateMode(identity, RaceIdentitySavedData.Mode.IN_COLONY);

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
        // Race-system PoC — toggle the race on an IN_COLONY citizen the
        // player owns. Used to flip a goblin-tagged citizen to ORC so the
        // orc shadow-entity render path can be tested with the existing
        // send pipeline. Will be removed once a proper race-aware spawn
        // path lands.
        event.getDispatcher().register(
                Commands.literal("raceflip")
                        .requires(src -> src.hasPermission(0))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(this::handleRaceFlipCommand))
        );
        // Stage B / multi-race — manage the executing player's owned-colony
        // composition. Usage:
        //   /setcolonyrace <goblin|orc|colonist|clear>   — replace with one
        //   /setcolonyrace add <member>                  — add member
        //   /setcolonyrace remove <member>               — remove member
        //   /setcolonyrace list                          — show current set
        event.getDispatcher().register(
                Commands.literal("setcolonyrace")
                        .requires(src -> src.hasPermission(0))
                        .then(Commands.literal("add")
                                .then(Commands.argument("member", StringArgumentType.word())
                                        .executes(ctx -> handleColonyMemberMutate(ctx, true))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("member", StringArgumentType.word())
                                        .executes(ctx -> handleColonyMemberMutate(ctx, false))))
                        .then(Commands.literal("list")
                                .executes(this::handleListColonyMembers))
                        .then(Commands.argument("race", StringArgumentType.word())
                                .executes(this::handleSetColonyRaceCommand))
        );
        // Envoy Stage 1 test handle: /spawnenvoy <goblin|orc|colonist>.
        // No unlock conditions; spawns an envoy at the player's owned-colony
        // town hall. Removed when the scheduler lands in Stage 3.
        event.getDispatcher().register(
                Commands.literal("spawnenvoy")
                        .requires(src -> src.hasPermission(0))
                        .then(Commands.argument("member", StringArgumentType.word())
                                .executes(this::handleSpawnEnvoyCommand))
        );
        // Stage 3a debug — inspect a colony's envoy state.
        event.getDispatcher().register(
                Commands.literal("envoystate")
                        .requires(src -> src.hasPermission(0))
                        .executes(this::handleEnvoyStateCommand)
        );
        // Stage 3a debug — run the scheduler ONCE for the player's colony
        // and report every gate decision + spawn outcome in chat.
        event.getDispatcher().register(
                Commands.literal("envoyforce")
                        .requires(src -> src.hasPermission(0))
                        .executes(this::handleEnvoyForceCommand)
        );
        // Stage 3a debug — reset the 3-day envoy cooldown so the next
        // scheduler tick is unblocked.
        event.getDispatcher().register(
                Commands.literal("envoyresetcooldown")
                        .requires(src -> src.hasPermission(0))
                        .executes(this::handleEnvoyResetCooldownCommand)
        );
    }

    /** Resolve the player's colony for /setcolonyrace, using the same
     *  "owner first, then first colony in level" fallback as onRaceNamed.
     *  Returns null with an error already sent if neither yields a colony. */
    private static IColony resolveCommandColony(CommandSourceStack src, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        IColonyManager colonyManager = IColonyManager.getInstance();
        IColony colony = colonyManager.getIColonyByOwner(level, player);
        if (colony == null) {
            List<IColony> all = colonyManager.getColonies(level);
            colony = all.isEmpty() ? null : all.get(0);
        }
        if (colony == null) {
            src.sendFailure(Component.literal("no colony found — create one first"));
        }
        return colony;
    }

    /** Bare-arg form: {@code /setcolonyrace <goblin|orc|colonist|clear>} —
     *  replaces the colony's composition set with a single member (or clears
     *  it). Preserved for backward compat with pre-multi-race tooling. */
    private int handleSetColonyRaceCommand(CommandContext<CommandSourceStack> ctx) {
        String raceArg = StringArgumentType.getString(ctx, "race").toLowerCase(java.util.Locale.ROOT);
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("/setcolonyrace must be run by a player"));
            return 0;
        }
        IColony colony = resolveCommandColony(src, player);
        if (colony == null) return 0;
        final IColony resolvedColony = colony;
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(player.serverLevel());

        if (raceArg.equals("clear") || raceArg.equals("none") || raceArg.equals("default")) {
            config.clearMembers(resolvedColony.getID());
            src.sendSuccess(() -> Component.literal(
                    "colony '" + resolvedColony.getName()
                    + "' members cleared — legacy / vanilla MineColonies citizens"), true);
            LOGGER.info("[TM] /setcolonyrace: colony {} ('{}') members CLEARED",
                    resolvedColony.getID(), resolvedColony.getName());
            return 1;
        }

        ColonyMember member = parseMember(raceArg);
        if (member == null) {
            src.sendFailure(Component.literal(
                    "unknown member '" + raceArg + "' — valid values: goblin, orc, colonist, clear"));
            return 0;
        }
        config.setSingleMember(resolvedColony.getID(), member);
        final ColonyMember finalMember = member;
        src.sendSuccess(() -> Component.literal(
                "colony '" + resolvedColony.getName() + "' members set to {" + finalMember + "}"), true);
        LOGGER.info("[TM] /setcolonyrace: colony {} ('{}') members set to {{{}}}",
                resolvedColony.getID(), resolvedColony.getName(), member);
        return 1;
    }

    /** {@code /setcolonyrace add <member>} or {@code /setcolonyrace remove <member>}. */
    private int handleColonyMemberMutate(CommandContext<CommandSourceStack> ctx, boolean adding) {
        String memberArg = StringArgumentType.getString(ctx, "member").toLowerCase(java.util.Locale.ROOT);
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("/setcolonyrace must be run by a player"));
            return 0;
        }
        ColonyMember member = parseMember(memberArg);
        if (member == null) {
            src.sendFailure(Component.literal(
                    "unknown member '" + memberArg + "' — valid values: goblin, orc, colonist"));
            return 0;
        }
        IColony colony = resolveCommandColony(src, player);
        if (colony == null) return 0;
        final IColony resolvedColony = colony;
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(player.serverLevel());

        boolean changed = adding
                ? config.addMember(resolvedColony.getID(), member)
                : config.removeMember(resolvedColony.getID(), member);

        EnumSet<ColonyMember> after = config.getMembers(resolvedColony.getID());
        String verb = adding ? "added to" : "removed from";
        String noop = adding ? "already in" : "not in";
        final ColonyMember finalMember = member;
        if (changed) {
            src.sendSuccess(() -> Component.literal(
                    finalMember + " " + verb + " colony '" + resolvedColony.getName()
                    + "' — members now " + after), true);
            LOGGER.info("[TM] /setcolonyrace {}: colony {} member {} — set is now {}",
                    adding ? "add" : "remove", resolvedColony.getID(), member, after);
        } else {
            src.sendSuccess(() -> Component.literal(
                    finalMember + " is " + noop + " colony '" + resolvedColony.getName()
                    + "' — no change. Current members: " + after), false);
        }
        return 1;
    }

    /** {@code /setcolonyrace list}. */
    private int handleListColonyMembers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("/setcolonyrace must be run by a player"));
            return 0;
        }
        IColony colony = resolveCommandColony(src, player);
        if (colony == null) return 0;
        final IColony resolvedColony = colony;
        ColonyRaceConfigSavedData config = ColonyRaceConfigSavedData.get(player.serverLevel());
        EnumSet<ColonyMember> members = config.getMembers(resolvedColony.getID());
        if (members.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "colony '" + resolvedColony.getName()
                    + "' has no members configured (legacy / vanilla citizens)"), false);
        } else {
            src.sendSuccess(() -> Component.literal(
                    "colony '" + resolvedColony.getName() + "' members: " + members), false);
        }
        return 1;
    }

    /** Parse a member-name argument (case-insensitive). Returns null on
     *  unknown input — the caller surfaces the error. */
    private static ColonyMember parseMember(String arg) {
        try {
            return ColonyMember.valueOf(arg.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int handleRaceFlipCommand(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            src.sendFailure(Component.literal("/raceflip must be run by a player"));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        IColonyManager cm = IColonyManager.getInstance();

        // Same lookup shape as /summongoblin — IN_COLONY identity, owned, name match.
        RaceIdentitySavedData.RaceIdentity match = null;
        IColony matchColony = null;
        ICitizenData matchData = null;
        for (RaceIdentitySavedData.RaceIdentity id : saved.all()) {
            if (id.mode != RaceIdentitySavedData.Mode.IN_COLONY) continue;
            if (!player.getUUID().equals(id.ownerPlayerUUID)) continue;
            IColony c = cm.getColonyByWorld(id.colonyId, level);
            if (c == null) continue;
            ICitizenData cd = c.getCitizenManager().getCivilian(id.citizenId);
            if (cd == null) continue;
            if (!name.equals(cd.getName())) continue;
            match = id;
            matchColony = c;
            matchData = cd;
            break;
        }
        if (match == null) {
            src.sendFailure(Component.literal(
                    "no IN_COLONY identity named '" + name + "' that you own"));
            return 0;
        }

        // Citizen body must currently be loaded for us to flip the attachment.
        AbstractEntityCitizen body = matchData.getEntity().orElse(null);
        if (body == null) {
            src.sendFailure(Component.literal(
                    "citizen '" + name + "' isn't in a loaded chunk right now"));
            return 0;
        }

        // Read → flip → write the attachment, then broadcast the new tag to
        // every player tracking the entity so client mirrors update at once.
        RaceTag current = body.getData(Attachments.RACE_TAG.get());
        if (current == null) {
            src.sendFailure(Component.literal(
                    "citizen '" + name + "' has no race tag attached (legacy spawn?)"));
            return 0;
        }
        RaceTag flipped = current.withRace(current.race().other());
        body.setData(Attachments.RACE_TAG.get(), flipped);
        PacketDistributor.sendToPlayersTrackingEntity(body,
                Networking.SyncRaceTagPayload.of(body.getUUID(), flipped));

        final Race newRace = flipped.race();
        final String displayName = name;
        src.sendSuccess(() -> Component.literal(
                "race flipped: '" + displayName + "' is now " + newRace), false);
        LOGGER.info("[TM] raceflip: '{}' (citizen {}) → race {}",
                name, matchData.getId(), newRace);
        return 1;
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
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        IColonyManager cm = IColonyManager.getInstance();

        // Find an IN_COLONY identity OWNED by this player whose citizen name matches.
        // The command only routes the lookup; the actual action goes through the
        // shared chokepoint so the magicule cost / collapse-prompt apply identically.
        java.util.UUID matchId = null;
        for (RaceIdentitySavedData.RaceIdentity id : saved.all()) {
            if (id.mode != RaceIdentitySavedData.Mode.IN_COLONY) continue;
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
                                     RaceIdentitySavedData saved,
                                     RaceIdentitySavedData.RaceIdentity identity,
                                     IColony colony,
                                     ICitizenData citizenData,
                                     Vec3 materializePos) {
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

        // 3b. Skin / variant sync — apply the citizen's RaceTag variant
        //     onto the freshly-reconstructed wild mob so its appearance
        //     EXACTLY matches what the player has been watching in
        //     colony. NBT-restore from {@code snapshot} normally
        //     handles this, but the snapshot was captured at first
        //     send and is older than the citizen's RaceTag (which is
        //     the source of truth for what's been rendered). Without
        //     this step, drift between snapshot and current RaceTag
        //     manifests as "summoned mob's skin doesn't match the
        //     citizen I just summoned" — exactly the reported bug.
        //
        //     We read RaceTag off the live citizen body if it's still
        //     loaded (citizenBodyOpt populated above); if not (chunk
        //     unloaded), we fall back to the snapshot's NBT-restored
        //     appearance, which is the best we have.
        if (citizenBodyOpt.isPresent()
                && citizenBodyOpt.get() instanceof LivingEntity citizenForSkin
                && citizenForSkin.hasData(Attachments.RACE_TAG.get())) {
            RaceTag srcTag = citizenForSkin.getData(Attachments.RACE_TAG.get());
            applyVariantToMob(goblin, srcTag);
            LOGGER.info("[TM] summon: applied citizen RaceTag variant onto wild mob (race={})",
                    srcTag.race());
        }

        // 4. CRITICAL — regenerate UUID. The tag carries the OLD goblin's UUID;
        //    if we kept it, the reverse map would still point at a stale identity
        //    record. setUUID before addFreshEntity ensures the world tracks the
        //    new UUID from the start.
        goblin.setUUID(UUID.randomUUID());

        // 5. CRITICAL — override position. The tag carries the OLD position
        //    (where the goblin was when sent). We use materializePos, which
        //    was locked at queue time (raytraced from the player's view to
        //    the block they were looking at, or the player's position as
        //    fallback). This way the goblin appears exactly where the
        //    materialize circle is, regardless of player movement during
        //    the delay.
        goblin.moveTo(materializePos.x, materializePos.y, materializePos.z,
                player.getYRot(), 0f);
        BlockPos spawnPos = goblin.blockPosition();

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
        //
        //     Stage F1: BEFORE discarding, clear the goblin-tag attachment and
        //     broadcast the clear to all tracking players. The entity-remove
        //     packet that follows would clean up the client mirror anyway via
        //     EntityLeaveLevelEvent, but the explicit clear payload is cheap
        //     insurance against any reordering and makes the round-trip
        //     symmetric with the send-side broadcast.
        citizenBodyOpt.ifPresent(citizenBody -> {
            if (citizenBody.hasData(Attachments.RACE_TAG.get())) {
                citizenBody.removeData(Attachments.RACE_TAG.get());
                PacketDistributor.sendToPlayersTrackingEntity(citizenBody,
                        Networking.SyncRaceTagPayload.clear(citizenBody.getUUID()));
                LOGGER.info("[TM] summon: race tag cleared from citizen entity {}",
                        citizenBody.getUUID());
            }
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
        saved.updateMobUUID(identity, goblin.getUUID());

        // 11. Update mode to SUBORDINATE.
        saved.updateMode(identity, RaceIdentitySavedData.Mode.SUBORDINATE);

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
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(player.serverLevel());
        RaceIdentitySavedData.RaceIdentity identity = saved.getById(identityId);
        if (identity == null) {
            sendAdvisoryNotice(player, "That citizen no longer exists.");
            return;
        }
        // Ownership check — only the namer can act on the identity.
        if (identity.ownerPlayerUUID == null
                || !player.getUUID().equals(identity.ownerPlayerUUID)) {
            sendAdvisoryNotice(player, "That citizen isn't yours.");
            return;
        }
        // Resolve the live body whose IExistence we'll read for the cost gate.
        LivingEntity target = resolveTargetBody(player, identity);
        if (target == null) {
            String msg = identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE
                    ? "Your subordinate isn't in any loaded chunk right now — go closer and try again."
                    : "That citizen's body isn't loaded right now — visit the colony first.";
            sendAdvisoryNotice(player, msg);
            return;
        }
        // Stage 3 — block evolved-tier orcs from the send path. Detected
        // via the live subordinate mob's actual EntityType (NOT the
        // identity's stored race, which is fixed at naming time and
        // doesn't change when the orc evolves at the player's side).
        // Surfaces here so the user gets feedback BEFORE the magic-circle
        // delay; sendNamedMobToColony has the same check as belt-and-braces.
        if (identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE
                && Races.isBlocked(target.getType())) {
            sendAdvisoryNotice(player,
                    "Orc lords and orc disasters cannot serve as colony citizens.");
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
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(player.serverLevel());
        RaceIdentitySavedData.RaceIdentity identity = saved.getById(identityId);
        if (identity == null) {
            sendAdvisoryNotice(player, "That citizen no longer exists.");
            return;
        }
        if (identity.ownerPlayerUUID == null
                || !player.getUUID().equals(identity.ownerPlayerUUID)) {
            sendAdvisoryNotice(player, "That citizen isn't yours.");
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
    // Stage 2a — bulk summon (drag-multi-select up to 9)
    // ------------------------------------------------------------------
    //
    // Treats N summons as one cost decision against the player's current
    // magicule:
    //   total > 1.25 × current   → FAIL — summon none, charge nothing
    //   total ≤ current          → safe path — deduct total, queue all
    //   in between               → overspend — zero out magicule (existing
    //                              Sleep-Mode pipeline triggers naturally),
    //                              queue all; per-identity paid is the
    //                              proportional share of the actual paid amount
    //                              (= the player's pre-zero magicule) so the
    //                              refund-on-abort path stays correct.
    //
    // Each queued swap reuses queueDelayedSwap → executeAction → summonGoblin,
    // i.e. THE SAME single-summon path. Bulk is a cost wrapper + N circles,
    // not a new code path. State restoration, identity reverse-map updates,
    // tag broadcasts, etc. all keep firing per-identity.
    //
    // SUBORDINATE-mode IDs in the request are silently skipped — bulk-send is
    // not yet supported; the client also avoids selecting them, this is
    // belt-and-braces.
    // ------------------------------------------------------------------

    /** Maximum identities accepted in one bulk request (summon or send). Matches
     *  the client-side selection cap and the 3×3 placement plan for Stage 2b. */
    static final int BULK_SUMMON_CAP = 9;

    /** Envoy roam radius — wandered around the town hall via PathfinderMob.restrictTo. */
    static final int ENVOY_ROAM_RADIUS = 15;

    /** Outcome of the three-band cost decision applied to a bulk request.
     *  {@code proceed = false} means an advisory was already shown and the
     *  caller must NOT queue anything. */
    private record BulkCostDecision(boolean proceed, double actualPaid, boolean overspend) {}

    /**
     * Three-band cost decision shared by bulk-summon and bulk-send.
     * Atomic — either the caller queues every swap in the batch, or none.
     * {@code failAdvisory} is the player-facing message for the refused band
     * (worded differently for summon vs send).
     */
    private static BulkCostDecision decideBulkCost(ServerPlayer player, double total,
                                                   String operationLabel, String failAdvisory) {
        ExistenceStorage playerExist = readExistence(player);
        if (playerExist == null) {
            sendAdvisoryNotice(player, "Couldn't read your magicule.");
            return new BulkCostDecision(false, 0.0, false);
        }
        double playerMag = playerExist.getMagicule();
        if (total > playerMag * 1.25) {
            sendAdvisoryNotice(player, failAdvisory);
            LOGGER.info("[TM] {}: REFUSED — total cost {} > 1.25× magicule ({})",
                    operationLabel, total, playerMag);
            return new BulkCostDecision(false, 0.0, false);
        } else if (total <= playerMag) {
            playerExist.setMagicule(playerMag - total);
            playerExist.markDirty();
            LOGGER.info("[TM] {}: safe path — deducted {} (had {}, now {})",
                    operationLabel, total, playerMag, playerMag - total);
            return new BulkCostDecision(true, total, false);
        } else {
            playerExist.setMagicule(0.0);
            playerExist.markDirty();
            boolean infMaterials = player.hasInfiniteMaterials();
            boolean invulnerable = player.isInvulnerable();
            LOGGER.info("[TM] {}: overspend — magicule {} → 0; total cost {} (hasInfiniteMaterials={} isInvulnerable={}{})",
                    operationLabel, playerMag, total, infMaterials, invulnerable,
                    (infMaterials || invulnerable)
                            ? " — TENSURA WILL SKIP SLEEP MODE"
                            : " — Sleep Mode will trigger naturally next tick");
            return new BulkCostDecision(true, playerMag, true);
        }
    }

    static void handleBulkSummon(ServerPlayer player, List<UUID> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return;
        if (requestedIds.size() > BULK_SUMMON_CAP) {
            requestedIds = requestedIds.subList(0, BULK_SUMMON_CAP);
        }

        RaceIdentitySavedData saved = RaceIdentitySavedData.get(player.serverLevel());

        // Resolve every requested ID into a (identity, target, cost) triple.
        // Skip anything that fails validation; report a single grouped advisory
        // at the end if anything was dropped.
        List<RaceIdentitySavedData.RaceIdentity> identities = new java.util.ArrayList<>();
        List<LivingEntity> targets = new java.util.ArrayList<>();
        List<Double> costs = new java.util.ArrayList<>();
        int skippedNotYours = 0, skippedSubordinate = 0, skippedUnloaded = 0, skippedReadFail = 0;

        for (UUID id : requestedIds) {
            RaceIdentitySavedData.RaceIdentity identity = saved.getById(id);
            if (identity == null) { skippedNotYours++; continue; }
            if (identity.ownerPlayerUUID == null
                    || !player.getUUID().equals(identity.ownerPlayerUUID)) {
                skippedNotYours++; continue;
            }
            if (identity.mode != RaceIdentitySavedData.Mode.IN_COLONY) {
                skippedSubordinate++; continue;
            }
            LivingEntity target = resolveTargetBody(player, identity);
            if (target == null) { skippedUnloaded++; continue; }
            ExistenceStorage targetExist = readExistence(target);
            if (targetExist == null) { skippedReadFail++; continue; }
            identities.add(identity);
            targets.add(target);
            costs.add(targetExist.getEP() * 0.25);
        }

        if (identities.isEmpty()) {
            sendAdvisoryNotice(player, "Nothing to summon in that selection.");
            return;
        }

        double total = 0.0;
        for (double c : costs) total += c;

        BulkCostDecision decision = decideBulkCost(player, total,
                "bulk-summon", "Not enough magicule to summon them all.");
        if (!decision.proceed()) return;

        // Queue every swap. Per-identity paid = proportional share of actualPaid,
        // so refund-on-abort returns the right fraction if a swap fails late.
        int n = identities.size();
        for (int i = 0; i < n; i++) {
            double share = total > 0.0 ? (costs.get(i) / total) * decision.actualPaid() : 0.0;
            Vec3 pos = bulkSummonPosFor(player, i, n);
            queueDelayedSwap(player, identities.get(i), targets.get(i), share, pos);
        }

        // Tail advisory — only if something was dropped AND we still summoned
        // a non-empty subset.
        int totalSkipped = skippedNotYours + skippedSubordinate + skippedUnloaded + skippedReadFail;
        if (totalSkipped > 0) {
            StringBuilder msg = new StringBuilder("Summoning ").append(n).append("; skipped ").append(totalSkipped).append(" (");
            boolean first = true;
            if (skippedSubordinate > 0) { msg.append(skippedSubordinate).append(" already at your side"); first = false; }
            if (skippedUnloaded > 0)    { if (!first) msg.append(", "); msg.append(skippedUnloaded).append(" unloaded"); first = false; }
            if (skippedNotYours > 0)    { if (!first) msg.append(", "); msg.append(skippedNotYours).append(" not yours"); first = false; }
            if (skippedReadFail > 0)    { if (!first) msg.append(", "); msg.append(skippedReadFail).append(" unreadable"); }
            msg.append(").");
            sendAdvisoryNotice(player, msg.toString());
        }
        LOGGER.info("[TM] bulk-summon: queued {} swaps (overspend={}, actualPaid={}, totalCost={})",
                n, decision.overspend(), decision.actualPaid(), total);
    }

    /**
     * Symmetric bulk-send (subordinates → colony). Each identity goes to its
     * OWN colony's town hall (the colonyId on the identity record), so a bulk
     * request can span colonies if the player has multiple. Cost band is the
     * same three-way decision as bulk-summon.
     *
     * Skips: identities that aren't yours, ones not in SUBORDINATE mode, mobs
     * not loaded, mobs whose evolved type is blocked (orc_lord / orc_disaster
     * via {@link Races#isBlocked}), and unreadable existence storage. The
     * blocked-orc filter mirrors the single-send gate in {@link #handleMenuAction}.
     */
    static void handleBulkSend(ServerPlayer player, List<UUID> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return;
        if (requestedIds.size() > BULK_SUMMON_CAP) {
            requestedIds = requestedIds.subList(0, BULK_SUMMON_CAP);
        }

        RaceIdentitySavedData saved = RaceIdentitySavedData.get(player.serverLevel());

        List<RaceIdentitySavedData.RaceIdentity> identities = new java.util.ArrayList<>();
        List<LivingEntity> targets = new java.util.ArrayList<>();
        List<Double> costs = new java.util.ArrayList<>();
        int skippedNotYours = 0, skippedInColony = 0, skippedUnloaded = 0,
                skippedReadFail = 0, skippedBlocked = 0;

        for (UUID id : requestedIds) {
            RaceIdentitySavedData.RaceIdentity identity = saved.getById(id);
            if (identity == null) { skippedNotYours++; continue; }
            if (identity.ownerPlayerUUID == null
                    || !player.getUUID().equals(identity.ownerPlayerUUID)) {
                skippedNotYours++; continue;
            }
            if (identity.mode != RaceIdentitySavedData.Mode.SUBORDINATE) {
                skippedInColony++; continue;
            }
            LivingEntity target = resolveTargetBody(player, identity);
            if (target == null) { skippedUnloaded++; continue; }
            // Blocked-orc gate — matches handleMenuAction's SUBORDINATE branch.
            if (Races.isBlocked(target.getType())) { skippedBlocked++; continue; }
            ExistenceStorage targetExist = readExistence(target);
            if (targetExist == null) { skippedReadFail++; continue; }
            identities.add(identity);
            targets.add(target);
            costs.add(targetExist.getEP() * 0.25);
        }

        if (identities.isEmpty()) {
            sendAdvisoryNotice(player, "Nothing to send in that selection.");
            return;
        }

        double total = 0.0;
        for (double c : costs) total += c;

        BulkCostDecision decision = decideBulkCost(player, total,
                "bulk-send", "Not enough magicule to send them all.");
        if (!decision.proceed()) return;

        // Queue each send. materializePos override is null — the SUBORDINATE
        // branch of queueDelayedSwap uses each identity's own colony town hall,
        // which is the correct destination per identity (potentially different
        // colonies for the same batch).
        int n = identities.size();
        for (int i = 0; i < n; i++) {
            double share = total > 0.0 ? (costs.get(i) / total) * decision.actualPaid() : 0.0;
            queueDelayedSwap(player, identities.get(i), targets.get(i), share, null);
        }

        int totalSkipped = skippedNotYours + skippedInColony + skippedUnloaded
                + skippedReadFail + skippedBlocked;
        if (totalSkipped > 0) {
            StringBuilder msg = new StringBuilder("Sending ").append(n).append("; skipped ").append(totalSkipped).append(" (");
            boolean first = true;
            if (skippedInColony > 0)   { msg.append(skippedInColony).append(" already in colony"); first = false; }
            if (skippedUnloaded > 0)   { if (!first) msg.append(", "); msg.append(skippedUnloaded).append(" unloaded"); first = false; }
            if (skippedBlocked > 0)    { if (!first) msg.append(", "); msg.append(skippedBlocked).append(" orc-lord/disaster"); first = false; }
            if (skippedNotYours > 0)   { if (!first) msg.append(", "); msg.append(skippedNotYours).append(" not yours"); first = false; }
            if (skippedReadFail > 0)   { if (!first) msg.append(", "); msg.append(skippedReadFail).append(" unreadable"); }
            msg.append(").");
            sendAdvisoryNotice(player, msg.toString());
        }
        LOGGER.info("[TM] bulk-send: queued {} swaps (overspend={}, actualPaid={}, totalCost={})",
                n, decision.overspend(), decision.actualPaid(), total);
    }

    /**
     * Stage 2a basic placement: fan N summon positions linearly across the
     * axis perpendicular to the player's horizontal look direction, centred on
     * the default summon position (3 blocks ahead). Step is 1.5 blocks so
     * adjacent bodies don't intersect. Stage 2b will replace this with the
     * 3×3 spaced grid pattern.
     */
    private static Vec3 bulkSummonPosFor(ServerPlayer player, int index, int total) {
        Vec3 center = summonMaterializePos(player);
        Vec3 look = player.getLookAngle();
        // Perpendicular on XZ plane, pointing to the player's right.
        double perpX = -look.z;
        double perpZ =  look.x;
        double perpLen = Math.sqrt(perpX * perpX + perpZ * perpZ);
        if (perpLen < 1e-6) {
            // Player looking straight up/down — use yaw fallback.
            float yawRad = (float) Math.toRadians(player.getYRot());
            perpX =  Math.cos(yawRad);
            perpZ =  Math.sin(yawRad);
            perpLen = 1.0;
        }
        perpX /= perpLen;
        perpZ /= perpLen;
        double step = 1.5;
        double offset = (index - (total - 1) / 2.0) * step;
        return new Vec3(center.x + perpX * offset, center.y, center.z + perpZ * offset);
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
                                         RaceIdentitySavedData.RaceIdentity identity,
                                         LivingEntity target,
                                         double magiculePaid) {
        queueDelayedSwap(player, identity, target, magiculePaid, null);
    }

    /**
     * @param materializePosOverride if non-null AND the action is a summon
     *     (IN_COLONY → at player), use this position instead of the default
     *     "3 blocks ahead in look direction." Used by bulk-summon to fan
     *     multiple bodies across distinct positions. Ignored for send.
     */
    private static void queueDelayedSwap(ServerPlayer player,
                                         RaceIdentitySavedData.RaceIdentity identity,
                                         LivingEntity target,
                                         double magiculePaid,
                                         Vec3 materializePosOverride) {
        ServerLevel level = player.serverLevel();
        long now = level.getServer().getTickCount();

        // Dissolve circle — at the live body's current position
        Vec3 dissolvePos = target.position();
        spawnSwapCircle(level, player, dissolvePos);

        // Materialize circle — destination depends on direction.
        // Captured here at queue time and locked into the PendingSwap so the
        // materialize body appears at the SAME spot as the circle, even if
        // the player turns or walks away during the delay.
        Vec3 materializePos;
        if (identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE) {
            // Send → materialize at the town hall.
            IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, level);
            if (colony != null && colony.getServerBuildingManager().hasTownHall()) {
                BlockPos thPos = colony.getServerBuildingManager().getTownHall().getPosition();
                materializePos = new Vec3(thPos.getX() + 0.5, thPos.getY(), thPos.getZ() + 0.5);
            } else {
                materializePos = dissolvePos; // fallback; the action would fail anyway
            }
        } else if (materializePosOverride != null) {
            materializePos = materializePosOverride;
        } else {
            // Summon → 3 blocks ahead in the player's look direction.
            materializePos = summonMaterializePos(player);
        }
        spawnSwapCircle(level, player, materializePos);

        // Dissolve-body sink: lower the live body SINK_DEPTH blocks over the
        // delay so it visually falls through its circle into the ground. Set
        // invulnerable to block suffocation damage. Lock X/Z so the body's
        // AI can't walk it out of the circle while the sink is in progress.
        target.setInvulnerable(true);
        double sinkStartY = target.getY();
        double sinkLockX  = target.getX();
        double sinkLockZ  = target.getZ();
        pendingVerticalMovements.add(new VerticalMovement(
                target.getUUID(), level.dimension(),
                sinkLockX, sinkLockZ,
                sinkStartY, sinkStartY - SINK_DEPTH,
                now, now + SWAP_DELAY_TICKS,
                false /* body gets discarded at execute; no need to clear invuln */));

        // Queue the swap to execute after the delay
        pendingSwaps.add(new PendingSwap(
                player.getUUID(),
                identity.identityId,
                identity.mode,
                identity.mobEntityUUID,
                magiculePaid,
                now + SWAP_DELAY_TICKS,
                materializePos));
        LOGGER.info("[TM] swap: queued for execution in {} ticks (paid={} magicule, dissolve sink {}→{})",
                SWAP_DELAY_TICKS, magiculePaid, sinkStartY, sinkStartY - SINK_DEPTH);
    }

    /**
     * For summon: 3 blocks in the HORIZONTAL direction the player is looking,
     * at the player's feet Y. We project to the XZ plane because if the
     * player is looking even slightly downward (natural pose when summoning),
     * a full 3D offset would put the materialize position below ground and
     * the circle would render inside a block (invisible).
     */
    private static Vec3 summonMaterializePos(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        double horizLenSqr = look.x * look.x + look.z * look.z;
        if (horizLenSqr < 1e-6) {
            // Looking nearly straight up or down — fall back to yaw direction.
            float yawRad = (float) Math.toRadians(player.getYRot());
            double dx = -Math.sin(yawRad) * 3.0;
            double dz =  Math.cos(yawRad) * 3.0;
            return new Vec3(player.getX() + dx, player.getY(), player.getZ() + dz);
        }
        double scale = 3.0 / Math.sqrt(horizLenSqr);
        return new Vec3(
                player.getX() + look.x * scale,
                player.getY(),
                player.getZ() + look.z * scale);
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
        double lockX = body.getX();
        double lockZ = body.getZ();
        body.setPos(lockX, surfaceY - RISE_START_OFFSET, lockZ);
        body.setInvulnerable(true);
        pendingVerticalMovements.add(new VerticalMovement(
                body.getUUID(), level.dimension(),
                lockX, lockZ,
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
        boolean added = level.addFreshEntity(circle);
        LOGGER.info("[TM] circle: spawned at ({}, {}, {}) caster={} added={}",
                String.format("%.2f", pos.x), String.format("%.2f", pos.y),
                String.format("%.2f", pos.z),
                caster.getName().getString(), added);
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

        // Stage 3a — envoy scheduler fires every ENVOY_SCHEDULER_PERIOD_TICKS
        // (currently 1 s). Cheap per call; the day-based gates inside the
        // scheduler use level.getGameTime() so the actual spawn cadence is
        // still "first eligible tick after a 3-day in-game gap." Frequent
        // ticking is what makes /time advancement immediately visible.
        if (now > 0 && now % ENVOY_SCHEDULER_PERIOD_TICKS == 0) {
            runEnvoyScheduler(server);
        }

        // Dawn-restock pass — refresh every named subordinate merchant's
        // trade stock at the start of each in-game day. Trade browsing is
        // available 24h (see {@link #handleOpenSubordinateTrade}), but
        // stock only refills here, so night browsing is read-only.
        tickDawnRestock(server);

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
        //
        // Each tick we lock X/Z to the queued position (prevents AI from
        // walking the body out of its circle), zero deltaMovement (no
        // residual velocity), and reset fallDistance (no fall damage
        // accumulating during the rise, which would hit the moment
        // invulnerability is cleared).
        java.util.Iterator<VerticalMovement> vit = pendingVerticalMovements.iterator();
        while (vit.hasNext()) {
            VerticalMovement m = vit.next();
            if (now < m.startTick()) continue;
            ServerLevel lvl = server.getLevel(m.dim());
            if (lvl == null) { vit.remove(); continue; }
            net.minecraft.world.entity.Entity e = lvl.getEntity(m.entityUUID());
            if (now >= m.endTick()) {
                if (e instanceof LivingEntity le && !le.isRemoved()) {
                    le.setPos(m.lockX(), m.targetY(), m.lockZ());
                    le.setDeltaMovement(Vec3.ZERO);
                    le.fallDistance = 0f;
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
            e.setPos(m.lockX(), y, m.lockZ());
            e.setDeltaMovement(Vec3.ZERO);
            if (e instanceof LivingEntity le) {
                le.fallDistance = 0f;
            }
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
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);
        RaceIdentitySavedData.RaceIdentity identity = saved.getById(pending.identityId());

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
        if (pending.expectedMode() == RaceIdentitySavedData.Mode.SUBORDINATE
                && pending.expectedGoblinUUID() != null
                && !pending.expectedGoblinUUID().equals(identity.mobEntityUUID)) {
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
        executeAction(player, identity, target, pending.materializePos());
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
                                                RaceIdentitySavedData.RaceIdentity identity,
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
     *  has already been passed (sufficient or confirmed-collapse).
     *  {@code materializePos} was locked at queue time and is used by summon
     *  to spawn the goblin at the originally-targeted position regardless of
     *  any player movement during the delay. */
    private static void executeAction(ServerPlayer player,
                                      RaceIdentitySavedData.RaceIdentity identity,
                                      LivingEntity target,
                                      Vec3 materializePos) {
        ServerLevel level = player.serverLevel();
        RaceIdentitySavedData saved = RaceIdentitySavedData.get(level);

        if (identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE) {
            if (!(target.level() instanceof ServerLevel goblinLevel)) {
                LOGGER.warn("[TM] action: goblin not on ServerLevel — aborting");
                return;
            }
            sendGoblinToColony(target, player, identity, goblinLevel, saved);
        } else {
            IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, level);
            if (colony == null) {
                sendAdvisoryNotice(player, "That citizen's colony no longer exists.");
                return;
            }
            ICitizenData cd = colony.getCitizenManager().getCivilian(identity.citizenId);
            if (cd == null) {
                sendAdvisoryNotice(player, "That citizen's record is missing.");
                return;
            }
            summonGoblin(player, level, saved, identity, colony, cd, materializePos);
        }
    }

    /** Resolve the live body for an identity. Returns null if not loaded. */
    private static LivingEntity resolveTargetBody(ServerPlayer player,
                                                  RaceIdentitySavedData.RaceIdentity identity) {
        if (identity.mode == RaceIdentitySavedData.Mode.SUBORDINATE) {
            return findLivingEntityAcrossLevels(player.getServer(), identity.mobEntityUUID);
        }
        IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, player.serverLevel());
        if (colony == null) return null;
        ICitizenData cd = colony.getCitizenManager().getCivilian(identity.citizenId);
        if (cd == null) return null;
        return cd.getEntity().orElse(null);
    }

    /** Lookup citizen name for advisory/prompt display. Falls back to "your citizen". */
    private static String resolveDisplayName(ServerPlayer player,
                                             RaceIdentitySavedData.RaceIdentity identity) {
        IColony colony = IColonyManager.getInstance().getColonyByWorld(identity.colonyId, player.serverLevel());
        if (colony == null) return "your citizen";
        ICitizenData cd = colony.getCitizenManager().getCivilian(identity.citizenId);
        return cd != null ? cd.getName() : "your citizen";
    }

    /**
     * Race-aware variant capture dispatcher. Each race resolves to its
     * race-specific capture; the dispatcher's return type is the sealed
     * {@link RaceVariantData} so the call site doesn't have to know
     * which concrete variant a race produces.
     */
    private static RaceVariantData captureRaceVariant(LivingEntity mob, Race race) {
        return switch (race) {
            case GOBLIN    -> captureGoblinVariant(mob);
            case ORC       -> captureOrcVariant(mob);
            case LIZARDMAN -> captureLizardmanVariant(mob);
            case DWARF     -> captureDwarfVariant(mob);
        };
    }

    /**
     * Race-aware variant APPLY dispatcher — inverse of
     * {@link #captureRaceVariant}. Stamps each appearance field from
     * {@code variant} back onto the live mob via the Tensura entity's
     * public setters.
     *
     * <p><b>Why this exists.</b> The summon path reconstructs the
     * wild mob via {@code EntityType.create(snapshot, level)} which
     * relies on Tensura's {@code readAdditionalSaveData} to restore
     * appearance from the NBT snapshot. In principle that round-trips
     * cleanly, but in practice the citizen's {@link RaceTag} is the
     * SOURCE OF TRUTH for what the player has been seeing in colony,
     * and the NBT snapshot is older (captured at first send and not
     * re-snapshotted on every appearance-touching interaction).
     *
     * <p>The user-visible symptom of NOT applying this: the wild mob
     * comes back with the appearance it had at FIRST send, not the
     * appearance the citizen body had right before summon — so any
     * variant drift between snapshot and the citizen's tag manifests
     * as a "skin doesn't match" complaint.
     *
     * <p>Calling this after {@code EntityType.create} but BEFORE
     * {@code addFreshEntity} guarantees the wild mob's appearance
     * matches the citizen's RaceTag exactly — that's what the player
     * watched walking around the colony.
     */
    private static void applyVariantToMob(LivingEntity mob, RaceTag tag) {
        try {
            switch (tag.variant()) {
                case GoblinVariantData g    -> applyGoblinVariant(mob, g);
                case OrcVariantData o       -> applyOrcVariant(mob, o);
                case LizardmanVariantData l -> applyLizardmanVariant(mob, l);
                case DwarfVariantData d     -> applyDwarfVariant(mob, d);
            }
        } catch (Throwable t) {
            LOGGER.warn("[TM] variant apply: failed for entity {} (race={}) — leaving NBT-restored values",
                    mob.getUUID(), tag.race(), t);
        }
    }

    private static void applyGoblinVariant(LivingEntity mob, GoblinVariantData v) {
        if (!(mob instanceof io.github.manasmods.tensura.entity.monster.GoblinEntity g)) return;
        g.setGender(v.gender());
        g.setSkin(v.skin());
        g.setFace(v.face());
        g.setHair(v.hair());
        g.setHairColor(v.hairColor());
        g.setHead(v.head());
        g.setHeadColor(v.headColor());
        g.setTop(v.top());
        g.setTopColor(v.topColor());
        g.setBottom(v.bottom());
        g.setBottomColor(v.bottomColor());
        g.setBandages(v.bandages());
        // evolutionState — restored by NBT (no public setter). Variant
        // capture re-records it on every send so it stays consistent.
    }

    private static void applyOrcVariant(LivingEntity mob, OrcVariantData v) {
        if (!(mob instanceof io.github.manasmods.tensura.entity.monster.OrcEntity o)) return;
        try {
            o.setVariant(io.github.manasmods.tensura.entity.variant.OrcVariant.byId(v.variantId()));
        } catch (Throwable ignored) { /* enum id out of range — keep default */ }
        o.setNeck(v.neckId());
        o.setNeckColor(v.neckColor());
        o.setTop(v.topId());
        o.setTopColor(v.topColor());
        o.setBottomColor(v.bottomColor());
        o.setBeltColor(v.beltColor());
        o.setBootsColor(v.bootsColor());
        o.setBandage(v.bandage());
        o.setNecklace(v.necklace());
    }

    private static void applyLizardmanVariant(LivingEntity mob, LizardmanVariantData v) {
        if (!(mob instanceof io.github.manasmods.tensura.entity.monster.LizardmanEntity l)) return;
        try {
            l.setVariant(io.github.manasmods.tensura.entity.variant.LizardmanVariant.byId(v.variantId()));
        } catch (Throwable ignored) { /* enum id out of range — keep default */ }
        l.setHair(v.hairId());
        l.setHairColor(v.hairColor());
        l.setTop(v.topId());
        l.setTopColor(v.topColor());
        l.setBottomColor(v.bottomColor());
        l.setBandage(v.bandage());
    }

    private static void applyDwarfVariant(LivingEntity mob, DwarfVariantData v) {
        if (!(mob instanceof io.github.manasmods.tensura.entity.human.DwarfEntity d)) return;
        d.setGender(v.gender());
        d.setSkin(v.skin());
        d.setFace(v.face());
        d.setScar(v.scar());
        d.setHair(v.hair());
        d.setFacialHair(v.facialHair());
        d.setTop(v.top());
        d.setTopColor(v.topColor());
        d.setBottom(v.bottom());
        d.setBottomColor(v.bottomColor());
        d.setFeet(v.feet());
        d.setFeetColor(v.feetColor());
        d.setHairColor(v.hairColor());
        // Dwarf has SCALE in variant data — restore it on the wild mob
        // too. captureDwarfVariant reads Tensura's natural per-mob
        // SCALE attribute; without re-applying here, the summoned
        // dwarf would render at MC's default 1.0 if NBT lost it.
        net.minecraft.world.entity.ai.attributes.AttributeInstance scaleAttr =
                d.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
        if (scaleAttr != null) {
            scaleAttr.setBaseValue(v.scale());
        }
    }

    /**
     * Dwarf-specific variant capture. 13 fields read off the live
     * {@code DwarfEntity}: 9 enum-typed (gender, skin, face, hair,
     * facialHair, top, bottom, feet — plus scar as raw int) and 4 colour
     * ints (hair / top / bottom / feet).
     *
     * <p>Sentinel guard: the 8 enum getters all go through {@code
     * DwarfVariant.<X>.byId(raw % length)}. The investigation found no
     * -1 sentinels in dwarf, but we wrap each enum getter in
     * {@link #safeOrcEnumId} all the same — identical defensive
     * protection to goblin / lizardman.
     */
    private static DwarfVariantData captureDwarfVariant(LivingEntity mob) {
        if (!(mob instanceof io.github.manasmods.tensura.entity.human.DwarfEntity d)) {
            LOGGER.warn("[TM] dwarf variant capture: entity {} is not a DwarfEntity — using DEFAULT",
                    mob.getUUID());
            return DwarfVariantData.DEFAULT;
        }
        // Capture the dwarf's randomised SCALE attribute. Tensura sets this
        // in finalizeSpawn to 0.7 + rand³ × 0.3 (biased low) for normal
        // dwarves, or 1.0 for royal guards. Storing it lets the citizen
        // body render at the same size as the wild dwarf it was named from.
        AttributeInstance sourceScale = d.getAttribute(Attributes.SCALE);
        float capturedScale = sourceScale != null
                ? (float) sourceScale.getValue()
                : 0.9375f;
        return new DwarfVariantData(
                safeOrcEnumId(() -> d.getGender().getId()),
                safeOrcEnumId(() -> d.getSkin().getId()),
                safeOrcEnumId(() -> d.getFace().getId()),
                d.getScar(),                              // raw int — no enum
                safeOrcEnumId(() -> d.getHair().getId()),
                safeOrcEnumId(() -> d.getFacialHair().getId()),
                safeOrcEnumId(() -> d.getTop().getId()),
                safeOrcEnumId(() -> d.getBottom().getId()),
                safeOrcEnumId(() -> d.getFeet().getId()),
                d.getHairColor(),
                d.getTopColor(),
                d.getBottomColor(),
                d.getFeetColor(),
                capturedScale
        );
    }

    /**
     * Lizardman-specific variant capture. 9 fields read off the live
     * {@code LizardmanEntity}: variant (skin/scale type), hair (id + colour),
     * top (id + colour), bottom colour, bandage flag, plus the evolution
     * state and timer.
     *
     * Sentinel guard: the three enum-typed getters
     * ({@code getVariant}, {@code getHair}, {@code getTop}) all go through
     * {@code LizardmanVariant.<X>.byId(raw % length)}. The investigation
     * found no -1 sentinels in lizardman, but the same defensive
     * try/catch shape as orc costs nothing and protects against future
     * additions.
     */
    private static LizardmanVariantData captureLizardmanVariant(LivingEntity mob) {
        if (!(mob instanceof io.github.manasmods.tensura.entity.monster.LizardmanEntity l)) {
            LOGGER.warn("[TM] lizardman variant capture: entity {} is not a LizardmanEntity — using DEFAULT",
                    mob.getUUID());
            return LizardmanVariantData.DEFAULT;
        }
        return new LizardmanVariantData(
                safeOrcEnumId(() -> l.getVariant().getId()),
                safeOrcEnumId(() -> l.getHair().getId()),
                l.getHairColor(),
                safeOrcEnumId(() -> l.getTop().getId()),
                l.getTopColor(),
                l.getBottomColor(),
                l.hasBandage(),
                l.getCurrentEvolutionState(),
                l.getEvolving()
        );
    }

    /**
     * Orc-specific variant capture. 10 fields read off the live OrcEntity
     * plus the 2 evolution counters.
     *
     * Sentinel guard: the three enum-typed getters
     * ({@code getVariant}, {@code getNeck}, {@code getTop}) all go through
     * {@code OrcVariant.<X>.byId(raw % length)} — same crash shape as
     * Tensura's goblin {@code Head.byId}. We wrap each in try/catch with
     * a {@code 0} fallback ({@code OrcVariant.HAM} / {@code Neck.EMPTY}
     * / {@code Top.SHIRT_LONG}). The other 9 fields (5 colors + 2 bools
     * + 2 evolution ints) read directly via public getters with no crash
     * risk.
     */
    private static OrcVariantData captureOrcVariant(LivingEntity mob) {
        if (!(mob instanceof io.github.manasmods.tensura.entity.monster.OrcEntity o)) {
            LOGGER.warn("[TM] orc variant capture: entity {} is not an OrcEntity — using DEFAULT",
                    mob.getUUID());
            return OrcVariantData.DEFAULT;
        }
        return new OrcVariantData(
                safeOrcEnumId(() -> o.getVariant().getId()),
                safeOrcEnumId(() -> o.getNeck().getId()),
                o.getNeckColor(),
                safeOrcEnumId(() -> o.getTop().getId()),
                o.getTopColor(),
                o.getBottomColor(),
                o.getBeltColor(),
                o.getBootsColor(),
                o.hasBandage(),
                o.hasNecklace(),
                o.getCurrentEvolutionState(),
                o.getEvolving()
        );
    }

    /** Try/catch wrapper for the three crash-prone {@code OrcVariant.byId}
     *  paths. Same shape as the goblin {@code readTopId / readBottomId}
     *  helpers — accessor is private in Tensura so we can't reach the raw
     *  int, and the public enum getter throws AIOOBE on negative ids. */
    private static int safeOrcEnumId(java.util.function.IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Goblin-specific variant capture. All Tensura variant enums expose
     * {@code getId()} matching what {@code byId(int)} consumes — the
     * round-trip ID is what the wire format encodes (NOT ordinal, in case
     * Tensura ever reorders enum values).
     *
     * Sentinel guard: Tensura uses {@code -1} as a "no accessory" sentinel
     * for HEAD / TOP / BOTTOM. Their own {@code GoblinLayer$Head.render}
     * checks {@code headId == -1} before reading; their {@code Head.byId}
     * doesn't — it does {@code BY_ID[id % BY_ID.length]} which throws
     * AIOOBE for id=-1 (Java's modulo: {@code -1 % 2 == -1}). Calling
     * {@code g.getHead()} on a goblin with HEAD=-1 crashes inside Tensura.
     *
     * We guard each of head/top/bottom by reading the raw synced int
     * directly when its accessor is public ({@code HEAD} is) and only
     * invoking the enum getter when the value is non-negative. For
     * private accessors ({@code TOP}, {@code BOTTOM}) we fall back to
     * try/catch — the user-facing rule of "don't use exceptions for
     * control flow" is relaxed only where the raw value isn't reachable
     * without reflection. The {@code -1} sentinel round-trips correctly
     * into {@link GoblinVariantData} and the renderer already skips
     * those layers via {@code GoblinTextures.head/top/bottom} guards.
     */
    private static GoblinVariantData captureGoblinVariant(LivingEntity mob) {
        if (!(mob instanceof GoblinEntity g)) {
            // Should not happen — the dispatcher guards by race. Fall
            // back to defaults rather than NPE.
            LOGGER.warn("[TM] goblin variant capture: entity {} is not a GoblinEntity — using DEFAULT",
                    mob.getUUID());
            return GoblinVariantData.DEFAULT;
        }
        return new GoblinVariantData(
                g.getGender().getId(),
                g.getSkin().getId(),
                g.getFace().getId(),
                g.getHair().getId(),
                g.getHairColor(),
                readHeadId(g),
                g.getHeadColor(),
                readTopId(g),
                g.getTopColor(),
                readBottomId(g),
                g.getBottomColor(),
                g.hasBandages(),
                g.getCurrentEvolutionState()
        );
    }

    // ------------------------------------------------------------------
    // Sentinel-safe readers for goblin head/top/bottom variant IDs.
    //
    // Each returns the raw int stored on the entity (including the -1
    // "no accessory" sentinel) without ever invoking Tensura's
    // crash-prone enum getter on a negative value.
    // ------------------------------------------------------------------

    /**
     * HEAD has a public {@code EntityDataAccessor}, so we read the raw
     * synced int directly and skip the {@code Head.byId} call entirely
     * when negative — no exception path used.
     */
    private static int readHeadId(GoblinEntity g) {
        int raw = g.getEntityData().get(GoblinEntity.HEAD);
        return raw >= 0 ? raw : -1;
    }

    /**
     * TOP's {@code EntityDataAccessor} is private in Tensura. Without
     * reflection we can't read the raw int, so we wrap {@code getTop()}
     * in a try/catch — this is the explicit fallback the rule allows
     * for the "raw value not cleanly accessible" case. Any throwable
     * (AIOOBE from the {@code -1} sentinel, or anything else surprising)
     * collapses to {@code -1}, which the renderer treats as "no top".
     */
    private static int readTopId(GoblinEntity g) {
        try {
            return g.getTop().getId();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** BOTTOM's accessor is private too — same try/catch fallback as TOP. */
    private static int readBottomId(GoblinEntity g) {
        try {
            return g.getBottom().getId();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Read ExistenceStorage off any LivingEntity via the ManasCore mixin. */
    private static ExistenceStorage readExistence(LivingEntity entity) {
        if (entity instanceof StorageHolder holder) {
            return holder.manasCore$getStorage(ExistenceStorage.getKey());
        }
        return null;
    }

    /**
     * Roster-row EP read. Reuses the same body-resolution + IExistence read
     * the cost gate uses, so the EP shown in the roster matches what the cost
     * gate will charge against. Returns 0.0 when the body isn't loaded or
     * the existence storage can't be read — deterministic for sorting.
     */
    static double readEPForRoster(ServerPlayer player,
                                  RaceIdentitySavedData.RaceIdentity identity) {
        LivingEntity body = resolveTargetBody(player, identity);
        if (body == null) return 0.0;
        ExistenceStorage exist = readExistence(body);
        return exist == null ? 0.0 : exist.getEP();
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

    /**
     * Per-race body-scale adjustment via vanilla {@link Attributes#SCALE}.
     *
     * <p><b>Currently a no-op for all races except defensive 1.0 forcing.</b>
     * The original plan was to set SCALE = 0.5 for dwarf, but
     * {@link LivingEntityRenderer#render} applies a hardcoded {@code -1.5}
     * Y translation AFTER {@code scale()} fires — that translate happens
     * in scaled space, so SCALE=0.5 places the rendered model -0.75 below
     * the entity origin (half-sunken in the ground), AND MineColonies'
     * citizen pathfinding may not respect the attribute. Both problems
     * vanish if we just hardcode the visual scale in the renderer and
     * leave the hitbox / pathing at the regular citizen dimensions.
     *
     * <p>The method is retained so a future race that DOES want hitbox
     * scaling (e.g. a giant orc tier) has a centralised hook.
     */
    private static void applyRaceScaleAttribute(LivingEntity citizen, Race race) {
        AttributeInstance scale = citizen.getAttribute(Attributes.SCALE);
        if (scale == null) return;
        // All races: explicit 1.0 so a Tensura tier-scale doesn't leak onto
        // the destination citizen body.
        if (scale.getBaseValue() != 1.0) {
            scale.setBaseValue(1.0);
        }
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
    /**
     * Push the citizen inventory's equipment (armor slots + held items) onto
     * the freshly-spawned citizen ENTITY's equipment slots, so the body
     * actually renders with the items. Without this, items sit in the
     * backing {@code InventoryCitizen} but the entity's
     * {@code getItemBySlot(slot)} returns empty and the
     * {@code HumanoidArmorLayer}/{@code ItemInHandLayer} render nothing.
     *
     * Run AFTER {@code transferGoblinItemsToCitizen} (which populates the
     * inventory) and on the just-spawned {@code AbstractCivilianEntity}.
     * MineColonies AI manages equipment ongoing, but on first spawn the
     * entity slots are empty — this is the bridge.
     */
    private static void applyEquipmentFromInventory(
            com.minecolonies.api.entity.citizen.AbstractCivilianEntity body,
            InventoryCitizen inv) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = inv.getArmorInSlot(slot);
            body.setItemSlot(slot, armor == null ? ItemStack.EMPTY : armor.copy());
        }
        ItemStack mainHand = inv.getHeldItem(InteractionHand.MAIN_HAND);
        ItemStack offHand  = inv.getHeldItem(InteractionHand.OFF_HAND);
        body.setItemSlot(EquipmentSlot.MAINHAND,
                mainHand == null ? ItemStack.EMPTY : mainHand.copy());
        body.setItemSlot(EquipmentSlot.OFFHAND,
                offHand == null ? ItemStack.EMPTY : offHand.copy());
    }

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
     * All advisory call sites route through this helper for consistent styling.
     */
    static void sendAdvisoryNotice(Player player, String text) {
        sendAdvisoryNotice(player, (Component) Component.literal(text));
    }

    /** Component overload — applies the same green-italic style to the
     *  passed Component (used for translatable advisories). */
    static void sendAdvisoryNotice(Player player, Component msg) {
        Component styled = Component.empty()
                .append(msg.copy().withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
        player.sendSystemMessage(styled);
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
            // Register the hostility gamerule. GameRules.register mutates a
            // static map and must happen on the main thread before any world
            // loads — enqueueWork gives us both. Default TRUE preserves the
            // current behaviour for existing worlds.
            RULE_HOSTILE_TO_CITIZENS = net.minecraft.world.level.GameRules.register(
                    "tensuraHostileToCitizens",
                    net.minecraft.world.level.GameRules.Category.MOBS,
                    net.minecraft.world.level.GameRules.BooleanValue.create(true));
            LOGGER.info("[TM] gamerule 'tensuraHostileToCitizens' registered (default true)");

            RULE_MAX_NON_COLONIST_ENVOYS = net.minecraft.world.level.GameRules.register(
                    "tensuraMaxNonColonistEnvoys",
                    net.minecraft.world.level.GameRules.Category.PLAYER,
                    net.minecraft.world.level.GameRules.IntegerValue.create(4));
            LOGGER.info("[TM] gamerule 'tensuraMaxNonColonistEnvoys' registered (default 4)");

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

            // Stage B — race-aware population spawn. Intercept the
            // INITIAL citizen-add to substitute the colony's chosen
            // race-mob. Colonies with no race configured are left to
            // vanilla MineColonies behaviour.
            IMinecoloniesAPI.getInstance().getEventBus()
                    .subscribe(CitizenAddedModEvent.class, this::onCitizenAdded);

            // Stage B — clean stale entries from ColonyRaceConfig when
            // a colony is deleted so a future re-creation under the
            // same id doesn't inherit the prior race.
            IMinecoloniesAPI.getInstance().getEventBus()
                    .subscribe(ColonyDeletedModEvent.class, this::onColonyDeleted);
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

    // ------------------------------------------------------------------
    // Stage F1 — entity tracking sync
    //
    // Fires server-side when a player begins tracking an entity (just
    // entered range, just joined the world, just changed dimension to
    // this one). If the entity is a goblin-tagged citizen, eagerly
    // unicast the tag to that player so they can see the goblin
    // appearance on the very first render frame — no scheduler delay,
    // no enqueueWork bounce, to avoid the one-frame default-colonist
    // flicker called out in the investigation report.
    // ------------------------------------------------------------------
    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof AbstractEntityCitizen citizen)) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        // Race-tag re-sync (worker races).
        if (citizen.hasData(Attachments.RACE_TAG.get())) {
            RaceTag tag = citizen.getData(Attachments.RACE_TAG.get());
            PacketDistributor.sendToPlayer(sp,
                    Networking.SyncRaceTagPayload.of(citizen.getUUID(), tag));
            LOGGER.info("[TM] tracking: re-synced race tag to {} for citizen entity {} (identity {})",
                    sp.getName().getString(), citizen.getUUID(), tag.identityId());
        }
    }
}
