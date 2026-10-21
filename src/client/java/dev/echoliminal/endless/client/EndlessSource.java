package dev.echoliminal.endless.client;

import dev.echoliminal.liminal.LiminalMesh;
import java.util.List;

/**
 * Where the streaming renderer gets its geometry. The real implementation wraps the shared generator; every call must be
 * thread safe (they run on the worker threads, except {@link #materials()} which is read once).
 */
public interface EndlessSource {
	/** Region edge in blocks. */
	int REGION = 64;

	int ZONE_BACKROOMS = 0, ZONE_FIELD = 1, ZONE_POOL = 2;

	/** The fixed material palette (index = material id used by {@link EndlessMesh#mat()}). */
	List<LiminalMesh.Material> materials();

	/** Region (rx, rz) at level of detail 0..4. */
	EndlessMesh region(int rx, int rz, int lod);

	/** The zone around block column (x, z): one of the {@code ZONE_*} constants. */
	int zone(int x, int z);
}
