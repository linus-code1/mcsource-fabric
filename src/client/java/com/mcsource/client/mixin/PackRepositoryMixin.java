package com.mcsource.client.mixin;

import java.util.Arrays;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;

import com.mcsource.client.texture.HalfLife2BlockPack;

/** Adds the Half-Life 2 block texture pack to the client's resource packs. */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE",
			target = "Lcom/google/common/collect/ImmutableSet;copyOf([Ljava/lang/Object;)Lcom/google/common/collect/ImmutableSet;"))
	private static Object[] mcsource$addHalfLife2Blocks(Object[] sources) {
		for (Object source : sources) {
			if (source instanceof ClientPackSource) {
				Object[] more = Arrays.copyOf(sources, sources.length + 1, RepositorySource[].class);
				more[sources.length] = new HalfLife2BlockPack();
				return more;
			}
		}
		return sources;
	}
}
