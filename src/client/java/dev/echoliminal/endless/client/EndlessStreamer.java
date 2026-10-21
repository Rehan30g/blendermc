package dev.echoliminal.endless.client;

import dev.echoliminal.EchoLiminal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Decides which tiles exist around the player and keeps them on the GPU. The area is a quadtree of {@link Tile}s over
 * fixed rings (level of detail 0 up to 80 blocks, 1 up to 112, 2 up to 288, 3 up to 640, 4 up to 2048, nearest point of the
 * tile counts). A leaf is requested from the worker threads nearest first; until it is ready, whatever is already resident
 * over its area keeps being drawn, so rings never open holes. A ring edge moves 12 percent before a tile is replaced by
 * a finer one (hysteresis). Nothing is drawn beyond the fog's end. Render thread only, except the workers.
 */
final class EndlessStreamer {
	/** Outer edge of level of detail i. */
	static final double[] RING = {80, 112, 288, 640, 2048};
	private static final int MAX_IN_FLIGHT = 4;
	private static final int UPLOADS_PER_TICK = 2;
	private static final int KEEP_TICKS = 100;
	private static final int MAX_RESIDENT = 700;

	final EndlessSource source;
	final EndlessMaterials materials;
	private final ExecutorService pool;
	private final Map<Tile, EndlessGpuTile> resident = new HashMap<>();
	private final Set<Tile> inFlight = ConcurrentHashMap.newKeySet();
	private final Map<Tile, Long> failedUntil = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<EndlessBuilder.Built> done = new ConcurrentLinkedQueue<>();
	private Set<Tile> leafSet = new HashSet<>();
	private List<EndlessGpuTile> drawList = List.of();
	private final ClientLevel level;
	private long tick;
	private boolean recompute = true;
	private volatile boolean closed;
	// Stats.
	private final long[] builtCount = new long[5], builtMillis = new long[5], builtTris = new long[5];

