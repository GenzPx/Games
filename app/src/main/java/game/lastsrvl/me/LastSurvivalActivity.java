package game.lastsrvl.me;

import org.libsdl.app.SDLActivity;

public class LastSurvivalActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected String getMainFunction() {
        return "SDL_main";
    }
}
