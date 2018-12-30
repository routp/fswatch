package com.routp.fswatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link FileSystemWatcherTest}
 */
public class FileSystemWatcherTest {

    private static final String dirToBeWatched = System.getProperty("user.dir") + File.separator + "fsw";
    private static final String testFile = "testFileWatch.properties";
    private static final String testFileAbsPath = dirToBeWatched + File.separator + testFile;
    private static volatile String testFileContents = "";
    private static final Object syncLock = new Object();

    @BeforeAll
    public static void init() throws IOException {
        Files.createDirectory(Paths.get(dirToBeWatched));
        System.out.println("File registered to watch: " + testFileAbsPath);
    }

    @Test
    public void testFileEventsFSWatcher() throws Exception {
        FileSystemWatcher fileSystemWatcher = createFileWatcherWithFSWatcher();
        assertNotNull(fileSystemWatcher);
        this.generateFileEvents(fileSystemWatcher);
    }

    @Test
    public void testFileEventsDelayExecutor() throws Exception {
        FileSystemWatcher fileSystemWatcher = createFileWatcherWithDelayExecutor();
        assertNotNull(fileSystemWatcher);
        this.generateFileEvents(fileSystemWatcher);
    }

    @Test
    public void testFileEventsAsyncExecutor() throws Exception {
        FileSystemWatcher fileSystemWatcher = createFileWatcherWithAsyncExecutor();
        assertNotNull(fileSystemWatcher);
        this.generateFileEvents(fileSystemWatcher);
    }

    private void generateFileEvents(FileSystemWatcher fileSystemWatcher) throws Exception {
        try {
            fileSystemWatcher.startWatching();
            Path testFilePath = Paths.get(testFileAbsPath);
            Files.createFile(testFilePath);
            TimeUnit.MILLISECONDS.sleep(800);
            Files.write(testFilePath, "Key1:Value1\nKey2:Value2\n".getBytes(), StandardOpenOption.APPEND);
            TimeUnit.MILLISECONDS.sleep(800);
            Files.write(testFilePath, "Key3:Value3\n".getBytes(), StandardOpenOption.APPEND);
            Files.write(testFilePath, "Key4:Value4\n".getBytes(), StandardOpenOption.APPEND);
            TimeUnit.MILLISECONDS.sleep(800);
            Files.deleteIfExists(testFilePath);
            TimeUnit.MILLISECONDS.sleep(800);
        } finally {
            if (fileSystemWatcher != null) {
                fileSystemWatcher.stopWatching();
            }
            assertNotNull(fileSystemWatcher);
        }
    }


    @AfterAll
    public static void teardown() throws IOException {
        Path path = Paths.get(dirToBeWatched);
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
        Files.deleteIfExists(path);
    }


    private static FileSystemWatcher createFileWatcherWithFSWatcher() {
        try {
            return FileSystemWatcher.builder()
                    .addFilePath(testFileAbsPath)
                    .name("TestWatchService")
                    .eventExecutor(EventExecutor.FS_WATCHER)
                    .eventModifier(EventModifier.LOW)
                    .eventHandler((fileEvent) -> {
                        if (fileEvent.getFilePath() != null) {
                            if (fileEvent.getFilePath().toAbsolutePath().toString().equals(testFileAbsPath)) {
                                synchronized (syncLock) {
                                    callbackOnEvent(fileEvent);
                                }
                            }
                        }
                    })
                    .build();
        } catch (FileWatchException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static FileSystemWatcher createFileWatcherWithDelayExecutor() {
        try {
            return FileSystemWatcher.builder()
                    .addFilePath(testFileAbsPath)
                    .addEvent(EventType.MODIFY)
                    .addEvent(EventType.CREATE)
                    .addEvent(EventType.DELETE)
                    .name("TestWatchServiceWithDelayExecutor")
                    .delayExecutorPoolSize(1)
                    .eventHandler((fileEvent) -> {
                        if (fileEvent.getFilePath() != null) {
                            if (fileEvent.getFilePath().toAbsolutePath().toString().equals(testFileAbsPath)) {
                                synchronized (syncLock) {
                                    callbackOnEvent(fileEvent);
                                }
                            }
                        }
                    })
                    .build();
        } catch (FileWatchException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static FileSystemWatcher createFileWatcherWithAsyncExecutor() {
        try {
            return FileSystemWatcher.builder()
                    .addFilePath(testFileAbsPath)
                    .addEvent(EventType.ALL)
                    .name("TestWatchServiceWithAsyncExecutor")
                    .eventExecutor(EventExecutor.ASYNC_EXECUTOR)
                    .eventModifier(EventModifier.HIGH)
                    .eventHandler((fileEvent) -> {
                        if (fileEvent.getFilePath() != null) {
                            if (fileEvent.getFilePath().toAbsolutePath().toString().equals(testFileAbsPath)) {
                                synchronized (syncLock) {
                                    callbackOnEvent(fileEvent);
                                }
                            }
                        }
                    })
                    .build();
        } catch (FileWatchException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void callbackOnEvent(FileEvent fileEvent) {
        // try{Thread.sleep(10000);}catch(Exception e){}
        String eventInfo = "Event executor: %s, Event type: %s, File contents: \n%s";
        try {
            if (EventType.CREATE == fileEvent.getEventType() || EventType.MODIFY == fileEvent.getEventType()) {
                assertTrue(Files.exists(Paths.get(testFileAbsPath)));
                testFileContents =
                        new String(Files.readAllBytes(Paths.get(testFileAbsPath)));
                System.out.println(String.format(eventInfo, Thread.currentThread().getName(), fileEvent.getEventType(),
                        testFileContents));
            } else {
                String deleteEventInfo = "Event executor: %s, Event type: %s, File %s deleted.";
                System.out.println(String.format(deleteEventInfo, Thread.currentThread().getName(),
                        fileEvent.getEventType(), testFileAbsPath));
                assertFalse(Files.exists(Paths.get(testFileAbsPath)));
            }
        } catch (IOException e) {
            System.out.println("Failed to update configuration on event " +
                    fileEvent.getEventType() + ", for file " + fileEvent.getFilePath() + ": " + e.getMessage());
        }
    }
}
