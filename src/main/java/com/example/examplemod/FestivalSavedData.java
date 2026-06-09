package com.example.examplemod;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-server persistent store for the Harvest Festival "prestige" bonus.
 *
 * <p>The festival is once per colony (until a prestige reset). When it runs we
 * bake a permanent additive skill bonus into each citizen's MineColonies skill
 * levels (which can exceed the normal 99 cap — the productivity formula reads
 * the raw level; see {@code docs/decisions.md}). Because that bonus is baked
 * into the level, we record the exact per-citizen, per-skill offset here so a
 * prestige reset can subtract it back out cleanly even if the base level grew
 * underneath.
 *
 * <p>Also tracks:
 * <ul>
 *   <li>{@link #doneColonies} — colonies whose festival already ran (once-per).</li>
 *   <li>{@link #pendingTensuraSwap} — colonies whose Tensura swap/EP-buff/sync
 *       was queued because they were in unloaded chunks at festival time; it
 *       runs the next time the colony loads.</li>
 * </ul>
 */
public class FestivalSavedData extends SavedData {

    public static final String DATA_KEY = "tensura_minecolonies_festival";

    /** colonyIds whose festival has run. */
    private final Set<Integer> doneColonies = new HashSet<>();
    /** colonyIds whose Tensura swap/EP-buff/sync is queued for next load. */
    private final Set<Integer> pendingTensuraSwap = new HashSet<>();
    /** colonyId -> citizenId -> (skill ordinal -> baked bonus offset). */
    private final Map<Integer, Map<Integer, Map<Integer, Integer>>> offsets = new HashMap<>();

    public static FestivalSavedData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FestivalSavedData::new, FestivalSavedData::load),
                DATA_KEY);
    }

    // -------- once-per-colony --------

    public boolean isDone(int colonyId)          { return doneColonies.contains(colonyId); }
    public void markDone(int colonyId)           { doneColonies.add(colonyId); setDirty(); }

    // -------- unloaded swap queue --------

    public boolean isSwapPending(int colonyId)   { return pendingTensuraSwap.contains(colonyId); }
    public void queueSwap(int colonyId)          { pendingTensuraSwap.add(colonyId); setDirty(); }
    public void clearSwapPending(int colonyId)   { pendingTensuraSwap.remove(colonyId); setDirty(); }
    public Set<Integer> pendingSwapColonies()    { return new HashSet<>(pendingTensuraSwap); }

    // -------- tracked skill offsets (for prestige reset) --------

    /** Record an applied skill bonus so it can be subtracted on prestige reset.
     *  Accumulates if the same skill is bonused more than once. */
    public void addOffset(int colonyId, int citizenId, int skillOrdinal, int bonus) {
        offsets.computeIfAbsent(colonyId, c -> new HashMap<>())
               .computeIfAbsent(citizenId, c -> new HashMap<>())
               .merge(skillOrdinal, bonus, Integer::sum);
        setDirty();
    }

    /** All recorded offsets for a colony: citizenId -> (skillOrdinal -> offset). */
    public Map<Integer, Map<Integer, Integer>> offsetsForColony(int colonyId) {
        return offsets.getOrDefault(colonyId, Map.of());
    }

    /** Drop all recorded offsets + the done/pending flags for a colony
     *  (the caller subtracts the offsets from the citizens first). */
    public void clearColony(int colonyId) {
        offsets.remove(colonyId);
        doneColonies.remove(colonyId);
        pendingTensuraSwap.remove(colonyId);
        setDirty();
    }

    // -------- save / load --------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putIntArray("done", doneColonies.stream().mapToInt(Integer::intValue).toArray());
        tag.putIntArray("pendingSwap", pendingTensuraSwap.stream().mapToInt(Integer::intValue).toArray());

        ListTag colonies = new ListTag();
        for (var colonyEntry : offsets.entrySet()) {
            CompoundTag colonyTag = new CompoundTag();
            colonyTag.putInt("colony", colonyEntry.getKey());
            ListTag citizens = new ListTag();
            for (var citizenEntry : colonyEntry.getValue().entrySet()) {
                CompoundTag citizenTag = new CompoundTag();
                citizenTag.putInt("citizen", citizenEntry.getKey());
                ListTag skills = new ListTag();
                for (var skillEntry : citizenEntry.getValue().entrySet()) {
                    CompoundTag s = new CompoundTag();
                    s.putInt("s", skillEntry.getKey());
                    s.putInt("o", skillEntry.getValue());
                    skills.add(s);
                }
                citizenTag.put("skills", skills);
                citizens.add(citizenTag);
            }
            colonyTag.put("citizens", citizens);
            colonies.add(colonyTag);
        }
        tag.put("offsets", colonies);
        return tag;
    }

    public static FestivalSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        FestivalSavedData data = new FestivalSavedData();
        for (int id : tag.getIntArray("done")) data.doneColonies.add(id);
        for (int id : tag.getIntArray("pendingSwap")) data.pendingTensuraSwap.add(id);

        ListTag colonies = tag.getList("offsets", Tag.TAG_COMPOUND);
        for (int i = 0; i < colonies.size(); i++) {
            CompoundTag colonyTag = colonies.getCompound(i);
            int colonyId = colonyTag.getInt("colony");
            Map<Integer, Map<Integer, Integer>> citizenMap = new HashMap<>();
            ListTag citizens = colonyTag.getList("citizens", Tag.TAG_COMPOUND);
            for (int j = 0; j < citizens.size(); j++) {
                CompoundTag citizenTag = citizens.getCompound(j);
                int citizenId = citizenTag.getInt("citizen");
                Map<Integer, Integer> skillMap = new HashMap<>();
                ListTag skills = citizenTag.getList("skills", Tag.TAG_COMPOUND);
                for (int k = 0; k < skills.size(); k++) {
                    CompoundTag s = skills.getCompound(k);
                    skillMap.put(s.getInt("s"), s.getInt("o"));
                }
                citizenMap.put(citizenId, skillMap);
            }
            data.offsets.put(colonyId, citizenMap);
        }
        return data;
    }
}
