package dev.blendermc.tools;

/** Dev tool: prints the connectivity report of the endless world (see {@link dev.blendermc.endless.gen.ConnectivityCheck}). */
public final class EndlessConnectivity {
	private EndlessConnectivity() {
	}

	public static void main(String[] args) {
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
		int radius = args.length > 1 ? Integer.parseInt(args[1]) : 24;
		System.out.println(dev.blendermc.endless.gen.ConnectivityCheck.run(seed, radius));
	}
}
