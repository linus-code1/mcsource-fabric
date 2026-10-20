package com.mcsource.client.mob;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.mcsource.client.mixin.ItemLayerAccessor;
import com.mcsource.client.mixin.ItemStackRenderStateAccessor;
import com.mcsource.model.RiggedModel;
import com.mcsource.model.source.StudioModel;

/**
 * Items held in a Half-Life 2 hand. Weapons, tools and other things with a
 * handle are gripped in the fist the way a Half-Life 2 character grips the
 * crowbar: the handle runs through the closed fingers and the item stands
 * out of the fist along the crowbar's line, at about the crowbar's size,
 * in place of Minecraft's own placement for its blocky arm.
 *
 * <p>Where the fist is comes from the hand's own bones: the knuckles (the
 * first joints of the index and little fingers), the wrist and the elbow.
 * For Half-Life 2's first-person hands, which come from the crowbar's view
 * model, the crowbar itself shows where and at what angle a handle is held.
 */
public final class HeldItems {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");

	/**
	 * How an item with a handle sits in the fist. {@code grip} is the point
	 * of the item's model (centred, before its display transform) that is
	 * held, {@code turn} turns its picture (degrees, about its face) so its
	 * handle runs straight up, {@code reach} is how far the item then stands
	 * out of the fist per unit of size, and {@code length} how long it is
	 * held next to the crowbar. {@code depth} thins a model drawn larger.
	 */
	public enum Grip {
		/** Swords, axes, pickaxes, shovels, hoes, sticks and rods: a picture from the handle at the lower left to the tip at the upper right. */
		HANDLE(-0.28F, -0.28F, 0.0F, 45.0F, 1.02F, 1.0F, 1.0F),
		/** The mace's handle is shorter and its head heavier. */
		MACE(-0.34F, -0.34F, 0.0F, 45.0F, 1.03F, 1.0F, 1.0F),
		/** Held at the middle of its bow, the string away from the fist. */
		BOW(-0.13F, 0.13F, 0.0F, 45.0F, 0.62F, 0.6F, 1.0F),
		/** A spear's picture runs the other way, its point at the upper left; held towards the back of the shaft. */
		SPEAR(0.2F, -0.2F, 0.0F, -45.0F, 0.94F, 1.6F, 0.5F),
		/** A torch stands up in its picture; held below the flame. */
		TORCH(0.0F, -0.28F, 0.0F, 0.0F, 0.4F, 0.45F, 1.0F),
		/**
		 * The trident is a model of its own, its shaft up the middle from 1.69
		 * below to its prongs 0.25 above its origin; held a little below the
		 * middle of the shaft.
		 */
		TRIDENT(-0.5F, -1.41F, -0.5F, 0.0F, 1.16F, 1.3F, 1.0F);

		final float gripX;
		final float gripY;
		final float gripZ;
		final float turn;
		final float reach;
		final float length;
		final float depth;

		Grip(float gripX, float gripY, float gripZ, float turn, float reach, float length, float depth) {
			this.gripX = gripX;
			this.gripY = gripY;
			this.gripZ = gripZ;
			this.turn = turn;
			this.reach = reach;
			this.length = length;
			this.depth = depth;
		}
	}

