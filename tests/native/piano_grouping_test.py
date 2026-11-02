"""Regression tests for the solo-piano staff recovery/grouping helper.

The phone rendered this score slightly differently from the desktop test image:
the second piano system lost its bass staff and the detector only reported four
of its five lines.  These cases replay that geometry so the helper stays able
to recover the missing staff instead of failing the whole page.
"""

import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..',
                                'app', 'src', 'main', 'assets', 'local-omr', 'python'))
import piano_grouping


class Point:
    def __init__(self, x, y, confidence=0.0):
        self.x = x
        self.y = list(y)
        self.confidence = confidence


class Staff:
    def __init__(self, grid, notes=0, is_grandstaff=False):
        self.grid = grid
        self._notes = notes
        self.is_grandstaff = is_grandstaff
        ys = [value for point in grid for value in point.y]
        self.min_y, self.max_y = min(ys), max(ys)
        self.min_x = min(point.x for point in grid)
        self.max_x = max(point.x for point in grid)

    def get_notes(self):
        return [object() for _ in range(self._notes)]

    def merge(self, lower):
        grid = []
        for upper in self.grid:
            nearest = min(lower.grid, key=lambda point: abs(point.x - upper.x))
            grid.append(Point(upper.x, list(upper.y) + list(nearest.y)))
        return Staff(grid, self._notes + lower._notes, True)


class MultiStaff:
    def __init__(self, staffs, args):
        self.staffs = staffs


def make_staff(lines, notes=10):
    grid = [Point(float(x), lines) for x in range(160, 1781, 101)]
    return Staff(grid, notes)


def mask(width, height, lines, partial=()):
    ink = np.zeros((height, width), dtype=np.uint8)
    for y in lines:
        ink[y:y + 3, 160:1781] = 255
    for y, right in partial:
        ink[y:y + 3, 160:right] = 255
    return ink


def lines_at(top, step=16.0, count=5):
    return [int(round(top + step * index)) for index in range(count)]


PHONE_LINES = (lines_at(411) + lines_at(540) + lines_at(768) + lines_at(897, count=4)
               + lines_at(1124) + lines_at(1262) + lines_at(1489) + lines_at(1627)
               + lines_at(1854) + lines_at(1987) + lines_at(2215) + lines_at(2368))


class PianoGroupingTest(unittest.TestCase):
    def test_recovers_partner_of_single_staff_with_four_visible_lines(self):
        # The fourth line of the second system's bass staff is cut off, so only
        # four long lines exist where five are expected.
        staffs = [make_staff(lines_at(411) + lines_at(540)),
                  make_staff(lines_at(768)),
                  make_staff(lines_at(1124) + lines_at(1262)),
                  make_staff(lines_at(1489) + lines_at(1627)),
                  make_staff(lines_at(1854) + lines_at(1987)),
                  make_staff(lines_at(2215) + lines_at(2368))]
        events = []
        recovered = piano_grouping.recover_five_line_staffs(
            mask(1900, 2600, PHONE_LINES, partial=[(961, 960)]), staffs, Staff, Point, events.append)
        self.assertEqual(len(recovered), len(staffs) + 1, events)
        added = [staff for staff in recovered if staff not in staffs]
        self.assertEqual(len(added), 1)
        self.assertAlmostEqual(added[0].min_y, 897, delta=2)
        self.assertEqual(len(added[0].grid[len(added[0].grid) // 2].y), 5)
        systems = piano_grouping.normalize_piano_staffs([MultiStaff(recovered, [])], MultiStaff, events.append)
        self.assertEqual(len(systems), 6, events)
        self.assertTrue(all(len(system.staffs[0].grid[0].y) >= 5 for system in systems))

    def test_complete_page_does_not_invent_staffs(self):
        staffs = [make_staff(lines_at(411) + lines_at(540)),
                  make_staff(lines_at(1124) + lines_at(1262)),
                  make_staff(lines_at(1489) + lines_at(1627))]
        events = []
        recovered = piano_grouping.recover_five_line_staffs(
            mask(1900, 1900, lines_at(411) + lines_at(540) + lines_at(1124) + lines_at(1262)
                 + lines_at(1489) + lines_at(1627)), staffs, Staff, Point, events.append)
        self.assertEqual(len(recovered), len(staffs), events)
        systems = piano_grouping.normalize_piano_staffs([MultiStaff(recovered, [])], MultiStaff, events.append)
        self.assertEqual(len(systems), 3, events)

    def test_unpaired_staff_is_kept_instead_of_failing(self):
        staffs = [make_staff(lines_at(411) + lines_at(540)), make_staff(lines_at(768))]
        events = []
        systems = piano_grouping.normalize_piano_staffs([MultiStaff(staffs, [])], MultiStaff, events.append)
        self.assertEqual(len(systems), 2, events)
        self.assertTrue(any(event.get('event') == 'piano-layout-unpaired' for event in events))


if __name__ == '__main__':
    unittest.main(verbosity=2)
