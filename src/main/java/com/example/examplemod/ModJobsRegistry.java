package com.example.examplemod;

import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import com.minecolonies.core.colony.jobs.views.DefaultJobView;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers our custom {@link JobBeastGuard} against MineColonies'
 * {@code JOBS} registry ({@link CommonMinecoloniesAPIImpl#JOBS}).
 *
 * <p>MineColonies exposes its job registry as a {@code ResourceKey<Registry<JobEntry>>}
 * — third-party mods can add their own {@link JobEntry} via NeoForge's
 * {@link DeferredRegister} keyed against that registry. The job becomes
 * indistinguishable from MC's own jobs once registered: a
 * {@link com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule}
 * can reference it by entry, citizen assignment / load / save just works.
 *
 * <p>Stage 1 registers a single entry: {@code tensura_minecolonies:beast_guard}.
 * Add a corresponding lang key in {@code en_us.json} under
 * {@code com.minecolonies.job.beast_guard}.
 */
public final class ModJobsRegistry {

    /** Registry id — referenced by the {@link JobEntry} and the lang key. */
    public static final ResourceLocation BEAST_GUARD_ID =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "beast_guard");

    /** DeferredRegister against MineColonies' JOBS registry. */
    public static final DeferredRegister<JobEntry> JOBS =
            DeferredRegister.create(CommonMinecoloniesAPIImpl.JOBS, ExampleMod.MODID);

    /** Beast-guard JobEntry. Producer wires our {@link JobBeastGuard}
     *  constructor; view producer wires MineColonies' {@link DefaultJobView}
     *  (no custom client-side view needed for Stage 1).
     *  Translation key matches the registry id by convention. */
    public static final DeferredHolder<JobEntry, JobEntry> BEAST_GUARD =
            JOBS.register("beast_guard", () -> new JobEntry.Builder()
                    .setJobProducer(JobBeastGuard::new)
                    .setJobViewProducer(() -> DefaultJobView::new)
                    .setRegistryName(BEAST_GUARD_ID)
                    .createJobEntry());

    private ModJobsRegistry() {}

    public static void register(IEventBus modBus) {
        JOBS.register(modBus);
    }
}
