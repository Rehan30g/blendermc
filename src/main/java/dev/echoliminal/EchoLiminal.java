package dev.echoliminal;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EchoLiminal implements ModInitializer {
	public static final String MOD_ID = "echoliminal";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		dev.echoliminal.liminal.LiminalCommands.register();
		dev.echoliminal.endless.Endless.register();
		dev.echoliminal.live.LiveLink.register();
		LOGGER.info("Echoliminal loaded.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
