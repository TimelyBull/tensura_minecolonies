package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.UUID;

/**
 * Server-side data attached to a MineColonies {@code AbstractEntityCitizen}
 * marking it as a race-identity citizen.
 *
 * Carries the {@link Race} discriminator plus a race-specific
 * {@link RaceVariantData} (sealed: {@link GoblinVariantData} or
 * {@link OrcVariantData}). The race byte on the wire / in NBT picks
 * the decoder via {@link #fromWire} / the attachment serializer.
 *
 * Wire / NBT format: identityId UUID + race byte + variant byte[]
 * (race-specific encoding inside). Legacy tags (pre-race-field) decode
 * as {@link Race#GOBLIN} via the byId fallback.
 */
public record RaceTag(UUID identityId, Race race, RaceVariantData variant, String profession) {

    /** 3-arg form — no profession ("" = jobless). Keeps every existing call
     *  site working; only the dwarf-profession render path sets a profession. */
    public RaceTag(UUID identityId, Race race, RaceVariantData variant) {
        this(identityId, race, variant, "");
    }

    /** Convenience for goblin construction with default race=GOBLIN. */
    public static RaceTag of(UUID identityId, GoblinVariantData variant) {
        return new RaceTag(identityId, Race.GOBLIN, variant);
    }

    public static RaceTag of(UUID identityId, Race race, RaceVariantData variant) {
        return new RaceTag(identityId, race, variant);
    }

    /** A copy carrying the given villager-profession registry name (e.g.
     *  {@code "minecraft:butcher"}; {@code ""} = none). Drives the dwarf
     *  profession-clothes render so the citizen matches its subordinate form. */
    public RaceTag withProfession(String professionId) {
        return new RaceTag(identityId, race, variant, professionId == null ? "" : professionId);
    }

    /** Client-side construction from a decoded payload. Dispatches the
     *  byte[] through the race-specific decoder. */
    public static RaceTag fromWire(UUID identityId, int raceId, byte[] encodedVariant, String profession) {
        Race race = Race.byId(raceId);
        RaceVariantData variant = switch (race) {
            case GOBLIN    -> GoblinVariantData.decode(encodedVariant);
            case ORC       -> OrcVariantData.decode(encodedVariant);
            case LIZARDMAN -> LizardmanVariantData.decode(encodedVariant);
            case DWARF     -> DwarfVariantData.decode(encodedVariant);
        };
        return new RaceTag(identityId, race, variant, profession == null ? "" : profession);
    }

    /** Polymorphic encode via the sealed interface. */
    public byte[] encodeVariant() {
        return variant.encode();
    }

    /** Return a copy with a different race. Used by {@code /raceflip}.
     *  Re-encodes the variant through the OTHER race's default decode so
     *  the renderer doesn't try to interpret goblin bytes as orc fields
     *  or vice versa — the flip resets to that race's DEFAULT appearance. */
    public RaceTag withRace(Race newRace) {
        if (newRace == race) return this;
        RaceVariantData fresh = switch (newRace) {
            case GOBLIN    -> GoblinVariantData.DEFAULT;
            case ORC       -> OrcVariantData.DEFAULT;
            case LIZARDMAN -> LizardmanVariantData.DEFAULT;
            case DWARF     -> DwarfVariantData.DEFAULT;
        };
        return new RaceTag(identityId, newRace, fresh, profession);
    }

    /** NBT serializer — same shape, dispatches on the race byte. */
    public static final IAttachmentSerializer<CompoundTag, RaceTag> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public RaceTag read(IAttachmentHolder holder, CompoundTag tag,
                                    HolderLookup.Provider registries) {
                    UUID id = tag.getUUID("identityId");
                    Race race = tag.contains("race")
                            ? Race.byId(tag.getByte("race") & 0xFF)
                            : Race.GOBLIN;
                    byte[] variantBytes = tag.contains("variant")
                            ? tag.getByteArray("variant")
                            : new byte[0];
                    RaceVariantData variant = switch (race) {
                        case GOBLIN    -> GoblinVariantData.decode(variantBytes);
                        case ORC       -> OrcVariantData.decode(variantBytes);
                        case LIZARDMAN -> LizardmanVariantData.decode(variantBytes);
                        case DWARF     -> DwarfVariantData.decode(variantBytes);
                    };
                    // profession absent on legacy tags → "" (jobless).
                    String profession = tag.contains("profession")
                            ? tag.getString("profession") : "";
                    return new RaceTag(id, race, variant, profession);
                }

                @Override
                public CompoundTag write(RaceTag tag, HolderLookup.Provider registries) {
                    CompoundTag c = new CompoundTag();
                    c.putUUID("identityId", tag.identityId());
                    c.putByte("race", (byte) tag.race().getId());
                    c.putByteArray("variant", tag.variant().encode());
                    if (!tag.profession().isEmpty()) c.putString("profession", tag.profession());
                    return c;
                }
            };
}
