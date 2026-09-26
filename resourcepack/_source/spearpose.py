"""Solve the Lost Trident's first-person 'throwing' transform so, under the vanilla spear charge pose,
the trident points at the crosshair with its prongs flat to the camera."""
import math, numpy as np
from spearsim import T, R, S

def euler_xyz(M):
    b = math.asin(max(-1, min(1, M[0, 2])))
    a = math.atan2(-M[1, 2], M[2, 2]); c = math.atan2(-M[0, 1], M[0, 0])
    return [round(math.degrees(v), 2) for v in (a, b, c)]

def solve(scale, grip_y, target=(0.30, -0.30, -0.62), forward=(-0.25, 0.12, -1.0), l=0.5):
    pre = T(0.56, -0.52, -0.72) @ T(-0.5, 0.7, 0.1) @ R(-55, 0, 0) @ R(0, 35.3, 0) @ R(0, 0, -9.785) @ T(0, 0, l * 0.2) @ S(1, 1, 1 + l * 0.2) @ R(0, -45, 0)
    Y = np.array(forward, float); Y /= np.linalg.norm(Y)
    X = np.array([1.0, 0, 0]); X -= X.dot(Y) * Y; X /= np.linalg.norm(X)
    Z = np.cross(X, Y)
    Rt = np.c_[X, Y, Z]
    A = pre[:3, :3]
    # orthonormalise the pre-rotation part (drop the small charge stretch) before solving
    U, _, Vt = np.linalg.svd(A); Rpre = U @ Vt
    Ritem = Rpre.T @ Rt
    rot = euler_xyz(Ritem)
    Rm = R(*rot)[:3, :3]
    q = np.array([0.0, (grip_y - 8) / 16, 0.0])
    local = np.linalg.solve(A, np.array(target) - pre[:3, 3])
    t = 16 * (local - Rm @ (scale * q))
    return {'rotation': rot, 'translation': [round(float(v), 3) for v in t], 'scale': [scale] * 3}
