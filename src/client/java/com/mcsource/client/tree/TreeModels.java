package com.mcsource.client.tree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mcsource.client.mob.GameInstalls;
import com.mcsource.client.mob.SourceModel;
import com.mcsource.model.RiggedModel;
import com.mcsource.model.source.StudioModel;

/**
 * The tree models found in the player's Source games, sorted into conifers,
 * bare trees and broadleaf trees, and which one a Minecraft tree becomes.
 */
public final class TreeModels {
	private static final Logger LOGGER = LoggerFactory.getLogger("mcsource");
	private static final String[] SKIP = {"stump", "shadow", "branch", "log", "trunk", "leaves", "gib", "card", "skybox", "lod",
		"root", "_low", "fallen", "broken", "xen", "alien", "tentacle", "cliff", "hedge", "bush", "dead_fall", "leaning"};

	private static List<String> conifers;
	private static List<String> bare;
	private static List<String> broadleaf;

	private TreeModels() {
	}

	/** Whether any tree model was found. */
	public static synchronized boolean available() {
		find();
		return !conifers.isEmpty() || !bare.isEmpty() || !broadleaf.isEmpty();
	}

	/** The model for a tree of this wood (such as {@code minecraft:spruce_log}), varied by {@code seed}; null if none. */
	public static synchronized SourceModel pick(String wood, long seed) {
		find();
		List<String> kind = wood.contains("spruce") ? conifers : wood.contains("pale_oak") ? bare : broadleaf;
		for (List<String> list : List.of(kind, broadleaf, conifers, bare)) {
			if (list.isEmpty()) {
				continue;
			}
			int start = (int) Math.floorMod(seed, (long) list.size());
			for (int i = 0; i < list.size(); i++) {
				SourceModel model = SourceModel.get("source:" + list.get((start + i) % list.size()));
				if (model != null) {
					return model;
				}
			}
		}
		return null;
	}

	/**
	 * Whether the model reads and stands up like a tree: tall enough, its foot
	 * at its origin and its top above its foot. Leaning cliff trees, logs,
	 * stumps and other things with "tree" in their names are left out.
	 */
	private static boolean readable(String path) {
		try {
			RiggedModel model = StudioModel.load(GameInstalls.source(), path, null);
			if (model == null || model.triangleCount() == 0) {
				return false;
			}
			if (!upright(model.positions)) {
				LOGGER.info("Tree model {} left out: not an upright tree", path);
				return false;
			}
			if (fleshy(model)) {
				LOGGER.info("Tree model {} left out: its bark is pink like flesh, not wood", path);
				return false;
			}
			return true;
		} catch (Exception e) {
			LOGGER.info("Tree model {} could not be read: {}", path, e.toString());
			return false;
		}
	}

	/**
	 * Whether the model's main solid texture is pinkish red, as on alien
	 * growths, rather than the browns and greys of bark.
	 */
	private static boolean fleshy(RiggedModel model) {
		int[] uses = new int[model.textures.length];
		for (int t : model.triangleTexture) {
			uses[t]++;
		}
		int main = -1;
		for (int i = 0; i < uses.length; i++) {
			if (!model.textures[i].masked() && (main < 0 || uses[i] > uses[main])) {
				main = i;
			}
		}
		if (main < 0) {
			return false;
		}
		long r = 0;
		long g = 0;
		long b = 0;
		for (int p : model.textures[main].pixels()) {
			r += p >> 16 & 0xFF;
			g += p >> 8 & 0xFF;
			b += p & 0xFF;
		}
		return r > g * 1.35 && b > g * 0.8;
	}

	/** Positions are x, y, z with z up, in Source units. */
	static boolean upright(float[] positions) {
		int count = positions.length / 3;
		if (count == 0) {
			return false;
		}
		float low = Float.MAX_VALUE;
		float high = -Float.MAX_VALUE;
		for (int i = 0; i < count; i++) {
			low = Math.min(low, positions[i * 3 + 2]);
			high = Math.max(high, positions[i * 3 + 2]);
		}
		float height = high - low;
		// At least three blocks tall, standing on its origin.
		if (height < 120.0F || Math.abs(low) > 0.15F * height) {
			return false;
		}
		return Math.hypot(average(positions, low + 0.75F * height, high, 0), average(positions, low + 0.75F * height, high, 1))
				<= 0.3F * height
				&& Math.hypot(average(positions, low, low + 0.15F * height, 0), average(positions, low, low + 0.15F * height, 1))
						<= 0.2F * height;
	}

	/** Average of one horizontal axis over the vertices between two heights. */
	private static float average(float[] positions, float from, float to, int axis) {
		double sum = 0.0;
		int n = 0;
		for (int i = 0; i < positions.length / 3; i++) {
			float z = positions[i * 3 + 2];
			if (z >= from && z <= to) {
				sum += positions[i * 3 + axis];
				n++;
			}
		}
		return n == 0 ? 0.0F : (float) (sum / n);
	}

	private static void find() {
		if (conifers != null) {
			return;
		}
		conifers = new ArrayList<>();
		bare = new ArrayList<>();
		broadleaf = new ArrayList<>();
		List<String> paths;
		try {
			paths = GameInstalls.source().list("models/");
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not list Source models", e);
			return;
		}
		for (String path : paths) {
			String name = path.toLowerCase(Locale.ROOT);
			if (!name.endsWith(".mdl")) {
				continue;
			}
			String file = name.substring(name.lastIndexOf('/') + 1);
			if (!file.contains("tree") || !(name.contains("foliage") || name.contains("forest") || name.contains("nature")
					|| name.contains("tree"))) {
				continue;
			}
			boolean skip = false;
			for (String word : SKIP) {
				skip |= name.contains(word);
			}
			if (skip) {
				continue;
			}
			if (!readable(name)) {
				continue;
			}
			if (file.contains("pine") || file.contains("spruce") || file.contains("fir") || file.contains("conifer")
					|| file.contains("evergreen")) {
				conifers.add(name);
			} else if (file.contains("dead") || file.contains("bare") || file.contains("dry")) {
				bare.add(name);
			} else {
				broadleaf.add(name);
			}
		}
		conifers.sort(null);
		bare.sort(null);
		broadleaf.sort(null);
		LOGGER.info("Tree models: conifers {}, bare {}, broadleaf {}", conifers, bare, broadleaf);
	}
}
