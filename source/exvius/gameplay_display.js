'use strict';

// Only active map/battle scenes count as gameplay. Mission repeat also keeps
// the display awake across the result and loading screens between runs.
const SCENES = [
  ['8MapScene', false],
  ['11BattleScene', true],
  ['18EncountBattleScene', true],
  ['15WaveBattleScene', true],
  ['13RbBattleScene', true],
  ['13RmBattleScene', true],
  ['18DmgRankBattleScene', true],
  ['20ColosseumBattleScene', true],
];

export function installGameplayDisplay(module, note, retained, bridge, missionRepeatActive) {
  const readBattleState = new NativeFunction(module.getExportByName('_ZN11BattleState6sharedEv'),
    'pointer', [], { exceptions: 'propagate' });
  const isAutoRepeatBattle = new NativeFunction(module.getExportByName('_ZNK11BattleState21getIsAutoRepeatBattleEv'),
    'bool', ['pointer'], { exceptions: 'propagate' });
  const update = module.getExportByName('_ZN9GameScene6updateEv');
  const visible = new Map();
  const attached = new Set();
  for (const [name, isBattle] of SCENES) {
    for (const [method, show] of [['14onSceneVisible', true], ['16onSceneInvisible', false], ['D2', false]]) {
      const address = module.findExportByName('_ZN' + name + method + 'Ev');
      if (address === null || attached.has(address.toString())) continue;
      attached.add(address.toString());
      retained.push(Interceptor.attach(address, {
        onEnter(args) {
          const scene = args[0].toString();
          if (show) visible.set(scene, isBattle);
          else visible.delete(scene);
        },
      }));
    }
  }

  let nextSample = 0;
  let lastMode = -1;
  let reportedFailure = false;
  retained.push(Interceptor.attach(update, {
    onLeave() {
      const now = Date.now();
      if (now < nextSample && nextSample - now <= 250) return;
      nextSample = now + 250;
      try {
        const repeating = missionRepeatActive();
        let fullAuto = repeating;
        if (!fullAuto && Array.from(visible.values()).includes(true)) {
          const state = readBattleState();
          fullAuto = !state.isNull() && isAutoRepeatBattle(state);
        }
        const awake = repeating || visible.size > 0;
        const mode = (awake ? 1 : 0) | (fullAuto ? 2 : 0);
        if (mode === lastMode) return;
        if (bridge('updateGameplayDisplay', '(II)Z', [awake ? 1 : 0, fullAuto ? 1 : 0], 'bool'))
          lastMode = mode;
      } catch (error) {
        if (!reportedFailure) note('GAMEPLAY_DISPLAY_FAILED ' + error);
        reportedFailure = true;
      }
    },
  }));
}
