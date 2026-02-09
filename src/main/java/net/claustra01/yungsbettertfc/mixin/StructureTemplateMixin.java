package net.claustra01.yungsbettertfc.mixin;

import java.util.Set;
import net.claustra01.yungsbettertfc.access.StructureTemplateIdAccess;
import net.claustra01.yungsbettertfc.world.processor.TfcBlockReplacementProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin implements StructureTemplateIdAccess {
    @Unique
    private static final Set<String> YBTF_STRUCTURE_NAMESPACES =
            Set.of(
                    "betterstrongholds",
                    "betterdungeons",
                    "betteroceanmonuments",
                    "betterfortresses",
                    "betterendisland",
                    "beneath");

    @Unique private ResourceLocation yungsbettertfc$templateId;

    @Override
    public ResourceLocation yungsbettertfc$getTemplateId() {
        return yungsbettertfc$templateId;
    }

    @Override
    public void yungsbettertfc$setTemplateId(ResourceLocation id) {
        this.yungsbettertfc$templateId = id;
    }

    // NeoForge runtime uses official names; we don't generate a refmap, so disable remapping.
    @Inject(method = "placeInWorld", at = @At("HEAD"), remap = false)
    private void yungsbettertfc$addProcessor(
            ServerLevelAccessor serverLevel,
            BlockPos offset,
            BlockPos pos,
            StructurePlaceSettings settings,
            RandomSource random,
            int flags,
            CallbackInfoReturnable<Boolean> cir) {
        ResourceLocation id = this.yungsbettertfc$templateId;
        if (id == null || !YBTF_STRUCTURE_NAMESPACES.contains(id.getNamespace())) {
            return;
        }

        // Ensure we run after the structure's own processors (we append to the end).
        if (!settings.getProcessors().contains(TfcBlockReplacementProcessor.INSTANCE)) {
            settings.addProcessor(TfcBlockReplacementProcessor.INSTANCE);
        }
    }
}
