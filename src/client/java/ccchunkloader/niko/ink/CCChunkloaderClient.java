package ccchunkloader.niko.ink;

import dan200.computercraft.api.client.FabricComputerCraftAPIClient;
import dan200.computercraft.api.client.turtle.TurtleUpgradeModeller;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CCChunkloaderClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(CCChunkloaderClient.class);
	
	@Override
	public void onInitializeClient() {
		LOGGER.info("Initializing CCChunkloader client-side");
		
		// Register extra models for loading
		ModelLoadingPlugin.register(pluginContext -> {
			pluginContext.addModels(
				Identifier.of(CCChunkloader.MOD_ID, "turtle/chunkloader_left"),
				Identifier.of(CCChunkloader.MOD_ID, "turtle/chunkloader_right")
			);
		});
		
		// Register turtle upgrade modeller with custom Blockbench models
		FabricComputerCraftAPIClient.registerTurtleUpgradeModeller(
			CCChunkloader.CHUNKLOADER_UPGRADE_SERIALISER,
			TurtleUpgradeModeller.sided(
				Identifier.of(CCChunkloader.MOD_ID, "turtle/chunkloader_left"),
				Identifier.of(CCChunkloader.MOD_ID, "turtle/chunkloader_right")
			)
		);
		
		LOGGER.info("CCChunkloader client initialized - registered custom 3D turtle upgrade models");
	}
}