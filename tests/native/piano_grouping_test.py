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


class SlantedPoint(Point):
    """homr StaffPoint carries an angle; a collapsed sliver carries a noisy one."""

    def __init__(self, x, y, angle=0.0):
        super().__init__(x, y, angle)
        self.angle = angle


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

    def test_narrow_fragment_is_dropped_before_decoding(self):
        # Merry Christmas Mr. Lawrence page 2: a barline block was reported as a
        # 20px wide staff and a leftover fragment as 240px wide.  homr then failed
        # in cv2.resize with "inv_scale_x > 0", aborting the whole page.
        wide = [make_staff(lines_at(228) + lines_at(397)),
                make_staff(lines_at(623) + lines_at(797)),
                make_staff(lines_at(1428) + lines_at(1596)),
                make_staff(lines_at(1828) + lines_at(2007)),
                make_staff(lines_at(2238) + lines_at(2407))]
        sliver = Staff([Point(x, lines_at(1030) + lines_at(1196)) for x in (1810.0, 1830.0)], 28)
        fragment = Staff([Point(x, lines_at(1195)) for x in (110.0, 350.0)], 6)
        events = []
        systems = piano_grouping.normalize_piano_staffs(
            [MultiStaff([wide[0], wide[1], sliver, fragment] + wide[2:], [])], MultiStaff, events.append)
        self.assertEqual(len(systems), 6, events)
        dropped = [event for event in events if event.get('event') == 'piano-layout-dropped']
        self.assertTrue(dropped, events)
        self.assertEqual(len(dropped[0]['staffs']), 1)
        self.assertEqual(dropped[0]['staffs'][0]['notes'], 6)
        widened = [event for event in events if event.get('event') == 'piano-layout-widened']
        self.assertTrue(widened, events)
        self.assertEqual(widened[0]['left'], 160.0)
        self.assertEqual(widened[0]['right'], 1776.0)

    def test_widening_never_stretches_a_staff_vertically(self):
        # Phone run 2026-11-12: the 20px sliver carried a noisy angle, and
        # extrapolating it across the page grew the staff to bottom=6585,
        # which made cv2.resize fail again on page 2.
        wide = [make_staff(lines_at(228) + lines_at(397)),
                make_staff(lines_at(623) + lines_at(797)),
                make_staff(lines_at(1428) + lines_at(1596)),
                make_staff(lines_at(1828) + lines_at(2007)),
                make_staff(lines_at(2238) + lines_at(2407))]
        ys = lines_at(1030) + lines_at(1196)
        slanted = Staff([SlantedPoint(1810.0, ys, 1.4), SlantedPoint(1830.0, ys, 1.4)], 28)
        events = []
        systems = piano_grouping.normalize_piano_staffs(
            [MultiStaff([wide[0], wide[1], slanted] + wide[2:], [])], MultiStaff, events.append)
        self.assertEqual(len(systems), 6, events)
        for system in systems:
            staff = system.staffs[0]
            height = staff.max_y - staff.min_y
            self.assertLess(height, 400, 'staff must keep its original vertical span')
            self.assertGreaterEqual(staff.max_x - staff.min_x, 1600)

    def test_collapsed_line_extent_is_widened_instead_of_losing_systems(self):
        # Merry Christmas Mr. Lawrence page 1: the first two systems were reported
        # only 110px wide although they carry the whole row of music.  Dropping
        # them removed bars 1-4 and shifted every later bar number.
        staffs = [Staff([Point(x, lines_at(389) + lines_at(522)) for x in (190.0, 300.0)], 27),
                  Staff([Point(x, lines_at(856) + lines_at(989)) for x in (110.0, 220.0)], 28),
                  make_staff(lines_at(1323) + lines_at(1456), notes=32),
                  make_staff(lines_at(1786) + lines_at(1919), notes=31),
                  make_staff(lines_at(2258) + lines_at(2391), notes=38)]
        events = []
        systems = piano_grouping.normalize_piano_staffs([MultiStaff(staffs, [])], MultiStaff, events.append)
        self.assertEqual(len(systems), 5, events)
        self.assertFalse([event for event in events if event.get('event') == 'piano-layout-dropped'], events)
        widened = [event for event in events if event.get('event') == 'piano-layout-widened']
        self.assertTrue(widened, events)
        self.assertEqual(len(widened[0]['staffs']), 2)
        for system, expected_notes in zip(systems, (27, 28, 32, 31, 38)):
            staff = system.staffs[0]
            self.assertGreaterEqual(staff.max_x - staff.min_x, 1600)
            self.assertEqual(len(staff.get_notes()), expected_notes, 'widening must keep the assigned notes')


if __name__ == '__main__':
    unittest.main(verbosity=2)
