"""Vanilla's extra first-person crossbow transforms (ItemInHandRenderer), and item transforms that
cancel them so the Hellstorm stays exactly where the standby pose puts it."""
import math, numpy as np
from spearsim import T, R, S
from spearpose import euler_xyz

def charged_extra():                       # holding a loaded crossbow, main hand
    return T(-0.641864, 0, 0) @ R(0, 10, 0)

def charging_extra(g=0.6):                 # while drawing it (scale stretch ignored: it is tiny)
    return T(-0.4785682, -0.094387, 0.05731531) @ R(-11.935, 0, 0) @ R(0, 65.3, 0) @ R(0, 0, -9.785) @ T(0, 0, 0.04 * g) @ R(0, -45, 0)

def item_matrix(d):
    return T(*(np.array(d['translation']) / 16)) @ R(*d['rotation']) @ S(*d['scale'])

def cancel(desired, extra):
    """Item transform X with extra @ X == item_matrix(desired)."""
    M = np.linalg.inv(extra) @ item_matrix(desired)
    s = desired['scale'][0]
    Rm = M[:3, :3] / s
    return {'rotation': euler_xyz(Rm), 'translation': [round(float(v), 3) for v in M[:3, 3] * 16], 'scale': desired['scale']}
