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
		"root", "_low", "fallen", "broken"};

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

	/** Whether the model reads and has something to draw, so trees are never planted invisible. */
	private static boolean readable(String path) {
		try {
			RiggedModel model = StudioModel.load(GameInstalls.source(), path, null);
			return model != null && model.triangleCount() > 0;
		} catch (Exception e) {
			LOGGER.info("Tree model {} could not be read: {}", path, e.toString());
			return false;
		}
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
				skip |= file.contains(word);
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
