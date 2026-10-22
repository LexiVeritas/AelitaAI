package aelita.aifriend.client;

import aelita.aifriend.Aelita;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class AelitaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Регистрируем рендерер для AELITA_ENTITY
		EntityRendererRegistry.register(Aelita.AELITA_ENTITY, AelitaEntityRenderer::new);
	}
}