	EndlessStreamer(EndlessSource source, ClientLevel level) {
		this.source = source;
		this.materials = new EndlessMaterials(source.materials());
		this.level = level;
		this.pool = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "echoliminal-endless-gen");
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		});
	}

	ClientLevel level() {
		return this.level;
	}

	List<EndlessGpuTile> drawList() {
		return this.drawList;
	}

	int residentCount() {
		return this.resident.size();
	}

	void blockChanged(BlockPos pos) {
		EndlessGpuTile tile = this.resident.get(new Tile(0, Math.floorDiv(pos.getX(), EndlessSource.REGION), Math.floorDiv(pos.getZ(), EndlessSource.REGION)));
		if (tile != null) {
			tile.cellChanged(pos.asLong());
		}
	}

	void chunkChanged(int chunkX, int chunkZ) {
		EndlessGpuTile tile = this.resident.get(new Tile(0, Math.floorDiv(chunkX, EndlessSource.REGION / 16), Math.floorDiv(chunkZ, EndlessSource.REGION / 16)));
		if (tile != null) {
			tile.chunkChanged(chunkX, chunkZ);
		}
	}

	void tick(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		this.tick++;
		double px = mc.player.getX(), pz = mc.player.getZ();
		EndlessZones.tick(this.source, px, pz);
		boolean uploaded = this.upload();
		if (uploaded || this.recompute || this.tick % 4 == 0) {
			this.recompute = false;
			this.plan(px, pz);
		}
		for (EndlessGpuTile tile : this.resident.values()) {
			if (tile.tile.lod() == 0) {
				tile.update(this.level);
			}
		}
		if (this.tick % 600 == 0) {
			this.logStats();
		}
	}

	/** Moves finished builds onto the GPU (a couple per tick). */
	private boolean upload() {
		boolean any = false;
		for (int n = 0; n < UPLOADS_PER_TICK; n++) {
			EndlessBuilder.Built built = this.done.poll();
			if (built == null) {
				break;
			}
			Tile tile = built.tile;
			int lod = tile.lod();
			this.builtCount[lod]++;
			this.builtMillis[lod] += built.millis;
			this.builtTris[lod] += built.triangles;
			EchoLiminal.LOGGER.debug("Endless tile {} built: {} triangles, {} ms", tile, built.triangles, built.millis);
			if (this.closed || this.resident.containsKey(tile) || !this.leafSet.contains(tile)) {
				built.free();
				continue;
			}
			try {
				EndlessGpuTile gpu = new EndlessGpuTile(built, this.level);
				gpu.lastUsed = this.tick;
				this.resident.put(tile, gpu);
				any = true;
			} catch (RuntimeException e) {
				built.free();
				EchoLiminal.LOGGER.error("Endless tile {} upload failed", tile, e);
				this.failedUntil.put(tile, System.currentTimeMillis() + 30_000L);
			}
		}
		return any;
	}

	private boolean isLeafOf(Tile t) {
		return this.leafSet.contains(t);
	}

	/** Quadtree walk: which tiles should be drawn at this position. */
	private void collect(Tile node, double cx, double cz, double limit, List<Tile> out) {
		double d = node.distance(cx, cz);
		if (d > limit) {
			return;
		}
		int lod = node.lod();
		if (lod >= 2) {
			double ring = RING[lod - 1];
			if (d >= ring * (this.isLeafOf(node) ? 0.88 : 1.0)) {
				out.add(node);
				return;
			}
			for (int dz = 0; dz < 2; dz++) {
				for (int dx = 0; dx < 2; dx++) {
					this.collect(new Tile(lod - 1, node.tx() * 2 + dx, node.tz() * 2 + dz), cx, cz, limit, out);
				}
			}
			return;
		}
		// A single region: finest or one step coarser.
		if (d >= RING[0] * (this.isLeafOf(new Tile(1, node.tx(), node.tz())) ? 0.88 : 1.0)) {
			out.add(new Tile(1, node.tx(), node.tz()));
		} else {
			out.add(new Tile(0, node.tx(), node.tz()));
		}
	}

	private void plan(double cx, double cz) {
		double limit = Math.min(RING[4], EndlessZones.streamLimit());
		int rootEdge = Tile.SIZE[4] * EndlessSource.REGION;
		int x0 = (int) Math.floor((cx - limit) / rootEdge), x1 = (int) Math.floor((cx + limit) / rootEdge);
		int z0 = (int) Math.floor((cz - limit) / rootEdge), z1 = (int) Math.floor((cz + limit) / rootEdge);
		List<Tile> leaves = new ArrayList<>();
		for (int tz = z0; tz <= z1; tz++) {
			for (int tx = x0; tx <= x1; tx++) {
				// Level of detail 4 is the root; a root nearer than its ring splits down by itself.
				this.collect(new Tile(4, tx, tz), cx, cz, limit, leaves);
			}
		}
		Set<Tile> leafs = new HashSet<>(leaves);
		this.leafSet = leafs;

		List<EndlessGpuTile> draw = new ArrayList<>(leaves.size() + 8);
		List<Tile> missing = new ArrayList<>();
		List<Tile> readyTiles = new ArrayList<>();
		for (Tile leaf : leaves) {
			EndlessGpuTile gpu = this.resident.get(leaf);
			if (gpu != null) {
				gpu.lastUsed = this.tick;
				draw.add(gpu);
				readyTiles.add(leaf);
			} else {
				missing.add(leaf);
			}
		}
		if (!missing.isEmpty()) {
			// Stand-ins: resident tiles over a missing leaf that do not also cover a ready one.
			for (EndlessGpuTile gpu : this.resident.values()) {
				if (leafs.contains(gpu.tile)) {
					continue;
				}
				boolean overMissing = false, overReady = false;
				for (Tile m : missing) {
					if (gpu.tile.overlaps(m)) {
						overMissing = true;
						break;
					}
				}
				if (overMissing) {
					for (Tile r : readyTiles) {
						if (gpu.tile.overlaps(r)) {
							overReady = true;
							break;
						}
					}
				}
				if (overMissing && !overReady) {
					gpu.lastUsed = this.tick;
					draw.add(gpu);
				}
			}
			this.request(missing, cx, cz);
		}
		this.drawList = draw;
		this.evict();
	}

	private void request(List<Tile> missing, double cx, double cz) {
		missing.sort((a, b) -> Double.compare(a.distance(cx, cz), b.distance(cx, cz)));
		long now = System.currentTimeMillis();
		for (Tile tile : missing) {
			if (this.inFlight.size() >= MAX_IN_FLIGHT) {
				return;
			}
			if (this.inFlight.contains(tile)) {
				continue;
			}
			Long retry = this.failedUntil.get(tile);
			if (retry != null && retry > now) {
				continue;
			}
			if (tile.lod() == 0 && this.countNearInFlight() >= 1) {
				continue;
			}
			this.inFlight.add(tile);
			this.pool.execute(() -> {
				try {
					if (!this.closed) {
						EndlessBuilder.Built built = EndlessBuilder.build(this.source, this.materials, tile);
						if (this.closed) {
							built.free();
						} else {
							this.done.add(built);
						}
					}
				} catch (Throwable t) {
					EchoLiminal.LOGGER.error("Endless tile {} failed", tile, t);
					this.failedUntil.put(tile, System.currentTimeMillis() + 30_000L);
				} finally {
					this.inFlight.remove(tile);
				}
			});
		}
	}

	private int countNearInFlight() {
		int n = 0;
		for (Tile t : this.inFlight) {
			if (t.lod() == 0) {
				n++;
			}
		}
		return n;
	}

	/** Drops tiles nobody has used for a while (and the oldest beyond the cap). */
	private void evict() {
		Iterator<EndlessGpuTile> it = this.resident.values().iterator();
		while (it.hasNext()) {
			EndlessGpuTile gpu = it.next();
			if (this.tick - gpu.lastUsed > KEEP_TICKS && !this.leafSet.contains(gpu.tile)) {
				gpu.close();
				it.remove();
			}
		}
		if (this.resident.size() > MAX_RESIDENT) {
			List<EndlessGpuTile> all = new ArrayList<>(this.resident.values());
			all.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
			for (int i = 0; i < all.size() - MAX_RESIDENT; i++) {
				if (this.tick - all.get(i).lastUsed > 4) {
					all.get(i).close();
					this.resident.remove(all.get(i).tile);
				}
			}
		}
	}

	private void logStats() {
		StringBuilder sb = new StringBuilder("Endless streaming: ").append(this.resident.size()).append(" tiles resident, ").append(this.drawList.size()).append(" drawn;");
		for (int lod = 0; lod < 5; lod++) {
			if (this.builtCount[lod] > 0) {
				sb.append(" lod").append(lod).append(": ").append(this.builtCount[lod]).append(" built, avg ").append(this.builtMillis[lod] / this.builtCount[lod])
					.append(" ms, ").append(this.builtTris[lod] / this.builtCount[lod]).append(" triangles;");
			}
		}
		EchoLiminal.LOGGER.info(sb.toString());
	}

	void close() {
		this.closed = true;
		this.pool.shutdownNow();
		for (EndlessGpuTile gpu : this.resident.values()) {
			gpu.close();
		}
		this.resident.clear();
		this.drawList = List.of();
		EndlessBuilder.Built b;
		while ((b = this.done.poll()) != null) {
			b.free();
		}
	}

}
