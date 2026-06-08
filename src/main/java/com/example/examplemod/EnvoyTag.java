package com.example.examplemod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Marks an entity as an envoy from / for a specific colony.
 *
 * Persists across save/load via the attachment's NBT serializer (same
 * mechanism as {@link RaceTag}). Four fields:
 *
 * <ul>
 *   <li>{@link #colonyId} — the MineColonies colony id the envoy is
 *       attached to. Diplomacy mutations (accept → add member) target
 *       this colony.</li>
 *   <li>{@link #member} — the {@link ColonyMember} that will be added to
 *       the colony's composition set on accept.</li>
 *   <li>{@link #state} — lifecycle state. ALIVE means the envoy is waiting
 *       on a player response; ACCEPTED / DECLINED are terminal states
 *       set by the dialogue handler.</li>
 *   <li>{@link #conditionMask} — Stage J2 bitmask of {@link EnvoyCondition}
 *       values that were SATISFIED at the moment this envoy spawned.
 *       Drives condition-dependent dialogue copy. May be {@code 0} on
 *       envoys from legacy saves (predating Stage J2) — the dialogue
 *       composer falls back to base-only when the mask is empty.</li>
 * </ul>
 *
 * {@code entity.hasData(Attachments.ENVOY_TAG.get())} is the universal
 * presence check, valid for all envoy entity types (GoblinEntity,
 * OrcEntity, vanilla Villager).
 */
public record EnvoyTag(int colonyId, ColonyMember member, State state, byte conditionMask) {

    /** Convenience constructor for ALIVE state with no condition mask —
     *  callers that don't have a captured condition set yet (legacy code
     *  paths, debug spawns) can omit the mask without worrying about
     *  backward compat. */
    public EnvoyTag(int colonyId, ColonyMember member, State state) {
        this(colonyId, member, state, (byte) 0);
    }

    public enum State {
        ALIVE(0),
        ACCEPTED(1),
        DECLINED(2);

        private final int id;
        State(int id) { this.id = id; }
        public int getId() { return id; }
        public static State byId(int id) {
            for (State s : values()) if (s.id == id) return s;
            return ALIVE;
        }
    }

    public EnvoyTag withState(State next) {
        return new EnvoyTag(this.colonyId, this.member, next, this.conditionMask);
    }

    /** @return the condition set decoded from {@link #conditionMask}. */
    public java.util.EnumSet<EnvoyCondition> conditions() {
        return EnvoyCondition.fromMask(this.conditionMask);
    }

    public static final IAttachmentSerializer<CompoundTag, EnvoyTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public EnvoyTag read(IAttachmentHolder holder, CompoundTag tag,
                                     HolderLookup.Provider registries) {
                    int colonyId = tag.getInt("colonyId");
                    ColonyMember member = ColonyMember.byId(tag.getByte("member") & 0xFF);
                    State state = tag.contains("state", Tag.TAG_BYTE)
                            ? State.byId(tag.getByte("state") & 0xFF)
                            : State.ALIVE;
                    // condMask absent on legacy / pre-Stage-J2 envoys → 0.
                    // Dialogue composer treats 0 as "no captured conditions,
                    // fall back to base-only" so legacy envoys still produce
                    // a readable greeting.
                    byte mask = tag.contains("condMask", Tag.TAG_BYTE)
                            ? tag.getByte("condMask")
                            : 0;
                    return new EnvoyTag(colonyId, member, state, mask);
                }

                @Override
                public CompoundTag write(EnvoyTag value, HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("colonyId", value.colonyId);
                    tag.putByte("member", (byte) value.member.getId());
                    tag.putByte("state", (byte) value.state.getId());
                    tag.putByte("condMask", value.conditionMask);
                    return tag;
                }
            };
}
