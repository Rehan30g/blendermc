package dev.echoliminal.endless.gen;

/**
 * The zones of the endless world with the look the renderer should give them. Colours are 0xRRGGBB. Fog distances
 * are in blocks (start, end).
 */
public enum Zone {
	BACKROOMS(0x7A7240, 12F, 110F, 0.60F, false),
	FIELD(0xB9C4CC, 150F, 1900F, 0.85F, true),
	POOLROOMS(0x9DB8BC, 12F, 130F, 0.70F, false);

	public final int fogColor;
	public final float fogStart, fogEnd;
	/** Ambient light 0..1 for cheap (far) lighting. */
	public final float ambient;
	/** Whether the open sky is visible in this zone. */
	public final boolean sky;

	Zone(int fogColor, float fogStart, float fogEnd, float ambient, boolean sky) {
		this.fogColor = fogColor;
		this.fogStart = fogStart;
		this.fogEnd = fogEnd;
		this.ambient = ambient;
		this.sky = sky;
	}
}
