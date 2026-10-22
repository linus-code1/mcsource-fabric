package com.mcsource.world.hl2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The unseen solid of a map world: what you stand on and bump into, while
 * what you see is the map itself, drawn as the game draws it. Filled from
 * the bottom in eighths, so floors and steps sit where the map's are.
 */
public class MapSolidBlock extends Block {
	public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, 8);
	private static final VoxelShape[] SHAPES = new VoxelShape[9];

	static {
		for (int i = 1; i <= 8; i++) {
			SHAPES[i] = Block.box(0, 0, 0, 16, i * 2, 16);
		}
	}

	public MapSolidBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(LAYERS, 8));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LAYERS);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		// Aimed at, so blocks can be placed against the map; its outline isn't drawn.
		return SHAPES[state.getValue(LAYERS)];
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		// Things moving in a map world collide with the map's own solid
		// instead (see MapCollision); the blocks stay solid for path finding,
		// projectiles and anything else that asks.
		if (context instanceof net.minecraft.world.phys.shapes.EntityCollisionContext entityContext) {
			net.minecraft.world.entity.Entity entity = entityContext.getEntity();
			if (entity != null && !(entity instanceof net.minecraft.world.entity.projectile.Projectile)
					&& MapCollision.of(entity.level()) != null) {
				return Shapes.empty();
			}
		}
		return SHAPES[state.getValue(LAYERS)];
	}

	@Override
	protected VoxelShape getOcclusionShape(BlockState state) {
		return Shapes.empty();
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return true;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0F;
	}
}
