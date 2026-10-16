"""Procedural textures for MC:Source's models, painted with noise."""

import numpy as np

from modelkit import noise


def _mix(base, other, t):
    return base * (1 - t[..., None]) + np.array(other, float) * t[..., None]


def _finish(rgb):
    return np.clip(np.round(rgb), 0, 255).astype(np.uint8)


def fabric(size, color, seed, weave=2, dirt=(60, 45, 30), dirt_amount=0.35):
    base = np.ones((size, size, 3)) * np.array(color, float)
    y, x = np.mgrid[0:size, 0:size]
    pattern = ((x // weave + y // weave) % 2) * 0.03 - 0.015
    base *= (1 + pattern)[..., None]
    base *= (0.88 + 0.24 * noise(size, 6, seed))[..., None]
    grime = np.clip((noise(size, 24, seed + 1) - 0.5) * 2.5, 0, 1) * dirt_amount
    return _finish(_mix(base, dirt, grime))


def leather(size, color, seed):
    base = np.ones((size, size, 3)) * np.array(color, float)
    base *= (0.8 + 0.4 * noise(size, 3, seed, octaves=3))[..., None]
    creases = np.abs(np.sin(noise(size, 16, seed + 2) * 40.0))
    base *= (0.85 + 0.15 * creases)[..., None]
    shine = np.clip(noise(size, 32, seed + 3) - 0.55, 0, 1) * 2.0
    return _finish(_mix(base, (90, 88, 86), shine * 0.35))


def rubber(size, color, seed):
    base = np.ones((size, size, 3)) * np.array(color, float)
    rng = np.random.default_rng(seed)
    base *= (0.85 + 0.3 * rng.random((size, size)))[..., None]
    return _finish(base)


def ribbed(size, color, seed):
    y, x = np.mgrid[0:size, 0:size]
    base = np.ones((size, size, 3)) * np.array(color, float)
    base *= (0.85 + 0.15 * np.sin(y * 1.6))[..., None]
    base *= (0.9 + 0.2 * noise(size, 8, seed))[..., None]
    return _finish(base)


def paint(size, color, seed, chips=(70, 65, 60), chip_amount=0.25):
    base = np.ones((size, size, 3)) * np.array(color, float)
    base *= (0.92 + 0.16 * noise(size, 12, seed))[..., None]
    chipped = (noise(size, 6, seed + 5) > 1 - chip_amount * 0.4).astype(float)
    return _finish(_mix(base, chips, chipped * 0.8))


def skin(size, color, seed, blotch=(110, 70, 80), veins=(90, 40, 60)):
    base = np.ones((size, size, 3)) * np.array(color, float)
    base *= (0.85 + 0.3 * noise(size, 10, seed))[..., None]
    b = np.clip((noise(size, 28, seed + 1) - 0.45) * 3, 0, 1)
    base = _mix(base, blotch, b * 0.5)
    v = np.clip(1 - np.abs(noise(size, 20, seed + 2) - 0.5) * 30, 0, 1)
    base = _mix(base, veins, v * 0.6)
    return _finish(base)


def stained(size, color, seed, stain=(120, 20, 15), amount=0.45):
    base = np.array(fabric(size, color, seed, weave=1, dirt=(120, 110, 90), dirt_amount=0.5), float)
    s = np.clip((noise(size, 30, seed + 9) - (1 - amount)) * 4, 0, 1)
    return _finish(_mix(base, stain, s))


def metal(size, color, seed):
    base = np.ones((size, size, 3)) * np.array(color, float)
    y, x = np.mgrid[0:size, 0:size]
    brushed = noise(size, 2, seed, octaves=2)[:, :1].repeat(size, axis=1)
    base *= (0.85 + 0.25 * brushed)[..., None]
    rust = np.clip((noise(size, 20, seed + 4) - 0.65) * 4, 0, 1)
    return _finish(_mix(base, (110, 70, 40), rust * 0.6))


def tread(size, color, seed):
    y, x = np.mgrid[0:size, 0:size]
    base = np.ones((size, size, 3)) * np.array(color, float)
    grooves = ((x // 4) % 3 == 0) | ((y // 4) % 4 == 0)
    base *= np.where(grooves, 0.6, 1.0)[..., None]
    base *= (0.9 + 0.2 * noise(size, 8, seed))[..., None]
    return _finish(base)
