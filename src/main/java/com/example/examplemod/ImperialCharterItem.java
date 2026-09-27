package com.example.examplemod;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * The Imperial Garrison Charter — the Eastern Empire's Covenant reward
 * (2026-09-26, developer design).
 *
 * <p>Canon: the Nasca Namrium Ulmeria Eastern Empire is the world's great
 * military power. Right-click the charter while standing inside a colony you
 * own: the charter is used up and that colony's GUARDS (knights, archers,
 * druids — any MineColonies guard job) are permanently drilled to imperial
 * standard:
 * <ul>
 *   <li>+{@value #HEALTH_BONUS_PERCENT}% max health,</li>
 *   <li>+{@value #ARMOR_BONUS} armor and +{@value #TOUGHNESS_BONUS} armor
 *       toughness,</li>
 *   <li>+{@value #KNOCKBACK_RESIST} knockback resistance,</li>
 *   <li>×{@value #DAMAGE_MULTIPLIER} damage dealt (melee, arrows and druid
 *       potions alike — done in a damage event, because only the knight's
 *       melee reads the ATTACK_DAMAGE attribute; archer and druid damage are
 *       computed by MineColonies' own AI).</li>
 * </ul>
 *
 * <p>Why a re-apply loop instead of "buff once": MineColonies rebuilds a
 * citizen's entity from its CitizenData whenever it respawns or its chunk
 * reloads, and a guard can be fired or hired at any time. So every
 * {@value #REAPPLY_SECONDS} s we walk each chartered colony's citizens and make
 * sure every guard has our modifiers and no one else does. Our modifiers use
 * their own ids, so they never clash with MineColonies' own guard-level health
 * bonus. The health modifier also doubles as the "is a chartered guard" flag
 * the damage event checks.
 *
 * <p>One charter per colony; the chartered colony list lives in
 * {@link Data} (overworld SavedData).
 */
public class ImperialCharterItem extends Item {

    static final int HEALTH_BONUS_PERCENT = 50;
    static final double ARMOR_BONUS = 4.0;
    static final double TOUGHNESS_BONUS = 2.0;
    static final double KNOCKBACK_RESIST = 0.5;
    static final float DAMAGE_MULTIPLIER = 1.25f;
    static final int REAPPLY_SECONDS = 5;

    private static final ResourceLocation HEALTH_ID = id("imperial_charter_health");
    private static final ResourceLocation ARMOR_ID = id("imperial_charter_armor");
    private static final ResourceLocation TOUGHNESS_ID = id("imperial_charter_toughness");
    private static final ResourceLocation KNOCKBACK_ID = id("imperial_charter_knockback");

    public ImperialCharterItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC).fireResistant());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, path);
    }

    // ------------------------------------------------------------------
    // Using the charter
    // ------------------------------------------------------------------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.success(stack);
        }
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(serverLevel, sp.blockPosition());
        if (colony == null || !sp.getUUID().equals(colony.getPermissions().getOwner())) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.imperial_charter.not_in_colony")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }
        Data data = Data.get(serverLevel);
        if (data.isChartered(serverLevel.dimension(), colony.getID())) {
            sp.displayClientMessage(Component.translatable("item.tensura_minecolonies.imperial_charter.already")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }

        data.charter(serverLevel.dimension(), colony.getID());
        int guards = applyToColony(colony);
        if (!sp.getAbilities().instabuild) stack.shrink(1);

        serverLevel.playSound(null, sp.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 1.5f, 1.2f);
        serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, sp.getX(), sp.getY() + 1.0, sp.getZ(),
                40, 0.8, 1.0, 0.8, 0.3);
        sp.sendSystemMessage(Component.translatable("item.tensura_minecolonies.imperial_charter.signed",
                colony.getName(), guards).withStyle(ChatFormatting.GOLD));
        return InteractionResultHolder.success(stack);
    }

    // ------------------------------------------------------------------
    // Keeping the drill applied — every REAPPLY_SECONDS from ExampleMod
    // ------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        Data data = Data.get(overworld);
        if (data.chartered.isEmpty()) return;
        for (String key : data.chartered) {
            int split = key.lastIndexOf('|');
            if (split < 0) continue;
            ResourceLocation dimId = ResourceLocation.tryParse(key.substring(0, split));
            if (dimId == null) continue;
            ServerLevel level = null;
            for (ServerLevel l : server.getAllLevels()) {
                if (l.dimension().location().equals(dimId)) { level = l; break; }
            }
            if (level == null) continue;
            int colonyId;
            try { colonyId = Integer.parseInt(key.substring(split + 1)); } catch (NumberFormatException ex) { continue; }
            IColony colony = IColonyManager.getInstance().getColonyByWorld(colonyId, level);
            if (colony != null) applyToColony(colony);
        }
    }

    /** Buff every guard in the colony, strip anyone who isn't one. Returns the guard count. */
    private static int applyToColony(IColony colony) {
        int guards = 0;
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            boolean isGuard = citizen.getJob() instanceof AbstractJobGuard<?>;
            if (isGuard) guards++;
            AbstractEntityCitizen body = citizen.getEntity().orElse(null);
            if (body == null) continue;
            if (isGuard) addBuffs(body); else removeBuffs(body);
        }
        return guards;
    }

    private static void addBuffs(AbstractEntityCitizen body) {
        boolean newlyBuffed = !hasCharter(body);
        set(body, Attributes.MAX_HEALTH, HEALTH_ID, HEALTH_BONUS_PERCENT / 100.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        set(body, Attributes.ARMOR, ARMOR_ID, ARMOR_BONUS, AttributeModifier.Operation.ADD_VALUE);
        set(body, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID, TOUGHNESS_BONUS, AttributeModifier.Operation.ADD_VALUE);
        set(body, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID, KNOCKBACK_RESIST, AttributeModifier.Operation.ADD_VALUE);
        if (newlyBuffed) body.setHealth(body.getMaxHealth());   // fresh recruits start at the new full health
    }

    private static void removeBuffs(AbstractEntityCitizen body) {
        if (!hasCharter(body)) return;
        remove(body, Attributes.MAX_HEALTH, HEALTH_ID);
        remove(body, Attributes.ARMOR, ARMOR_ID);
        remove(body, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID);
        remove(body, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        if (body.getHealth() > body.getMaxHealth()) body.setHealth(body.getMaxHealth());
    }

    private static void set(AbstractEntityCitizen body, Holder<Attribute> attr, ResourceLocation id,
                            double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = body.getAttribute(attr);
        if (inst != null && !inst.hasModifier(id)) {
            inst.addTransientModifier(new AttributeModifier(id, amount, op));
        }
    }

    private static void remove(AbstractEntityCitizen body, Holder<Attribute> attr, ResourceLocation id) {
        AttributeInstance inst = body.getAttribute(attr);
        if (inst != null) inst.removeModifier(id);
    }

    /** True when this citizen body currently carries the charter's drill. */
    static boolean hasCharter(Entity e) {
        if (!(e instanceof AbstractEntityCitizen citizen)) return false;
        AttributeInstance inst = citizen.getAttribute(Attributes.MAX_HEALTH);
        return inst != null && inst.hasModifier(HEALTH_ID);
    }

    /** Chartered guards hit harder — melee, arrows (the arrow's owner), druid potions. */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (hasCharter(event.getSource().getEntity())) {
            event.setAmount(event.getAmount() * DAMAGE_MULTIPLIER);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tensura_minecolonies.imperial_charter.desc")
                .withStyle(ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------------
    // Which colonies hold a charter (overworld SavedData)
    // ------------------------------------------------------------------

    /** NBT: {@code chartered: ["minecraft:overworld|3", …]} — dimension + colony id,
     *  because MineColonies colony ids are only unique within a dimension. */
    static final class Data extends SavedData {
        static final String DATA_KEY = "tensura_minecolonies_imperial_charter";

        final Set<String> chartered = new HashSet<>();

        static Data get(ServerLevel anyLevel) {
            return anyLevel.getServer().overworld().getDataStorage().computeIfAbsent(
                    new SavedData.Factory<>(Data::new, Data::load), DATA_KEY);
        }

        private static String key(net.minecraft.resources.ResourceKey<Level> dim, int colonyId) {
            return dim.location() + "|" + colonyId;
        }

        boolean isChartered(net.minecraft.resources.ResourceKey<Level> dim, int colonyId) {
            return chartered.contains(key(dim, colonyId));
        }

        void charter(net.minecraft.resources.ResourceKey<Level> dim, int colonyId) {
            if (chartered.add(key(dim, colonyId))) setDirty();
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            for (String k : chartered) list.add(net.minecraft.nbt.StringTag.valueOf(k));
            tag.put("chartered", list);
            return tag;
        }

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            ListTag list = tag.getList("chartered", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) data.chartered.add(list.getString(i));
            return data;
        }
    }
}
