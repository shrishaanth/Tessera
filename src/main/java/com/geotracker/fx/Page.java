package com.geotracker.fx;

import javafx.scene.Node;

/** A top-level screen reachable from the sidebar. */
public interface Page {
    String title();

    /** SVG path (24x24) used in the navigation sidebar. */
    String icon();

    Node view();

    /** Called after the page becomes visible (map attached, layers configured). */
    default void onShow() {}

    default void onHide() {}

    /** Live data, delivered on the FX thread while the page is visible. */
    default void onFrame(LiveData.Frame frame) {}

    /** Whether the page shows the shared map (and therefore needs vehicle payloads). */
    default boolean usesMap() {
        return false;
    }
}
