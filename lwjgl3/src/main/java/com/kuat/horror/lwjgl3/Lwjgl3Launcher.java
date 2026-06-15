package com.kuat.horror.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.kuat.horror.HorrorGame;

public class Lwjgl3Launcher {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setForegroundFPS(60);
        cfg.setTitle("ОСОБНЯК — Super Horror");
        cfg.setWindowedMode(760, 760);
        new Lwjgl3Application(new HorrorGame(), cfg);
    }
}
