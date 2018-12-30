#Introduction
fswatch is based out Java NIO File Watcher (https://docs.oracle.com/javase/8/docs/api/java/nio/file/WatchService.html) for notification of file system events. fswatch watches one or more 
registered directories and sends notification when there is an event such as create, update, delete to the files of the
directory is triggered. If the event is matched to the registered file name and event type, it invokes the respective 
event handler to do the required processing as part of action to the event.

Please note that multiple file system events can be triggered sometime as part of a single MODIFY transaction on a given
file. These multiple events are mostly due to internal implementation of the editor being used to modify a file. In 
such case a delay invocation of file event handler can be useful in order to avoid redundant callbacks.
 
For example:-
* gedit triggers one create event on the actual file name (e.g. testconfig.properties) as part of one modify action
```
ENTRY_CREATE triggered on file /fswatch/fsw/.goutputstream-R2KURZ..
ENTRY_MODIFY triggered on file /fswatch/fsw/.goutputstream-R2KURZ
ENTRY_MODIFY triggered on file /fswatch/fsw/.goutputstream-R2KURZ
ENTRY_CREATE triggered on file /fswatch/fsw/testFileWatch.properties
```
* vi triggers one create and two modify on the actual file as part of one modify action.
```
ENTRY_CREATE triggered on file /fswatch/fsw/testFileWatch.properties.swp
ENTRY_CREATE triggered on file /fswatch/fsw/testFileWatch.properties.swx
ENTRY_CREATE triggered on file /fswatch/fsw/testFileWatch.properties.swp
ENTRY_MODIFY triggered on file /fswatch/fsw/testFileWatch.properties.swp
ENTRY_MODIFY triggered on file /fswatch/fsw/testFileWatch.properties.swp
ENTRY_CREATE triggered on file /fswatch/fsw/4913
ENTRY_MODIFY triggered on file /fswatch/fsw/4913
ENTRY_CREATE triggered on file /fswatch/fsw/testFileWatch.properties~
ENTRY_CREATE triggered on file /fswatch/fsw/testFileWatch.properties
ENTRY_MODIFY triggered on file /fswatch/fsw/testFileWatch.properties
ENTRY_MODIFY triggered on file /fswatch/fsw/testFileWatch.properties
ENTRY_MODIFY triggered on file /fswatch/fsw/testFileWatch.properties.swp
```
* Editor like sublime triggers only one modify event on the actual file as part of one modify action.
```
ENTRY_MODIFY triggered on file /fswatch/fsw/testFileWatch.properties
```
##Available Event Executors
* DELAY_EXECUTOR
This is the default event executor. The delay executor avoids redundant invocation of event handler if multiple events 
are triggered as part of single transaction on a file. Under this configuration, the file system watcher thread runs 
independently to catch all events on the monitored directories. The delay executor is a scheduled executor service
which invokes event handler only when the specified delay time elapsed from the last event generation time. By 
default, number of delay executor thread is 1 and maximum can be 5. With default configuration maximum number of 
running threads are always 2 (one for File system watch and one for scheduled event executor).

* ASYNC_EXECUTOR
When a subscribed event triggered on a registered file, the file system watcher thread creates a new on-demand thread
to invoke event handler. It is recommended to FileEventHandler#onEvent(FileEvent) method to terminate gracefully at any
case of success/failure to confirm the async executor thread to have a end of life. Maximum number of running threads
is 1 (only file system watch thread) and new on-demand threads will be created to process the events asynchronously.

* FS_WATCHER
The file system watch thread itself invokes FileEventHandler#onEvent(FileEvent). Only one executor service thread 
will be running to monitor events on the directories and also processing the events. During invocation of file event 
handler if any other events are triggered those events will be a miss. Maximum number of running thread is always 1 
(only file system watch thread).