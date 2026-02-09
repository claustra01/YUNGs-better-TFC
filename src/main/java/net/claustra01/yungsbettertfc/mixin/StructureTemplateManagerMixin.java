package net.claustra01.yungsbettertfc.mixin;

import net.claustra01.yungsbettertfc.access.StructureTemplateIdAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerMixin {
    // NeoForge runtime uses official names; we don't generate a refmap, so disable remapping.
    @Inject(method = "getOrCreate", at = @At("RETURN"), remap = false)
    private void yungsbettertfc$setTemplateId(ResourceLocation id, CallbackInfoReturnable<StructureTemplate> cir) {
        StructureTemplate template = cir.getReturnValue();
        if (template instanceof StructureTemplateIdAccess access) {
            access.yungsbettertfc$setTemplateId(id);
        }
    }
}
