// Terrain snow/rock blend — referenced by MeshStandardMaterial.onBeforeCompile
// in web/js/terrain.js. Kept here so the GLSL lives in-repo.

uniform sampler2D snowMap;

void blend_snow() {
  vec4 snowSample = texture2D(snowMap, vMapUv);
  float snowAmt = vColor.r;
  diffuseColor.rgb = mix(diffuseColor.rgb, snowSample.rgb, snowAmt);
}
