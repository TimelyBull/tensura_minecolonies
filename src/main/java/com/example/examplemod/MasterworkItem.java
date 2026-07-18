package com.example.examplemod;

import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;

/**
 * The Masterwork weapon line (Dwargon Covenant). Forged at the Tensura Smithing
 * Bench from a hihiirokane weapon + a Masterwork Weapon Core, gated by the
 * Masterwork schematic. These are "legendary" weapons that grow with the player
 * (see docs/future-ideas.md "Legendary weapons" / the Masterwork DECIDED notes).
 *
 * <p>FOUNDATION STAGE: registered + craftable + EP-growing (via its
 * gear_existence entry — EP stats + EP-backed self-repair). The player-status
 * layer is added on top next:
 * <ul>
 *   <li>Alignment form — majin → lifesteal + dark on-hit; non-majin → regen +
 *       light on-hit (via {@link WorldReputationManager#isMajinSide}).</li>
 *   <li>Battlewill-vs-magic branch — physical (sweep, drains aura) / magic
 *       (magic-slice projectile, drains magicule) / balanced (base).</li>
 *   <li>Mastered-skill QOL tiers — 10 magnet / 15 step assist / 20 soulbound.</li>
 *   <li>Prestige debuff — base damage floors to 10, same % off other stats,
 *       recovers as EP regrows; abilities kept.</li>
 * </ul>
 */
public class MasterworkItem extends SwordItem {

    public MasterworkItem(Tier tier, Properties properties) {
        super(tier, properties);
    }
}
