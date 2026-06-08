package com.example.examplemod;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.client.render.modeltype.ModModelTypes;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;

/**
 * MineColonies job for beast-guard citizens (knight spider — Stage 1).
 *
 * <p>Parallel to {@link com.minecolonies.core.colony.jobs.JobKnight}:
 * extends {@code AbstractJobGuard<JobBeastGuard>}, generates a
 * {@link EntityAIBeastGuard} as its AI, and answers
 * {@link #isGuard()} = true.
 *
 * <p>Locked to PATROL behaviour via the AI override — the building's
 * {@code GuardTaskSetting} is ignored, beasts always patrol.
 *
 * <p>Model: reuses MineColonies' Knight humanoid model for the SERVER
 * body (the player never sees it — the client-side render handler
 * swaps in the spider shadow). The model only matters if the render
 * handler fails for some reason; in that case the citizen falls back
 * to a knight humanoid, which is a safer visual than nothing.
 */
public class JobBeastGuard extends AbstractJobGuard<JobBeastGuard> {

    public JobBeastGuard(ICitizenData entity) {
        super(entity);
        // Stage L3-hotfix-6 — set the JobEntry directly in the
        // constructor so the {@code entry} field is non-null regardless
        // of how this job got constructed. {@code JobEntry.produceJob}
        // normally sets it after calling the producer, but that's only
        // ONE construction path. Other paths (legacy citizens loaded
        // from saves that pre-date the produceJob fix, deserialise via
        // class reflection, etc.) bypass produceJob and leave the entry
        // null — every later {@code getJobRegistryEntry().getKey()}
        // call NPEs ({@code KnightCombatAI.onTargetDied} chain).
        // Doing it here makes any construction safe; calling
        // {@code setRegistryEntry} again later in produceJob just
        // overwrites with the same value, which is harmless.
        try {
            setRegistryEntry(ModJobsRegistry.BEAST_GUARD.get());
        } catch (Throwable t) {
            // Only fails if the registry isn't initialised yet — very
            // early bootstrap. Real runs always have it by the time a
            // citizen exists.
        }
    }

    @Override
    public EntityAIBeastGuard generateGuardAI() {
        return new EntityAIBeastGuard(this);
    }

    @Override
    public boolean isGuard() {
        return true;
    }

    @Override
    public ResourceLocation getModel() {
        // Knight humanoid fallback for the server body; the client render
        // pipeline replaces it with the spider shadow.
        return ModModelTypes.KNIGHT_GUARD_ID;
    }

    @Override
    public boolean ignoresDamage(DamageSource source) {
        // Mirrors JobKnight — beast guards aren't immune to anything in
        // particular. Override later if we want explosion-immunity or
        // fall-immunity for the spider thematically.
        return false;
    }

    /**
     * Stage L3 hotfix — pre-link this job's {@code workBuilding} field
     * BEFORE the job is handed to {@code citizenData.setJob}. Needed to
     * avoid an NPE in {@code AbstractEntityAIGuard.<init>} during the
     * job-swap path used by {@link ExampleMod#assignBeastToTower}.
     *
     * <p>The crash sequence (bytecode-traced from MC 1.1.1319):
     * <ol>
     *   <li>{@code module.assignCitizen} links {@code JobKnight.workBuilding = tower}.</li>
     *   <li>{@code citizenData.setJob(JobBeastGuard)} fires
     *       {@code JobKnight.onRemoval()} which unassigns the citizen
     *       from the tower's module — clearing the knight's workBuilding.</li>
     *   <li>{@code setJob} then fires {@code onJobChanged} → AI build for
     *       the new {@code JobBeastGuard}.</li>
     *   <li>The AI ctor reads {@code citizenData.getWorkBuilding()} which
     *       delegates to {@code citizenData.job.getWorkBuilding()} — i.e.
     *       the NEW JobBeastGuard's workBuilding. That's null unless we
     *       pre-set it via this helper.</li>
     * </ol>
     * Pre-setting bypasses the NPE — the AI ctor finds the tower and
     * proceeds normally.
     *
     * <p>Inherits from {@link com.minecolonies.core.colony.jobs.AbstractJob#workBuilding}
     * (protected field, accessible to subclasses).
     */
    public void preLinkWorkBuilding(com.minecolonies.api.colony.buildings.IBuilding building) {
        this.workBuilding = building;
    }
}
