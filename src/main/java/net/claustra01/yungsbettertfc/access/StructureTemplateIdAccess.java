package net.claustra01.yungsbettertfc.access;

import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * Attached to {@link net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate} via mixin.
 *
 * <p>This must live outside our mixin package so it can be referenced by normal game code without triggering
 * Mixin's IllegalClassLoadError ("defined mixin package ... cannot be referenced directly").</p>
 */
public interface StructureTemplateIdAccess {
    @Nullable ResourceLocation yungsbettertfc$getTemplateId();

    void yungsbettertfc$setTemplateId(ResourceLocation id);
}