	/** How an item is gripped, or null for things simply held in the hand (blocks, food, shields). */
	public static Grip grip(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		if (stack.is(Items.MACE)) {
			return Grip.MACE;
		}
		if (stack.is(Items.BOW)) {
			return Grip.BOW;
		}
		if (stack.is(Items.TRIDENT)) {
			return Grip.TRIDENT;
		}
		if (stack.is(ItemTags.SPEARS)) {
			return Grip.SPEAR;
		}
		if (stack.is(Items.TORCH) || stack.is(Items.SOUL_TORCH) || stack.is(Items.REDSTONE_TORCH) || stack.is(Items.COPPER_TORCH)) {
			return Grip.TORCH;
		}
		if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS)
				|| stack.is(ItemTags.HOES) || stack.is(Items.STICK) || stack.is(Items.BLAZE_ROD) || stack.is(Items.BREEZE_ROD)
				|| stack.is(Items.BONE) || stack.is(Items.FISHING_ROD) || stack.is(Items.CARROT_ON_A_STICK)
				|| stack.is(Items.WARPED_FUNGUS_ON_A_STICK)) {
			return Grip.HANDLE;
		}
		return null;
	}

	/**
	 * Draws a gripped item. The pose stack is at the grip: its origin in the
	 * fist, y along the line the handle is held on (towards the item's tip)
	 * and z out of the item's flat face. {@code reach} is how far a sword's
	 * tip stands out of the fist, in the pose stack's units; other items are
	 * sized from it.
	 */
	public static void submit(ItemStackRenderState item, Grip grip, float reach, PoseStack poseStack, SubmitNodeCollector collector,
			int light, int outline) {
		ItemStackRenderStateAccessor access = (ItemStackRenderStateAccessor) item;
		ItemStackRenderState.LayerRenderState[] layers = access.mcsource$layers();
		int count = Math.min(access.mcsource$activeLayerCount(), layers.length);
		float size = reach * grip.length / grip.reach;
		// Minecraft's display transform moves, turns and scales the centred
		// model; here it brings the held point to the origin and the handle
		// onto y.
		Vector3f offset = new Vector3f(grip.gripX * size, grip.gripY * size, grip.gripZ * size * grip.depth)
				.rotateZ((float) Math.toRadians(grip.turn)).negate();
		// Minecraft mirrors a left hand's transform; mirror it first so it comes out the same.
		boolean left = access.mcsource$displayContext().leftHand();
		ItemTransform held = new ItemTransform(new Vector3f(0.0F, 0.0F, left ? -grip.turn : grip.turn),
				new Vector3f(left ? -offset.x : offset.x, offset.y, offset.z), new Vector3f(size, size, size * grip.depth));
		ItemTransform[] before = new ItemTransform[count];
		for (int i = 0; i < count; i++) {
			before[i] = ((ItemLayerAccessor) layers[i]).mcsource$itemTransform();
			layers[i].setItemTransform(held);
		}
		try {
			item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, outline);
		} finally {
			for (int i = 0; i < count; i++) {
				layers[i].setItemTransform(before[i]);
			}
		}
	}

	// ------------------------------------------------------------ the fist

	/**
	 * Where a hand's fist is, in the model's own space (Half-Life's axes and
	 * units): {@code grip} in the middle of the closed fingers, {@code along}
	 * the line a handle runs through it (from the little finger towards the
	 * index finger and thumb), {@code up} from the wrist towards the elbow,
	 * and {@code size} the hand's length from wrist to knuckles.
	 */
	public record Fist(Vector3f grip, Vector3f along, Vector3f up, float size) {
	}

	private record HandBones(int hand, int forearm, int index, int little) {
	}

	private static final Map<RiggedModel, HandBones[]> HAND_BONES = new IdentityHashMap<>();

	/** The fist of the right or left hand in this pose, or null if the model has no finger bones to find it by. */
	public static Fist fist(RiggedModel model, Matrix4f[] bones, boolean right) {
		HandBones found = HAND_BONES.computeIfAbsent(model, HeldItems::findHands)[right ? 0 : 1];
		if (found == null) {
			return null;
		}
		Vector3f wrist = joint(model, bones, found.hand());
		Vector3f index = joint(model, bones, found.index());
		Vector3f little = joint(model, bones, found.little());
		Vector3f knuckles = new Vector3f(index).add(little).mul(0.5F);
		Vector3f fingers = new Vector3f(knuckles).sub(wrist);
		float size = fingers.length();
		if (size < 1.0E-4F) {
			return null;
		}
		fingers.div(size);
		Vector3f along = new Vector3f(index).sub(little);
		along.fma(-along.dot(fingers), fingers);
		if (along.lengthSquared() < 1.0E-8F) {
			return null;
		}
		along.normalize();
		// The palm faces the way the fingers close; the left hand is the right one mirrored.
		Vector3f palm = new Vector3f(along).cross(fingers);
		if (!right) {
			palm.negate();
		}
		Vector3f up = found.forearm() >= 0 ? joint(model, bones, found.forearm()).sub(wrist) : new Vector3f(fingers).negate();
		if (up.lengthSquared() < 1.0E-8F) {
			up.set(fingers).negate();
		}
		up.normalize();
		// The handle runs through the curled fingers, a little in from the knuckles on the palm side.
		Vector3f grip = new Vector3f(knuckles).fma(0.3F * size, palm);
		return new Fist(grip, along, up, size);
	}

	/** A bone's joint in this pose, in model space. */
	private static Vector3f joint(RiggedModel model, Matrix4f[] bones, int bone) {
		Vector3f joint = new Matrix4f(model.inverseBind[bone]).invert().getTranslation(new Vector3f());
		return bones[bone].transformPosition(joint);
	}

	private static HandBones[] findHands(RiggedModel model) {
		return new HandBones[] {findHand(model, "r"), findHand(model, "l")};
	}

	private static HandBones findHand(RiggedModel model, String side) {
		int hand = bone(model, "_" + side + "_hand");
		int index = bone(model, "_" + side + "_finger1");
		int little = -1;
		for (String finger : new String[] {"_finger4", "_finger3", "_finger2"}) {
			little = bone(model, "_" + side + finger);
			if (little >= 0) {
				break;
			}
		}
		if (hand < 0 || index < 0 || little < 0) {
			return null;
		}
		return new HandBones(hand, bone(model, "_" + side + "_forearm"), index, little);
	}

	/** The bone whose name (lower case, spaces as underscores) ends with this, or -1. */
	private static int bone(RiggedModel model, String ending) {
		for (int b = 0; b < model.boneCount(); b++) {
			if (boneName(model, b).endsWith(ending)) {
				return b;
			}
		}
		return -1;
	}

	private static String boneName(RiggedModel model, int bone) {
		return model.boneNames[bone].toLowerCase(Locale.ROOT).replace(' ', '_');
	}

	// ------------------------------------------------------------ the crowbar

	/** The crowbar's held line in the bone it follows: where the hand holds it and its far end, in that bone's bind pose. */
	private record Crowbar(int bone, Vector3f grip, Vector3f tip) {
	}

	private static final Map<String, Optional<Crowbar>> CROWBARS = new HashMap<>();

	/**
	 * Where a view model holds its crowbar in this pose, as the grip and the
	 * crowbar's far end in model space, or null if the hands don't come from
	 * a crowbar's view model. {@code reference} names the hands as
	 * {@link SourceModel} does, with the {@code #words} that keep only the
	 * hands; the rest of that model is the crowbar.
	 */
	public static Vector3f[] crowbar(String reference, RiggedModel hands, Matrix4f[] bones) {
		Crowbar crowbar = CROWBARS.computeIfAbsent(reference, r -> Optional.ofNullable(findCrowbar(r, hands))).orElse(null);
		if (crowbar == null || crowbar.bone() >= bones.length) {
			return null;
		}
		Matrix4f bone = bones[crowbar.bone()];
		return new Vector3f[] {bone.transformPosition(new Vector3f(crowbar.grip())), bone.transformPosition(new Vector3f(crowbar.tip()))};
	}

	private static Crowbar findCrowbar(String reference, RiggedModel hands) {
		int hash = reference.indexOf('#');
		if (!reference.startsWith("source:") || hash < 0 || !reference.contains("crowbar")) {
			return null;
		}
		String path = reference.substring("source:".length(), hash);
		String[] words = reference.substring(hash + 1).split(",");
		try {
			RiggedModel full = StudioModel.load(GameInstalls.source(), path, null);
			if (full == null || full.boneCount() != hands.boneCount()) {
				return null;
			}
			return measureCrowbar(full, words);
		} catch (Exception e) {
			LOGGER.warn("Could not read the crowbar of {}", path, e);
			return null;
		}
	}

	/**
	 * Finds the crowbar (everything that isn't the hands) in the view model's
	 * idle pose: its long axis, the point on it nearest the middle of the
	 * fist, and the far end of the longer part beyond that point.
	 */
	private static Crowbar measureCrowbar(RiggedModel full, String[] handWords) {
		int vertices = full.positions.length / 3;
		boolean[] crowbar = new boolean[vertices];
		boolean[] hand = new boolean[vertices];
		for (int t = 0; t < full.triangleCount(); t++) {
			String material = full.textures[full.triangleTexture[t]].name().toLowerCase(Locale.ROOT);
			boolean isHand = false;
			for (String word : handWords) {
				isHand |= !word.isEmpty() && material.contains(word);
			}
			for (int k = 0; k < 3; k++) {
				int v = full.cornerVertex[t * 3 + k];
				if (isHand) {
					hand[v] = true;
				} else {
					crowbar[v] = true;
				}
			}
		}
		RiggedModel.Pose pose = new RiggedModel.Pose(full);
		RiggedModel.Sequence idle = full.find("idle01", "idle", "act_vm_idle");
		if (idle != null) {
			full.pose(idle, 0.0F, pose);
		} else {
			full.bindPose(pose);
		}
		Matrix4f[] bones = new Matrix4f[full.boneCount()];
		full.skinning(pose, bones);
		float[] weights = new float[full.boneCount()];
		Vector3f mean = new Vector3f();
		Vector3f fist = new Vector3f();
		int count = 0;
		int fistCount = 0;
		float[] posed = new float[vertices * 3];
		Vector3f p = new Vector3f();
		for (int i = 0; i < vertices; i++) {
			if (!crowbar[i] && !hand[i]) {
				continue;
			}
			float x = 0;
			float y = 0;
			float z = 0;
			int strongest = 0;
			for (int k = 0; k < 3; k++) {
				float w = full.positionWeights[i * 3 + k];
				if (w <= 0.0F) {
					continue;
				}
				bones[full.positionBones[i * 3 + k]].transformPosition(full.positions[i * 3], full.positions[i * 3 + 1],
						full.positions[i * 3 + 2], p);
				x += p.x * w;
				y += p.y * w;
				z += p.z * w;
				if (w > full.positionWeights[i * 3 + strongest]) {
					strongest = k;
				}
				if (crowbar[i]) {
					weights[full.positionBones[i * 3 + k]] += w;
				}
			}
			posed[i * 3] = x;
			posed[i * 3 + 1] = y;
			posed[i * 3 + 2] = z;
			if (crowbar[i]) {
				mean.add(x, y, z);
				count++;
			} else {
				String bone = boneName(full, full.positionBones[i * 3 + strongest]);
				if (bone.contains("r_hand") || bone.contains("r_finger")) {
					fist.add(x, y, z);
					fistCount++;
				}
			}
		}
		if (count < 8 || fistCount == 0) {
			return null;
		}
		mean.div(count);
		fist.div(fistCount);
		// The long axis: the strongest direction of the spread of its vertices.
		float[] c = new float[9];
		for (int i = 0; i < vertices; i++) {
			if (!crowbar[i]) {
				continue;
			}
			float dx = posed[i * 3] - mean.x;
			float dy = posed[i * 3 + 1] - mean.y;
			float dz = posed[i * 3 + 2] - mean.z;
			c[0] += dx * dx;
			c[1] += dx * dy;
			c[2] += dx * dz;
			c[4] += dy * dy;
			c[5] += dy * dz;
			c[8] += dz * dz;
		}
		c[3] = c[1];
		c[6] = c[2];
		c[7] = c[5];
		Vector3f axis = new Vector3f(1.0F, 0.7F, 0.4F).normalize();
		for (int step = 0; step < 32; step++) {
			axis.set(c[0] * axis.x + c[1] * axis.y + c[2] * axis.z, c[3] * axis.x + c[4] * axis.y + c[5] * axis.z,
					c[6] * axis.x + c[7] * axis.y + c[8] * axis.z);
			if (axis.lengthSquared() < 1.0E-12F) {
				return null;
			}
			axis.normalize();
		}
		float low = Float.MAX_VALUE;
		float high = -Float.MAX_VALUE;
		for (int i = 0; i < vertices; i++) {
			if (crowbar[i]) {
				float t = (posed[i * 3] - mean.x) * axis.x + (posed[i * 3 + 1] - mean.y) * axis.y + (posed[i * 3 + 2] - mean.z) * axis.z;
				low = Math.min(low, t);
				high = Math.max(high, t);
			}
		}
		float held = Math.max(low, Math.min(high, new Vector3f(fist).sub(mean).dot(axis)));
		if (held - low > high - held) {
			axis.negate();
			float t = low;
			low = -high;
			high = -t;
			held = -held;
		}
		if (high - held < 1.0F) {
			return null;
		}
		Vector3f grip = new Vector3f(mean).fma(held, axis);
		Vector3f tip = new Vector3f(grip).fma(high - held, axis);
		// Kept in the bind pose of the bone the crowbar follows most, so it moves with every animation.
		int bone = 0;
		for (int b = 1; b < weights.length; b++) {
			if (weights[b] > weights[bone]) {
				bone = b;
			}
		}
		Matrix4f back = new Matrix4f(bones[bone]).invert();
		LOGGER.info("Held items follow the crowbar of {}: {} units from the fist to its end, on bone {}", full.name, high - held,
				full.boneNames[bone]);
		return new Crowbar(bone, back.transformPosition(grip), back.transformPosition(tip));
	}

	private HeldItems() {
	}
}
