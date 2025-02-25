package eu.pb4.stylednicknames;

import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.stylednicknames.command.Commands;
import eu.pb4.stylednicknames.config.ConfigManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class StyledNicknamesMod implements ModInitializer {
	public static final String ID = "stylednicknames";

	public static final Logger LOGGER = LogManager.getLogger("StyledNicknames");
	public static String VERSION = FabricLoader.getInstance().getModContainer("styled-nicknames").get().getMetadata().getVersion().getFriendlyString();

	@Override
	public void onInitialize() {
		this.crabboardDetection();
		Commands.register();
		ServerLifecycleEvents.SERVER_STARTING.register((s) -> {
			this.crabboardDetection();
			ConfigManager.loadConfig();
		});

		Placeholders.register(new Identifier("styled-nicknames","display_name"), (ctx, arg) -> {
			if (ctx.hasPlayer()) {
				if (ctx.player().networkHandler != null) {
					return PlaceholderResult.value(NicknameHolder.of(ctx.player().networkHandler).styledNicknames$getOutputOrVanilla());
				} else {
					return PlaceholderResult.value(ctx.player().getName());
				}
			} else {
				return PlaceholderResult.invalid("Not a player!");
			}
		});
	}

	public static final Identifier id(String path) {
		return new Identifier(ID, path);
	}

	private void crabboardDetection() {
		if (FabricLoader.getInstance().isModLoaded("cardboard")) {
			LOGGER.error("");
			LOGGER.error("Cardboard detected! This mod doesn't work with it!");
			LOGGER.error("You won't get any support as long as it's present!");
			LOGGER.error("");
			LOGGER.error("Read more: https://gist.github.com/Patbox/e44844294c358b614d347d369b0fc3bf");
			LOGGER.error("");
		}
	}
}
