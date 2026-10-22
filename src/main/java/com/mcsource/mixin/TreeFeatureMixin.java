package com.mcsource.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.TreeFeature;

import com.mcsource.tree.Trees;

/** Plants a tree entity instead of building the tree from blocks. */
@Mixin(TreeFeature.class)
public abstract class TreeFeatureMixin {
	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void mcsource$plantTreeEntity(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
			CallbackInfoReturnable<Boolean> cir) {
		if (Trees.enabled()) {
			cir.setReturnValue(Trees.plant(level, random, origin, (TreeFeature) (Object) this));
		}
	}
}
