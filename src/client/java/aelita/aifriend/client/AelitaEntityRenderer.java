package aelita.aifriend.client;

import aelita.aifriend.Aelita;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public class AelitaEntityRenderer extends MobEntityRenderer<Aelita.AelitaEntity, BipedEntityModel<Aelita.AelitaEntity>> {

    // Ссылаемся на реальное имя файла: aelita_skin.png
    private static final Identifier TEXTURE = Identifier.of(Aelita.MOD_ID, "textures/entity/aelita_skin.png");

    public AelitaEntityRenderer(EntityRendererFactory.Context context) {
        super(context, new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public Identifier getTexture(Aelita.AelitaEntity entity) {
        return TEXTURE;
    }
}