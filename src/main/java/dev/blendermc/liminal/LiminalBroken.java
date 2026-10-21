package dev.blendermc.liminal;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's copy of which level cells are broken (fed by {@link LiminalBrokenPayload} and
 * {@link LiminalBrokenDeltaPayload}); the renderer skips their triangles. The server's own set lives in the
 * world's blendermc.json (see {@link LiminalWorld}).
 */
public final class LiminalBroken {
	private static final Map<String, LongOpenHashSet> CELLS = new ConcurrentHashMap<>();
	private static volatile int version;
	/** Single cell changes since the renderer last looked (level, cell), and whether a wholesale change needs a full re-check. */
	private static final java.util.Queue<Object[]> CHANGES = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static volatile boolean everything;

	private LiminalBroken() {
	}

	public static boolean isBroken(String level, long cell) {
		LongOpenHashSet set = CELLS.get(level);
		if (set == null) {
			return false;
		}
		synchronized (set) {
			return set.contains(cell);
		}
	}

	/** A copy of the broken cells of {@code level}. */
	public static LongSet broken(String level) {
		LongOpenHashSet set = CELLS.get(level);
		if (set == null) {
			return new LongOpenHashSet();
		}
		synchronized (set) {
			return new LongOpenHashSet(set);
		}
	}

	/** Increases on every change. */
	public static int version() {
		return version;
	}

	public static void setAll(String level, long[] cells) {
		CELLS.put(level, new LongOpenHashSet(cells));
		everything = true;
		version++;
	}

	/**
	 * Hands every cell change since the last call to {@code sink} (level name, cell); returns true instead when the
	 * changes were wholesale (full sync, disconnect) and everything has to be re-checked. Renderer thread.
	 */
	public static boolean drain(java.util.function.BiConsumer<String, Long> sink) {
		boolean all = everything;
		everything = false;
		Object[] change;
		while ((change = CHANGES.poll()) != null) {
			if (!all) {
				sink.accept((String) change[0], (Long) change[1]);
			}
		}
		return all;
	}

	public static void set(String level, long cell, boolean broken) {
		LongOpenHashSet set = CELLS.computeIfAbsent(level, k -> new LongOpenHashSet());
		synchronized (set) {
			if (broken) {
				set.add(cell);
			} else {
				set.remove(cell);
			}
		}
		CHANGES.add(new Object[] {level, cell});
		version++;
	}

	/** Forgets everything (client disconnect). */
	public static void clear() {
		CELLS.clear();
		everything = true;
		version++;
	}
}
