package com.routp.fswatch;

import static com.routp.fswatch.EventType.ALL;
import static com.routp.fswatch.EventType.CREATE;
import static com.routp.fswatch.EventType.DELETE;
import static com.routp.fswatch.EventType.MODIFY;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.nio.file.SensitivityWatchEventModifier;

/**
 * This class polls for create, modify and delete events on the files of a directory that is being watched by
 * {@link WatchService}. On such events it invokes the registered handler {@link FileEventHandler} to take the required
 * action.
 */
public final class FileSystemWatcher {

    private static final Logger logger = Logger.getLogger(FileSystemWatcher.class.getName());
    private static final int TIME_ELAPSED_SINCE_LAST_EVENT_TIME = 150;
    private static final int EVENT_PROCESSOR_EXEC_INTERVAL_IN_MILLIS = 100;
    private static final String WATCH_EVENT_DELAY_EXECUTOR_NAME = "FSW_DELAY_EXECUTOR";
    private static final String WATCH_EVENT_ASYNC_EXECUTOR_NAME = "FSW_ASYNC_EXECUTOR";
    private final Map<String, FileEvent> fileEventMap = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> watchKeyDirsMap = new HashMap<>();

    private final Kind<?>[] registeredEventKinds;
    private final FileEventHandler eventHandler;
    private final Set<String> eventOnFiles;
    private final EventExecutor eventExecutor;
    private final int delayExecutorPoolSize;
    private final String watcherThreadName;
    private final WatchService watchService;
    private final WatchEvent.Modifier eventModifier;

    private ExecutorService watchExecutorService;
    private ScheduledExecutorService delayEventExecutorService;
    private boolean isWatching;

