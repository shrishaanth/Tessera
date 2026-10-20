package com.geotracker;

import javafx.application.Application;

/**
 * Entry point used when JavaFX is on the class path (see the javafx-maven-plugin configuration).
 * The launcher must not extend {@link Application}: if the main class does, the JDK insists on loading
 * JavaFX as named modules, which fails on JDK 24+ because jdk.jsobject is no longer part of the JDK.
 */
public final class TesseraLauncher {
    private TesseraLauncher() {}

    public static void main(String[] args) {
        Application.launch(TesseraApp.class, args);
    }
}
