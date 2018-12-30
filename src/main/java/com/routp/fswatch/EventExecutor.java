package com.routp.fswatch;

/**
 * This enum defines supported kind of executors to execute {@link FileEventHandler#onEvent(FileEvent)} event when a
 * subscribed event triggered on a registered file.
 * <p>
 * FS_WATCHER - The file system watch thread itself will be used to call {@link FileEventHandler#onEvent(FileEvent)}.
 * If multiple events are triggered for a single transaction (usually for a MODIFY) this may call
 * {@link FileEventHandler#onEvent(FileEvent)} multiple times.
 * </p>
 * <p>
 * DELAY_EXECUTOR - This executor avoids redundant calls to {@link FileEventHandler#onEvent(FileEvent)} when multiple
 * events are triggered as part of single transaction on file. The default event executor
 * </p>
 * <p>
 * ASYNC_EXECUTOR - A new on-demand thread will be created to call {@link FileEventHandler#onEvent(FileEvent)} when a
 * subscribed event triggered on a registered file.
 * </p>
 * <p>
 * The default executor is {@link EventExecutor#DELAY_EXECUTOR} when no executor is specified and default event type
 * is ALL (CREATE, DELETE, MODIFY) when event types are specified
 * </p>
 * All the shared resources inside {@link FileEventHandler#onEvent(FileEvent)} must be synchronized to avoid data
 * inconsistencies and {@link FileEventHandler#onEvent(FileEvent)} must finish gracefully to avoid any kind of race
 * between the executor threads
 */
public enum EventExecutor {
    FS_WATCHER, DELAY_EXECUTOR, ASYNC_EXECUTOR
}
