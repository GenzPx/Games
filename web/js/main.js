import { ThinAir } from "./game.js";

const game = new ThinAir();
game.start().catch((err) => {
  console.error(err);
  const brief = document.getElementById("screen-load");
  if (brief) {
    brief.classList.add("on");
    brief.querySelector("p").textContent = String(err);
  }
});
