package com.routp.fswatch;

import java.nio.file.Path;

/**
 * The type of interface {@link FileEventHandler} will be invoked to do the required processing as an action to the event.
 */
@FunctionalInterface
public interface FileEventHandler {
    /**
     * On an event it sets the event information in {@link FileEvent} object containing {@link EventType} event type
     * and file {@link Path} on which event is triggered.
     *
     * @param fileEvent {@link FileEvent} object
     */
    void onEvent(FileEvent fileEvent);
}
