"""Keep the client boss flag out of reconstructed random exploration rows."""
from collections import Counter
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'source/server'))
import battle_content
from repair_world_explorations import build


class RandomFieldEncounterTests(unittest.TestCase):
    def test_world_random_encounters_use_normal_defeat_and_keep_bosses(self):
        for mid, groups, slots in (('1110104', 13, 25), ('1110204', 15, 35)):
            with self.subTest(mission=mid):
                authored = battle_content.event_encounter(mid)
                reply = battle_content.field_mission_bundle({}, {'qo3PECw6': mid})
                random_groups = {r['2fY1IomW'] for r in reply['6v0LPiRe']}
                random_slots = [r for r in reply['Bi6hVv34'] if r['2fY1IomW'] in random_groups]
                self.assertEqual(len(random_groups), groups)
                self.assertEqual(len(random_slots), slots)
                self.assertEqual({r['3t7pZM5V'] for r in random_slots}, {'0'})
                self.assertTrue(any(count > 1 for count in
                                    Counter(r['2fY1IomW'] for r in random_slots).values()))
                self.assertEqual(reply['6v0LPiRe'], authored['6v0LPiRe'])
                self.assertTrue(any(r['3t7pZM5V'] == '1' and r['2fY1IomW'] not in random_groups
                                    for r in reply['Bi6hVv34']))

    def test_mixed_event_keeps_its_scripted_boss_rows(self):
        mid = '9001701'
        authored = battle_content.event_encounter(mid)
        scripted = {r['2fY1IomW'] for r in authored['YjHhvN65']}
        random = {r['2fY1IomW'] for r in authored['6v0LPiRe']}
        before = [dict(r) for r in authored['Bi6hVv34']]
        reply = battle_content.field_mission_bundle({}, {'qo3PECw6': mid})
        boss_rows = [r for r in before if r['2fY1IomW'] in scripted]
        self.assertTrue(any(r['3t7pZM5V'] == '2' for r in boss_rows))
        self.assertTrue(all(r in reply['Bi6hVv34'] for r in boss_rows))
        self.assertTrue(all(r['3t7pZM5V'] != '2' for r in reply['Bi6hVv34']
                            if r['2fY1IomW'] in random))
        self.assertEqual(authored['Bi6hVv34'], before)

    def test_rebuilt_world_formations_use_normal_defeat(self):
        rebuilt = build()
        self.assertTrue(rebuilt)
        for mid, (bundle, _) in rebuilt.items():
            with self.subTest(mission=mid):
                self.assertEqual({r['3t7pZM5V'] for r in bundle['Bi6hVv34']}, {'0'})


    def test_all_catalogued_regular_field_groups_avoid_the_boss_flag(self):
        path = ROOT / 'source/server/event_encounters.json'
        entries = json.loads(path.read_bytes())['missions']
        native = {tuple(row[key] for key in ('2fY1IomW', 'Z0EN6jSh', 'xqartN26')): row
                  for row in battle_content.IndexSource().load('F_BATTLE_GROUP_MST')}
        for mid, entry in entries.items():
            authored = entry['bundle']
            if not authored.get('6v0LPiRe'):
                continue
            with self.subTest(mission=mid):
                reply = battle_content.field_mission_bundle({}, {'qo3PECw6': mid})
                self.assertEqual(reply['6v0LPiRe'], authored['6v0LPiRe'])
                random = {row['2fY1IomW'] for row in reply['6v0LPiRe']}
                scripted = {row['2fY1IomW'] for row in reply.get('YjHhvN65', [])}
                groups = {}
                for row in reply['Bi6hVv34']:
                    groups.setdefault(row['2fY1IomW'], []).append(row)
                for gid in random - scripted:
                    rows = groups[gid]
                    for row in rows:
                        flag = row['3t7pZM5V']
                        if flag != '0':
                            # Rare single-enemy encounters may keep their native boss flag.
                            key = (gid, row['Z0EN6jSh'], row['xqartN26'])
                            self.assertEqual(len(rows), 1)
                            self.assertEqual(flag, '1')
                            self.assertEqual(native[key]['3t7pZM5V'], flag)


if __name__ == '__main__':
    unittest.main()
