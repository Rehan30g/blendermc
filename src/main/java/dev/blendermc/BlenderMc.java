package dev.blendermc;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlenderMc implements ModInitializer {
	public static final String MOD_ID = "blendermc";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		dev.blendermc.liminal.LiminalCommands.register();
		dev.blendermc.live.LiveLink.register();
		LOGGER.info("BlenderMC loaded.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
