package com.routp.fswatch;

import java.nio.file.Path;
import java.util.StringJoiner;

/**
 * Bean for a file event
 */
public class FileEvent {

    private final Path filePath;
    private final EventType eventType;
    private final long eventTime;

    FileEvent(final Path filePath, final EventType eventType) {
        this.eventTime = System.currentTimeMillis();
        this.filePath = filePath;
        this.eventType = eventType;

    }

    /**
     * Returns {@link Path} of file on which event is triggered
     *
     * @return {@link Path} of file
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * Returns {@link EventType} event triggered on file. One event out of CREATE, MODIFY, DELETE
     *
     * @return the event type
     */
    public EventType getEventType() {
        return eventType;
    }

    /**
     * The time in milliseconds at the event is triggered
     *
     * @return the event time
     */
    public long getEventTime() {
        return eventTime;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", FileEvent.class.getSimpleName() + "[", "]")
                .add("filePath=" + filePath)
                .add("eventType=" + eventType)
                .add("eventTime=" + eventTime)
                .toString();
    }
}
