"""First-person Hellstorm pose: stock forward, limbs level, top up; grip placed in the lower right."""
import math, numpy as np
from spearpose import euler_xyz
from toon import rot_xyz

def solve(scale=0.6, target=(0.28, -0.36, -0.58), forward=(-0.10, 0.10, -1.0), grip_u=-5.0):
    f = np.array(forward, float); f /= np.linalg.norm(f)
    up = np.array([0.0, 1.0, 0.0]); up -= up.dot(f) * f; up /= np.linalg.norm(up)
    w = np.cross(up, f)
    s2 = 1 / math.sqrt(2)
    M_model = np.c_[[s2, s2, 0], [-s2, s2, 0], [0, 0, 1]]      # columns: u, v, z in model space
    M_world = np.c_[f, w, up]
    R = M_world @ M_model.T
    rot = euler_xyz(R)
    Rm = rot_xyz(*rot)
    g = np.array([grip_u * s2, grip_u * s2, 0.0]) / 16             # centred model units -> blocks
    tr = 16 * (np.array(target) - np.array([0.56, -0.52, -0.72]) - Rm @ (scale * g))
    return {'rotation': rot, 'translation': [round(float(v), 3) for v in tr], 'scale': [scale] * 3}
