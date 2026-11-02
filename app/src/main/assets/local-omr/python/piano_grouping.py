"""Conservative page-order grouping for this app's solo-piano OMR path.

An upstream Staff can already contain both five-line piano staves.  MultiStaff
instead describes simultaneous instrumental parts; it must not be mistaken for
a sequence of piano systems.  No model detections are silently discarded here.
This module deliberately has no numpy/homr imports so the grouping boundary can
be tested independently of inference.
"""

import math
from statistics import median


def _lines(staff):
    grid = getattr(staff, "grid", ())
    if grid and getattr(grid[0], "y", None):
        return len(grid[0].y)
    return 10 if getattr(staff, "is_grandstaff", False) else 5


def _unit(staff):
    grid = getattr(staff, "grid", ())
    if grid and getattr(grid[0], "y", None):
        ys = sorted(float(y) for y in grid[len(grid) // 2].y)
        # Omit the inter-staff gap in a ten-line Staff; its overall mean
        # spacing would otherwise make the confidence limits too permissive.
        differences = [ys[i + 1] - ys[i] for i in range(len(ys) - 1)
                       if i % 5 != 4 and ys[i + 1] > ys[i]]
        if differences:
            return median(differences)
    value = float(getattr(staff, "average_unit_size", 1))
    return value if math.isfinite(value) and value > 0 else 1


def _notes(staff):
    getter = getattr(staff, "get_notes", None)
    return list(getter()) if getter else []


def _snapshot(staff):
    return {"top": round(float(staff.min_y), 2),
            "bottom": round(float(staff.max_y), 2),
            "left": round(float(staff.min_x), 2),
            "right": round(float(staff.max_x), 2),
            "lines": _lines(staff), "notes": len(_notes(staff))}


def _emit(log, event):
    if log is not None:
        try:
            log(event)
        except Exception:
            # Diagnostic output must never replace the recognition result.
            pass


def _pair_is_plausible(upper, lower, grand_gaps):
    if _lines(upper) != 5 or _lines(lower) != 5:
        return False
    unit_a, unit_b = _unit(upper), _unit(lower)
    unit = median([unit_a, unit_b])
    if max(unit_a, unit_b) > 1.7 * min(unit_a, unit_b):
        return False
    width_a = float(upper.max_x) - float(upper.min_x)
    width_b = float(lower.max_x) - float(lower.min_x)
    if min(width_a, width_b) <= 0:
        return False
    overlap = min(float(upper.max_x), float(lower.max_x)) - max(float(upper.min_x), float(lower.min_x))
    if overlap < 0.72 * min(width_a, width_b):
        return False
    if abs(float(upper.min_x) - float(lower.min_x)) > max(12 * unit, 0.12 * min(width_a, width_b)):
        return False
    gap = float(lower.min_y) - float(upper.max_y)
    limit = 18 * unit
    if grand_gaps:
        limit = min(24 * unit, max(12 * unit, 1.8 * median(grand_gaps)))
    return -0.25 * unit <= gap <= limit


def recover_five_line_staffs(mask, staffs, make_staff, make_point, log=None):
    """Recover a missing detector anchor only from five long lines in its mask.

    This runs before note assignment.  It neither fabricates pitches nor copies
    another system's notes.  Short ledger lines, text and isolated strokes do
    not span enough of the known staff width to qualify.  Ambiguous/curved masks
    are left untouched rather than forcing an inferred musical layout.
    """
    import numpy as np

    original = list(staffs)
    if not original:
        return original
    pixels = np.asarray(mask)
    if pixels.ndim == 3 and pixels.shape[2] == 1:
        pixels = pixels[:, :, 0]
    if pixels.ndim != 2 or min(pixels.shape) < 32:
        return original
    ink = pixels > 0
    height, width = ink.shape
    widths = [float(staff.max_x) - float(staff.min_x) for staff in original]
    typical_width = median(value for value in widths if value > 0)
    unit = median(_unit(staff) for staff in original)
    radius = max(1, min(3, int(round(unit * 0.18))))
    # Combine a small vertical band so a one- or two-pixel skew does not erase
    # an otherwise uninterrupted staff line from the horizontal projection.
    band = ink.copy()
    for shift in range(1, radius + 1):
        band[shift:] |= ink[:-shift]
        band[:-shift] |= ink[shift:]
    strength = band.sum(axis=1)
    raw_strength = ink.sum(axis=1)
    threshold = max(40, typical_width * 0.55)
    strong_rows = np.flatnonzero(strength >= threshold)
    if not strong_rows.size:
        _emit(log, {"event": "piano-staff-recovery", "before": len(original), "recovered": [], "lineCandidates": []})
        return original
    boundaries = np.flatnonzero(np.diff(strong_rows) > 1) + 1
    bands = []
    for cluster in np.split(strong_rows, boundaries):
        lo, hi = int(cluster[0]), int(cluster[-1])
        weights = raw_strength[lo:hi + 1].astype(float)
        center = float(np.average(np.arange(lo, hi + 1), weights=weights)) if weights.sum() else (lo + hi) / 2
        bands.append((center, lo, hi))

    recovered, claimed = [], set()
    for start in range(len(bands) - 4):
        indices = set(range(start, start + 5))
        if claimed & indices:
            continue
        selected = bands[start:start + 5]
        ys = [entry[0] for entry in selected]
        gaps = [ys[i + 1] - ys[i] for i in range(4)]
        spacing = median(gaps)
        if spacing < max(2, unit * 0.55) or spacing > unit * 1.7:
            continue
        if max(abs(gap - spacing) for gap in gaps) > max(1.5, 0.16 * spacing):
            continue
        if any(not (ys[-1] < float(staff.min_y) - spacing * 0.65
                    or ys[0] > float(staff.max_y) + spacing * 0.65) for staff in original + recovered):
            continue
        line_support = []
        for _, lo, hi in selected:
            line_support.append(ink[max(0, lo):min(height, hi + 1)].any(axis=0))
        columns = np.flatnonzero(np.asarray(line_support).sum(axis=0) >= 4)
        if not columns.size or columns[-1] - columns[0] < typical_width * 0.65:
            continue
        left, right = int(columns[0]), int(columns[-1])
        nearest = min(original, key=lambda staff: min(abs(float(staff.min_y) - ys[-1]), abs(float(staff.max_y) - ys[0])))
        # Upstream resampling uses ten-pixel grids, sometimes with an anchor
        # offset. Include the nearest real grid's phase so Staff.merge obtains
        # common x positions without interpolating a different staff's pitch.
        xs = set(range(max(0, left // 10 * 10), min(width - 1, (right // 10 + 1) * 10) + 1, 10))
        xs.update(int(round(point.x)) for point in getattr(nearest, "grid", ()) if left <= point.x <= right)
        if len(xs) < 2:
            continue
        grid = []
        patch_radius = max(2, int(round(spacing * 0.35)))
        for x in sorted(xs):
            measured = []
            x1, x2 = max(0, x - patch_radius), min(width, x + patch_radius + 1)
            for center, lo, hi in selected:
                weights = ink[lo:hi + 1, x1:x2].sum(axis=1).astype(float)
                measured.append(float(np.average(np.arange(lo, hi + 1), weights=weights)) if weights.sum() else center)
            grid.append(make_point(float(x), measured, 0.0))
        candidate = make_staff(grid)
        recovered.append(candidate)
        claimed.update(indices)
    _emit(log, {"event": "piano-staff-recovery", "before": len(original),
                "lineCandidates": [round(entry[0], 2) for entry in bands],
                "recovered": [_snapshot(staff) for staff in recovered]})
    return sorted(original + recovered, key=lambda staff: (float(staff.min_y), float(staff.min_x)))


def normalize_piano_staffs(rows, make_multi, log=None):
    """Return every detected piano system in top-to-bottom document order.

    ``make_multi`` is the upstream MultiStaff constructor.  Existing ten-line
    Staff objects stay intact.  Adjacent five-line detections are joined only
    with evidence of a piano grand staff or an existing connected pair, and
    compatible geometry.  A page of independent single staves stays single.
    When piano evidence exists but a staff is left unpaired, report a readable
    failure rather than silently dropping it or playing it as the next bar.
    """
    unique, seen, memberships = [], set(), {}
    for row_index, row in enumerate(rows):
        for staff in row.staffs:
            key = id(staff)
            memberships.setdefault(key, set()).add(row_index)
            if key not in seen:
                seen.add(key)
                unique.append(staff)
    unique.sort(key=lambda staff: (float(staff.min_y), float(staff.min_x)))
    _emit(log, {"event": "piano-layout-input", "rows": len(rows),
                "staffs": [_snapshot(staff) for staff in unique]})
    if not unique:
        raise ValueError("没有检测到可识别的五线谱，请使用更清晰的乐谱")
    for staff in unique:
        values = [float(staff.min_y), float(staff.max_y), float(staff.min_x), float(staff.max_x)]
        if not all(math.isfinite(value) for value in values) or _lines(staff) not in (5, 10):
            raise ValueError("检测到不完整或不支持的谱表，请裁剪为清晰的钢琴谱后重试")

    grands = [staff for staff in unique if _lines(staff) == 10]
    grand_gaps = []
    for staff in grands:
        grid = getattr(staff, "grid", ())
        if grid:
            ys = sorted(float(y) for y in grid[len(grid) // 2].y)
            if len(ys) == 10:
                grand_gaps.append(ys[5] - ys[4])
    connected_pair = any(
        memberships[id(upper)] & memberships[id(lower)]
        and _pair_is_plausible(upper, lower, grand_gaps)
        for upper, lower in zip(unique, unique[1:]))
    piano_evidence = bool(grands) or connected_pair
    result, unpaired, merged_count = [], [], 0
    index = 0
    while index < len(unique):
        staff = unique[index]
        if _lines(staff) == 10:
            staff.is_grandstaff = True
            result.append(staff)
            index += 1
            continue
        lower = unique[index + 1] if index + 1 < len(unique) else None
        if piano_evidence and lower is not None and _pair_is_plausible(staff, lower, grand_gaps):
            before = {id(note) for note in _notes(staff) + _notes(lower)}
            merged = staff.merge(lower)
            if _lines(merged) != 10 or not getattr(merged, "grid", None):
                raise ValueError("上下谱表未能对齐，请使用清晰、完整的钢琴谱后重试")
            if not before.issubset({id(note) for note in _notes(merged)}):
                raise ValueError("上下谱表合并时音符不完整，请重新识别")
            merged.is_grandstaff = True
            result.append(merged)
            merged_count += 1
            index += 2
        else:
            result.append(staff)
            unpaired.append(staff)
            index += 1
    if piano_evidence and unpaired:
        _emit(log, {"event": "piano-layout-unpaired", "staffs": [_snapshot(staff) for staff in unpaired]})
        raise ValueError("检测到未配对的钢琴谱表，无法确认谱行顺序；请裁剪为完整上下谱表后重试")
    if len(result) != len(unique) - merged_count:
        raise ValueError("谱表整理不完整，请重新识别")
    _emit(log, {"event": "piano-layout-output", "systems": len(result),
                "mergedPairs": merged_count, "staffs": [_snapshot(staff) for staff in result]})
    return [make_multi([staff], []) for staff in result]