    /**
     * Private constructor
     *
     * @param watchDirPaths         directories path to be watched
     * @param registeredEventTypes  event types to be monitored
     * @param eventHandler          {@link FileEventHandler} to be called as action to an event triggered
     * @param eventOnFiles          list of files on which events are tracked
     * @param watcherThreadName     a custom name for watcher service thread factory, optional
     * @param eventExecutor         {@link EventExecutor} type
     * @param delayExecutorPoolSize scheduled executor thread pool size
     * @param eventModifier         watch event modifier
     * @throws FileWatchException any exception raised during file system watch operation
     */
    private FileSystemWatcher(final Set<Path> watchDirPaths,
                              final Kind[] registeredEventTypes,
                              final FileEventHandler eventHandler,
                              final Set<String> eventOnFiles,
                              final String watcherThreadName,
                              final EventExecutor eventExecutor,
                              final int delayExecutorPoolSize,
                              final WatchEvent.Modifier eventModifier) throws FileWatchException {
        this.registeredEventKinds = registeredEventTypes;
        this.eventHandler = eventHandler;
        this.eventOnFiles = eventOnFiles;
        this.watcherThreadName = watcherThreadName;
        this.eventExecutor = eventExecutor;
        this.delayExecutorPoolSize = delayExecutorPoolSize;
        this.eventModifier = eventModifier;

        // Create a watch service
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("File system watch service initialized.");
            }
        } catch (IOException e) {
            throw new FileWatchException("Failed to create file system watch service " + e.getMessage(), e);
        }

        // Register watch service and events to the target path
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Directory list to be registered to watch " + watchDirPaths);
        }
        for (Path targetPath : watchDirPaths) {
            try {
                // register directory to be watched
                WatchKey watchKey = targetPath.register(watchService, registeredEventKinds, eventModifier);
                // Store watch key and corresponding directory path
                this.watchKeyDirsMap.put(watchKey, targetPath);
            } catch (IOException e) {
                throw new FileWatchException(
                        "Failed to register watch service and events to the directory " + targetPath, e);
            }
        }
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Watch key and corresponding registered directory " + this.watchKeyDirsMap);
        }
    }

    /**
     * Starts the watch service thread
     */
    public void startWatching() {
        // If watch service thread is already running. do nothing
        if (watchExecutorService != null && !watchExecutorService.isShutdown()) {
            logger.info("File system watch service is already running.");
            return;
        }
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(this::toString);
        }
        isWatching = true;

        // Create a thread pool for async executor
        ThreadFactory threadFactory = null;
        if (EventExecutor.ASYNC_EXECUTOR == this.eventExecutor) {
            threadFactory = new FsWatchThreadFactory(WATCH_EVENT_ASYNC_EXECUTOR_NAME);
        }
        final ThreadFactory asyncExecutorThdFactory = threadFactory;

        // A single thread executor for watching service
        final FsWatchThreadFactory fileWatcherFactory =
                new FsWatchThreadFactory(this.watcherThreadName);
        this.watchExecutorService = Executors.newFixedThreadPool(1, fileWatcherFactory);


        this.watchExecutorService.submit(() -> {
            logger.info("File system watch service thread started.");
            while (isWatching) {
                WatchKey watchKey;
                try {
                    // wait for the availability of watch key
                    watchKey = watchService.take();
                } catch (InterruptedException e) {
                    logger.log(Level.INFO, "File system watch service thread interrupted.");
                    Thread.currentThread().interrupt();
                    return;
                }

                // Continue if watch key or path not found in the map
                Path parentDirPath = watchKeyDirsMap.getOrDefault(watchKey, null);
                if (parentDirPath == null) {
                    continue;
                }

                // Poll for the events
                for (WatchEvent<?> watchEvent : watchKey.pollEvents()) {
                    // Get type of event
                    Kind<?> kind = watchEvent.kind();

                    // Get path of file on which event was triggered
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) watchEvent;
                    Path eventFilename = ev.context();
                    Path eventFilePath = parentDirPath.resolve(eventFilename).toAbsolutePath();
                    String strEventFilePath = eventFilePath != null ? eventFilePath.toString() : "";
                    if (logger.isLoggable(Level.FINE)) {
                        logger.fine("Watch event of type " + kind.name() + " triggered on file " + eventFilePath);
                    }

                    // Store only the events on registered files into the map
                    if (eventOnFiles.contains(strEventFilePath)) {
                        logger.info("Watch event " + kind.name() + " triggered on registered file " + eventFilePath);
                        try {
                            final FileEvent fileEvent;
                            if (ENTRY_MODIFY == kind) {
                                // Event kind is modify
                                fileEvent = new FileEvent(eventFilePath, MODIFY);
                            } else if (ENTRY_CREATE == kind) {
                                // Event kind is create
                                fileEvent = new FileEvent(eventFilePath, CREATE);
                            } else if (ENTRY_DELETE == kind) {
                                // Event kind is delete
                                fileEvent = new FileEvent(eventFilePath, DELETE);
                            } else {
                                continue;
                            }

                            // Process events by calling event handler
                            if (EventExecutor.DELAY_EXECUTOR == this.eventExecutor) {
                                // Delay event executor execute FileEventHandler#onEvent() asynchronously
                                fileEventMap.put(strEventFilePath, fileEvent);
                            } else if (EventExecutor.ASYNC_EXECUTOR == this.eventExecutor) {
                                // A new on-demand thread will be created to execute FileEventHandler#onEvent()
                                // asynchronously
                                //new Thread(() -> eventHandler.onEvent(fileEvent)).start();
                                asyncExecutorThdFactory.newThread(() -> eventHandler.onEvent(fileEvent)).start();
                            } else if (EventExecutor.FS_WATCHER == this.eventExecutor) {
                                // watcher thread itself execute FileEventHandler#onEvent() synchronously
                                eventHandler.onEvent(fileEvent);
                            } else {
                                // Won't happen as default is FS_WATCHER. If any future kind of executor will be added
                                logger.warning("No executor type found. Event processing is ignored.");
                            }
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "Failed to process event: " + e.getMessage(), e);
                        }
                    }
                }
                // The key must be reset after processed. This step is critical to receive further watch events.
                // If the key is no longer valid, the directory is inaccessible so exit the loop.
                if (!watchKey.reset()) {
                    break;
                }
            }
        });

        if (EventExecutor.DELAY_EXECUTOR == this.eventExecutor) {
            // Execute the accumulated events after the specified delay from their last generated time to avoid multiple
            // callbacks on a single event.
            FsWatchThreadFactory delayEventExecutorFactory =
                    new FsWatchThreadFactory(WATCH_EVENT_DELAY_EXECUTOR_NAME);
            this.delayEventExecutorService = Executors.newScheduledThreadPool(this.delayExecutorPoolSize,
                    delayEventExecutorFactory);
            this.delayEventExecutorService.scheduleAtFixedRate(() -> {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("Delay event executor is now scheduled to process events...");
                }
                final long currentTimeMillis = System.currentTimeMillis();
                final Set<String> keys = fileEventMap.keySet();
                for (String key : keys) {
                    FileEvent fileEvent = fileEventMap.get(key);
                    if (currentTimeMillis - fileEvent.getEventTime() > TIME_ELAPSED_SINCE_LAST_EVENT_TIME) {
                        fileEvent = fileEventMap.remove(key);
                        if (logger.isLoggable(Level.FINE)) {
                            logger.fine("Delay executor is invoking FileEventHandler#onEvent for event " + fileEvent);
                        }
                        eventHandler.onEvent(fileEvent);
                    }
                }
            }, 0, EVENT_PROCESSOR_EXEC_INTERVAL_IN_MILLIS, TimeUnit.MILLISECONDS);
            logger.info("Delay event executor thread started.");
        }
    }

    /**
     * Stops the watch service thread
     */
    public void stopWatching() {
        try {
            if (watchExecutorService != null && !watchExecutorService.isShutdown()) {
                this.graceShutdownExecutorService(watchExecutorService, 500, TimeUnit.MILLISECONDS);
            }
        } finally {
            this.isWatching = false;
            if (delayEventExecutorService != null && !delayEventExecutorService.isShutdown()) {
                this.graceShutdownExecutorService(delayEventExecutorService, 1, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Returns {@code true} if File system watcher service instance is currently running otherwise false
     *
     * @return {@code true} if running {@code false} not running
     */
    public boolean isWatching() {
        return this.isWatching;
    }

    /**
     * Shuts down an executor service thread pool gracefully
     *
     * @param executorService the executor service thread pool to be shut down
     * @param timeout         maximum time to wait
     * @param timeUnit        {@code true} if this executor terminated and
     *                        {@code false} if the timeout elapsed before termination
     */
    private void graceShutdownExecutorService(ExecutorService executorService, long timeout, TimeUnit timeUnit) {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(timeout, timeUnit)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Creates a {@link Builder} object of {@link FileSystemWatcher}
     *
     * @return {@code Builder} object
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for {@link FileSystemWatcher}
     */
    public static class Builder {

        private final Set<Path> watchDirPaths = new HashSet<>();
        private static final String DEF_WATCHER_THREAD_NAME = "FileSystemWatcher";
        static final int MAX_SCHEDULED_EXEC_POOL_SIZE = 5;

        private final Set<String> eventOnFiles = new HashSet<>();
        private final Set<EventType> registeredEvents = new HashSet<>();
        private FileEventHandler eventHandler;
        private String name;
        private int delayExecutorPoolSize;
        private EventExecutor eventExecutor;

        private EventExecutor eventExecutorUsed;
        private int delayExecutorPoolSizeUsed;
        private EventModifier eventModifier;

        public Builder() {
        }

        /**
         * Adds a file to the list, if there is an event on this file then handler will be called
         *
         * @param filePath the absolute path of file
         * @return {@code Builder} object
         */
        public Builder addFilePath(final String filePath) {
            this.eventOnFiles.add(filePath);
            return this;
        }

        /**
         * Adds a supported event type defined in {@link EventType}
         *
         * @param eventType the event type
         * @return {@code Builder} object
         */
        public Builder addEvent(final EventType eventType) {
            this.registeredEvents.add(eventType);
            return this;
        }

        /**
         * The {@link FileEventHandler} type to be called on an event
         *
         * @param eventHandler {@link FileEventHandler} object
         * @return {@code Builder} object
         */
        public Builder eventHandler(final FileEventHandler eventHandler) {
            this.eventHandler = eventHandler;
            return this;
        }

        /**
         * A user defined name for the File System Watcher thread
         *
         * @param name name of thread
         * @return {@code Builder} object
         */
        public Builder name(final String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the {@link EventExecutor} type. If no executor is specified then {@link EventExecutor#DELAY_EXECUTOR}
         * will be used.
         *
         * @param eventExecutor {@link EventExecutor} type
         * @return {@code Builder} object
         */
        public Builder eventExecutor(final EventExecutor eventExecutor) {
            this.eventExecutor = eventExecutor;
            return this;
        }

        /**
         * Event executor thread pool size. Default is 1 and maximum is MAX_SCHEDULED_EXEC_POOL_SIZE. Default single
         * thread should be fine for less number of watch directories.
         *
         * @param delayExecutorPoolSize event executor thread pool size
         * @return {@code Builder} object
         */
        public Builder delayExecutorPoolSize(final int delayExecutorPoolSize) {
            this.delayExecutorPoolSize = delayExecutorPoolSize;
            return this;
        }

        /**
         * Event modifier type for FileSystem watch
         *
         * @param eventModifier {@link EventModifier} type
         * @return {@code Builder} object
         */
        public Builder eventModifier(final EventModifier eventModifier) {
            this.eventModifier = eventModifier;
            return this;
        }

        /**
         * Builds a {@link FileSystemWatcher} object
         *
         * @return {@link FileSystemWatcher} object
         * @throws FileWatchException any exception raised during creating the {@code FileSystemWatcher} object
         */
        public FileSystemWatcher build() throws FileWatchException {
            if (this.eventHandler == null) {
                throw new FileWatchException("No handlers are provided for callback.");
            }
            if (this.registeredEvents.isEmpty()) {
                this.registeredEvents.add(ALL);
            }
            if (this.eventOnFiles.isEmpty()) {
                throw new FileWatchException("No files are registered for event notification.");
            }
            // Find all the distinct directories to be watched
            for (String strFilePath : this.eventOnFiles) {
                Path filePath;
                if (strFilePath != null && !strFilePath.isEmpty() && (filePath = Paths.get(strFilePath)) != null) {
                    Path parentPath = filePath.getParent();
                    if (parentPath != null && Files.exists(parentPath)) {
                        watchDirPaths.add(parentPath);
                    } else {
                        throw new FileWatchException("Parent Directory "
                                + parentPath + " of file " + strFilePath + " does not exist.");
                    }
                } else {
                    throw new FileWatchException("File " + strFilePath + " is not a valid file path.");
                }
            }
            // If directory set is empty
            if (watchDirPaths.isEmpty()) {
                throw new FileWatchException("No valid directory found for watching.");
            }

            // watcher thread factory name
            final String watcherFactoryName = (this.name != null && !this.name.isEmpty()) ? this.name.trim() :
                    DEF_WATCHER_THREAD_NAME;

            Set<Kind<Path>> eventKindSet = new HashSet<>();
            for (EventType eventType : this.registeredEvents) {
                if (CREATE == eventType) {
                    eventKindSet.add(ENTRY_CREATE);
                } else if (MODIFY == eventType) {
                    eventKindSet.add(ENTRY_MODIFY);
                    eventKindSet.add(ENTRY_CREATE);
                } else if (DELETE == eventType) {
                    eventKindSet.add(ENTRY_DELETE);
                } else if (ALL == eventType) {
                    eventKindSet.add(ENTRY_CREATE);
                    eventKindSet.add(ENTRY_MODIFY);
                    eventKindSet.add(ENTRY_DELETE);
                    break;
                } else {
                    throw new FileWatchException("Unsupported event type [" + eventType + "] found.");
                }
            }
            Kind[] eventKinds = eventKindSet.toArray(new Kind[]{});

            // Event executor to be used
            if (this.eventExecutor == null) {
                logger.info("Event executor type is not specified. Using the DELAY_EXECUTOR as default executor.");
                this.eventExecutorUsed = EventExecutor.DELAY_EXECUTOR;
            } else {
                this.eventExecutorUsed = this.eventExecutor;
            }

            if (EventExecutor.DELAY_EXECUTOR == this.eventExecutorUsed) {
                // Delay executor pool size. Default is 1 and Max is MAX_SCHEDULED_EXEC_POOL_SIZE
                if (this.delayExecutorPoolSize > MAX_SCHEDULED_EXEC_POOL_SIZE) {
                    logger.info("Setting delay executor pool size to " + MAX_SCHEDULED_EXEC_POOL_SIZE
                            + " instead of " + this.delayExecutorPoolSize);
                }
                this.delayExecutorPoolSizeUsed = (this.delayExecutorPoolSize <= 0) ? 1 :
                        (this.delayExecutorPoolSize > MAX_SCHEDULED_EXEC_POOL_SIZE ?
                                MAX_SCHEDULED_EXEC_POOL_SIZE : this.delayExecutorPoolSize);
            }

            WatchEvent.Modifier modifier;
            if (EventModifier.HIGH == this.eventModifier) {
                modifier = SensitivityWatchEventModifier.HIGH;
            } else if (EventModifier.LOW == this.eventModifier) {
                modifier = SensitivityWatchEventModifier.LOW;
            } else {
                modifier = SensitivityWatchEventModifier.MEDIUM;
            }

            if (logger.isLoggable(Level.FINE)) {
                logger.fine(this::toString);
            }
            return new FileSystemWatcher(watchDirPaths, eventKinds, this.eventHandler, this.eventOnFiles,
                    watcherFactoryName, this.eventExecutorUsed, this.delayExecutorPoolSizeUsed, modifier);
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", Builder.class.getSimpleName() + "[", "]")
                    .add("watchDirPaths=" + watchDirPaths)
                    .add("eventOnFiles=" + eventOnFiles)
                    .add("registeredEvents=" + registeredEvents)
                    .add("eventHandler=" + eventHandler)
                    .add("name='" + name + "'")
                    .add("eventExecutor=" + eventExecutor)
                    .add("eventExecutorUsed=" + eventExecutorUsed)
                    .add("delayExecutorPoolSize=" + delayExecutorPoolSize)
                    .add("delayExecutorPoolSizeUsed=" + delayExecutorPoolSizeUsed)
                    .add("eventModifier=" + eventModifier)
                    .toString();
        }
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", FileSystemWatcher.class.getSimpleName() + "[", "]")
                .add("registeredEventKinds=" + Arrays.toString(registeredEventKinds))
                .add("eventHandler=" + eventHandler)
                .add("eventOnFiles=" + eventOnFiles)
                .add("eventExecutor=" + eventExecutor)
                .add("delayExecutorPoolSize=" + delayExecutorPoolSize)
                .add("watcherThreadName=" + watcherThreadName)
                .add("isWatching=" + isWatching)
                .add("fileEventMap=" + fileEventMap)
                .add("watchExecutorService=" + watchExecutorService)
                .add("delayEventExecutorService=" + delayEventExecutorService)
                .add("watchKeyDirsMap=" + watchKeyDirsMap)
                .add("watchService=" + watchService)
                .add("eventWatchModifier=" + eventModifier)
                .toString();
    }
}
