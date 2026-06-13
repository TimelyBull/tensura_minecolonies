package com.example.examplemod;

import io.github.manasmods.tensura.ability.magic.Element;
import io.github.manasmods.tensura.ability.magic.spiritual.SpiritualMagic;
import io.github.manasmods.tensura.storage.TensuraStorages;
import io.github.manasmods.tensura.storage.spirit.ISpiritWielder;
import net.minecraft.server.level.ServerPlayer;

/**
 * Luminous's Covenant boon — a hero-evolution gift granting starter
 * SPIRITS (investigated: spirits are per-player elemental affinities in
 * {@code ISpiritWielder}, NOT skills; {@code getSpiritLevelId(element)}
 * returns 0 when absent and {@code setSpiritLevel} grants — both clean,
 * confirmed against {@code TensuraStorages.getSpiritFrom} and the
 * vanilla {@code /spirit} command's own path).
 *
 * <p><b>"Does nothing if you already have spirits":</b> if the player
 * holds ANY non-zero elemental affinity, the boon is refused (returns
 * -1) — it's strictly for those starting from nothing.
 */
public final class LuminousSpirits {

    /** The three starter affinities granted, at LESSER level. */
    private static final Element[] STARTER_ELEMENTS =
            { Element.FLAME, Element.WATER, Element.WIND };

    private LuminousSpirits() {}

    /**
     * Grant the three starter spirits IF the player has none.
     * @return the count granted, or -1 if the player already had spirits
     *         (the boon does nothing, per the design).
     */
    public static int grantStarterSpirits(ServerPlayer player) {
        ISpiritWielder wielder = TensuraStorages.getSpiritFrom(player);
        if (wielder == null) return -1;
        // "Has none" — no element carries a non-zero spirit level.
        for (Element element : Element.values()) {
            if (wielder.getSpiritLevelId(element) > 0) return -1;
        }
        int granted = 0;
        for (Element element : STARTER_ELEMENTS) {
            if (wielder.setSpiritLevel(element, SpiritualMagic.SpiritLevel.LESSER)) {
                granted++;
            }
        }
        wielder.markDirty();
        return granted;
    }
}
