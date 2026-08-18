import * as THREE from "three";

/** Chunky stylized climber — readable like PEAK, red down-suit from the title still. */
export function createClimber() {
  const root = new THREE.Group();
  root.name = "climber";

  const skin = new THREE.MeshStandardMaterial({ color: 0xe0b08a, roughness: 0.7 });
  const suit = new THREE.MeshStandardMaterial({ color: 0xc81e1e, roughness: 0.55 });
  const navy = new THREE.MeshStandardMaterial({ color: 0x1b2433, roughness: 0.7 });
  const pack = new THREE.MeshStandardMaterial({ color: 0xd9782a, roughness: 0.6 });
  const boot = new THREE.MeshStandardMaterial({ color: 0x2a2118, roughness: 0.85 });
  const metal = new THREE.MeshStandardMaterial({ color: 0xb7c0c8, metalness: 0.7, roughness: 0.3 });

  const hips = new THREE.Group();
  const torso = new THREE.Mesh(new THREE.CapsuleGeometry(0.28, 0.42, 4, 8), suit);
  torso.position.y = 1.18;
  torso.castShadow = true;
  const belly = new THREE.Mesh(new THREE.SphereGeometry(0.3, 10, 8), suit);
  belly.scale.set(1, 0.75, 0.85);
  belly.position.y = 1.02;
  const head = new THREE.Mesh(new THREE.SphereGeometry(0.2, 12, 10), skin);
  head.position.y = 1.62;
  head.castShadow = true;
  const hat = new THREE.Mesh(new THREE.SphereGeometry(0.21, 10, 8), navy);
  hat.scale.set(1, 0.7, 1);
  hat.position.y = 1.74;
  const brim = new THREE.Mesh(new THREE.CylinderGeometry(0.23, 0.23, 0.04, 12), navy);
  brim.position.y = 1.66;
  const goggle = new THREE.Mesh(new THREE.BoxGeometry(0.22, 0.06, 0.08), metal);
  goggle.position.set(0, 1.64, 0.16);

  const backpack = new THREE.Mesh(new THREE.BoxGeometry(0.32, 0.4, 0.18), pack);
  backpack.position.set(0, 1.2, -0.28);
  backpack.castShadow = true;
  const bottle = new THREE.Mesh(new THREE.CylinderGeometry(0.045, 0.045, 0.28, 8), metal);
  bottle.position.set(0.2, 1.28, -0.28);

  function limb(len, r, mat) {
    const m = new THREE.Mesh(new THREE.CapsuleGeometry(r, len, 3, 6), mat);
    m.castShadow = true;
    return m;
  }

  const lArm = new THREE.Group();
  const lArmM = limb(0.32, 0.07, suit);
  lArmM.position.y = -0.18;
  lArm.add(lArmM);
  lArm.position.set(-0.34, 1.34, 0);
  const lHand = new THREE.Mesh(new THREE.SphereGeometry(0.07, 8, 6), navy);
  lHand.position.y = -0.4;
  lArm.add(lHand);

  const rArm = new THREE.Group();
  const rArmM = limb(0.32, 0.07, suit);
  rArmM.position.y = -0.18;
  rArm.add(rArmM);
  rArm.position.set(0.34, 1.34, 0);
  const rHand = new THREE.Mesh(new THREE.SphereGeometry(0.07, 8, 6), navy);
  rHand.position.y = -0.4;
  rArm.add(rHand);

  const lLeg = new THREE.Group();
  const lLegM = limb(0.38, 0.085, navy);
  lLegM.position.y = -0.22;
  lLeg.add(lLegM);
  lLeg.position.set(-0.14, 0.78, 0);
  const lBoot = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.1, 0.22), boot);
  lBoot.position.set(0, -0.48, 0.04);
  lLeg.add(lBoot);

  const rLeg = new THREE.Group();
  const rLegM = limb(0.38, 0.085, navy);
  rLegM.position.y = -0.22;
  rLeg.add(rLegM);
  rLeg.position.set(0.14, 0.78, 0);
  const rBoot = new THREE.Mesh(new THREE.BoxGeometry(0.14, 0.1, 0.22), boot);
  rBoot.position.set(0, -0.48, 0.04);
  rLeg.add(rBoot);

  const axe = new THREE.Group();
  const shaft = new THREE.Mesh(new THREE.CylinderGeometry(0.018, 0.02, 0.7, 6), boot);
  const head = new THREE.Mesh(new THREE.BoxGeometry(0.18, 0.04, 0.04), metal);
  head.position.y = 0.34;
  const pick = new THREE.Mesh(new THREE.ConeGeometry(0.02, 0.12, 5), metal);
  pick.rotation.z = Math.PI / 2;
  pick.position.set(-0.12, 0.34, 0);
  axe.add(shaft, head, pick);
  axe.position.set(0.38, 1.05, 0.08);
  axe.rotation.z = 0.15;

  hips.add(torso, belly, head, hat, brim, goggle, backpack, bottle, lArm, rArm, lLeg, rLeg, axe);
  root.add(hips);

  root.userData.parts = { lArm, rArm, lLeg, rLeg, axe, hips, head };
  return root;
}

export function poseClimber(root, state, dt) {
  const p = root.userData.parts;
  if (!p) return;
  const t = state.time;
  const walk = state.speed > 0.4 && state.grounded && !state.climbing;
  const climb = state.climbing;
  const fall = !state.grounded && !state.climbing && state.vy < -2;

  let wl = 0, wr = 0, al = 0, ar = 0;
  if (walk) {
    const s = Math.sin(t * 9);
    wl = s * 0.7;
    wr = -s * 0.7;
    al = -s * 0.55;
    ar = s * 0.55;
  } else if (climb) {
    const s = Math.sin(t * 7);
    al = -0.9 + s * 0.7;
    ar = -0.9 - s * 0.7;
    wl = 0.4 - s * 0.35;
    wr = 0.4 + s * 0.35;
    p.hips.position.z = -0.08;
  } else if (fall) {
    al = -2.4 + Math.sin(t * 8) * 0.4;
    ar = -2.1 - Math.sin(t * 7) * 0.4;
    wl = 0.6;
    wr = -0.3;
  } else {
    const b = Math.sin(t * 2.2) * 0.03;
    p.hips.position.y = b;
    al = 0.12;
    ar = 0.12;
  }
  if (!climb) p.hips.position.z = 0;

  p.lLeg.rotation.x = THREE.MathUtils.damp(p.lLeg.rotation.x, wl, 12, dt);
  p.rLeg.rotation.x = THREE.MathUtils.damp(p.rLeg.rotation.x, wr, 12, dt);
  p.lArm.rotation.x = THREE.MathUtils.damp(p.lArm.rotation.x, al, 12, dt);
  p.rArm.rotation.x = THREE.MathUtils.damp(p.rArm.rotation.x, ar, 12, dt);
  p.axe.visible = climb || state.slope > 38;
  p.axe.rotation.x = climb ? -0.8 + Math.sin(t * 7) * 0.5 : 0.1;
}
