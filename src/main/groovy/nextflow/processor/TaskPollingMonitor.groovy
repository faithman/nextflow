/*
 * Copyright (c) 2013-2017, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2017, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.processor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.executor.BatchCleanup
import nextflow.executor.GridTaskHandler
import nextflow.util.Duration
import nextflow.util.Throttle

/**
 * Monitors the queued tasks waiting for their termination
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */

@Slf4j
@CompileStatic
class TaskPollingMonitor implements TaskMonitor {

    /**
     * The current session object
     */
    final Session session

    /**
     * The tasks dispatcher
     */
    final TaskDispatcher dispatcher

    /**
     * The time interval (in milliseconds) elapsed which execute a new poll
     */
    final long pollIntervalMillis

    /**
     * Determines how often the executor status is written in the application log file (default: 5min)
     */
    final Duration dumpInterval

    /**
     * The name of the executor associated to this monitor
     */
    final String name


    /**
     * A lock object used to signal the completion of a task execution
     */
    private Lock taskCompleteLock

    /**
     * Locks the queue of pending tasks buffer
     */
    private Lock pendingQueueLock

    /**
     * Condition to signal a new task has been added in the {@link #pendingQueue}
     */
    private Condition taskAvail

    /**
     * Condition to signal a new processing slot may be available in the {@link #runningQueue}
     */
    private Condition slotAvail

    /**
     * A condition that signal when at least a complete task is available
     */
    private Condition taskComplete

    /**
     * Unbounded buffer that holds all tasks scheduled but not yet submitted for execution
     */
    private Queue<TaskHandler> pendingQueue

    /**
     * Bounded buffer that holds all {@code TaskHandler}s that have been submitted for execution
     */
    private Queue<TaskHandler> runningQueue

    /**
     * The capacity of the {@code pollingQueue} ie. the max number of tasks can be executed at
     * the same time.
     */
    private int capacity

    /**
     * Create the task polling monitor with the provided named parameters object.
     * <p>
     * Valid parameters are:
     * <li>name: The name of the executor for which the polling monitor is created
     * <li>session: The current {@code Session}
     * <li>capacity: The maximum number of this monitoring queue
     * <li>pollInterval: Determines how often a poll occurs to check for a process termination
     * <li>dumpInterval: Determines how often the executor status is written in the application log file
     *
     * @param params
     */
    protected TaskPollingMonitor( Map params ) {
        assert params
        assert params.session instanceof Session
        assert params.name != null
        assert params.pollInterval != null

        this.name = params.name
        this.session = params.session as Session
        this.dispatcher = session.dispatcher
        this.pollIntervalMillis = ( params.pollInterval as Duration ).toMillis()
        this.dumpInterval = (params.dumpInterval as Duration) ?: Duration.of('5min')
        this.capacity = (params.capacity ?: 0) as int

        this.pendingQueue = new ArrayDeque<>()
        this.runningQueue = new ArrayBlockingQueue<>(capacity)
    }

    static TaskPollingMonitor create( Session session, String name, int defQueueSize, Duration defPollInterval ) {
        assert session
        assert name
        final capacity = session.getQueueSize(name, defQueueSize)
        final pollInterval = session.getPollInterval(name, defPollInterval)
        final dumpInterval = session.getMonitorDumpInterval(name)

        log.debug "Creating task monitor for executor '$name' > capacity: $capacity; pollInterval: $pollInterval; dumpInterval: $dumpInterval "
        new TaskPollingMonitor(name: name, session: session, capacity: capacity, pollInterval: pollInterval, dumpInterval: dumpInterval)
    }

    static TaskPollingMonitor create( Session session, String name, Duration defPollInterval ) {
        assert session
        assert name

        final pollInterval = session.getPollInterval(name, defPollInterval)
        final dumpInterval = session.getMonitorDumpInterval(name)

        log.debug "Creating task monitor for executor '$name' > pollInterval: $pollInterval; dumpInterval: $dumpInterval "
        new TaskPollingMonitor(name: name, session: session, pollInterval: pollInterval, dumpInterval: dumpInterval)
    }

    /**
     * @return The current {@link #runningQueue} instance
     */
    protected Queue<TaskHandler> getRunningQueue() { runningQueue }

    /**
     * @return The current {@link TaskDispatcher} instance
     */
    public TaskDispatcher getDispatcher() { dispatcher }

    /**
     * @return the current capacity value by the number of slots specified
     */
    public int getCapacity() { capacity }


    /**
     * Defines the strategy determining if a task can be submitted for execution.
     *
     * @param handler
     *      A {@link TaskHandler} for a task to be submitted
     * @return
     *      {@code true} if the task satisfies the resource requirements and scheduling strategy implemented
     *      by the polling monitor
     */
    protected boolean canSubmit(TaskHandler handler) {
        runningQueue.size() < capacity
    }

    /**
     * Submits the specified task for execution adding it to the queue of scheduled tasks
     *
     * @param handler
     *      A {@link TaskHandler} instance representing the task to be submitted for execution
     */
    protected void submit(TaskHandler handler) {
        // submit the job execution -- throws a ProcessException when submit operation fail
        handler.submit()
        // note: add the 'handler' into the polling queue *after* the submit operation,
        // this guarantees that in the queue are only jobs successfully submitted
        runningQueue.add(handler)
    }

    /**
     * Remove a task from task the scheduling queue
     *
     * @param handler
     *      A {@link TaskHandler} instance
     * @return
     *      {@code true} if the task was removed successfully from the tasks polling queue,
     *      {@code false} otherwise
     */
    protected boolean remove(TaskHandler handler) {
        runningQueue.remove(handler)
    }

    /**
     * Schedule a new task for execution
     *
     * @param handler
     *      A {@link TaskHandler} representing the task to be submitted for execution
     */
    @Override
    void schedule(TaskHandler handler) {
        pendingQueueLock.withLock {
            pendingQueue << handler
            taskAvail.signal()  // signal that a new task is available for execution
            slotAvail.signal()  // signal that a slot in the processing queue
            log.trace "Scheduled task > $handler"
        }
    }

    /**
     * Evicts a task from the processing tasks queue
     *
     * @param handler
     *      A {@link TaskHandler} instance
     * @return
     *      {@code true} when the specified task was successfully removed from polling queue,
     *      {@code false} otherwise
     */
    @Override
    boolean evict(TaskHandler handler) {
        if( !handler ) {
            return false
        }

        pendingQueueLock.withLock {
            if( remove(handler) ) {
                slotAvail.signal()
                return true
            }

            return false
        }
    }

    /**
     * Launch the monitoring thread
     *
     * @return
     *      The monitor object itself
     */
    @Override
    TaskMonitor start() {
        log.debug ">>> barrier register (monitor: ${this.name})"
        session.barrier.register(this)

        this.taskCompleteLock = new ReentrantLock()
        this.taskComplete = taskCompleteLock.newCondition()

        this.pendingQueueLock = new ReentrantLock()
        this.taskAvail = pendingQueueLock.newCondition()
        this.slotAvail = pendingQueueLock.newCondition()

        // remove pending tasks on termination
        session.onShutdown { this.cleanup() }

        // launch the thread polling the queue
        Thread.start('Task monitor') {
            try {
                pollLoop()
            }
            finally {
                log.debug "<<< barrier arrives (monitor: ${this.name})"
                session.barrier.arrive(this)
            }
        }

        // launch daemon that submits tasks for execution
        Thread.startDaemon('Task submitter', this.&submitLoop)

        return this
    }

    /**
     * Wait for new tasks and submit for execution when a slot is available
     */
    protected void submitLoop() {
        while( true ) {
            pendingQueueLock.lock()
            try {
                // wait for at least at to be available
                if( pendingQueue.size()==0 )
                    taskAvail.await()

                // try to submit all pending tasks
                int processed = submitPendingTasks()

                // if no task has been submitted wait for a new slot to be available
                if( !processed ) {
                    Throttle.after(dumpInterval) { dumpSubmitQueue() }
                    slotAvail.await()
                }
            }
            finally {
                pendingQueueLock.unlock()
            }
        }
    }

    /**
     * Implements the polling strategy
     */
    protected void pollLoop() {

        while( true ) {
            long time = System.currentTimeMillis()
            log.trace "Scheduler queue size: ${runningQueue.size()}"

            // check all running tasks for termination
            checkAllTasks()

            if( (session.isTerminated() && runningQueue.size()==0 && pendingQueue.size()==0) || session.isAborted() ) {
                break
            }

            await(time)

            if( session.isAborted() ) {
                break
            }

            // dump this line every two minutes
            Throttle.after(dumpInterval) {
                dumpPendingTasks()
            }
        }
    }

    protected void dumpPendingTasks() {

        try {
            def pending = runningQueue.size()
            if( !pending ) {
                log.debug "No more task to compute -- ${session.dumpNetworkStatus() ?: 'Execution may be stalled'}"
                return
            }

            def msg = []
            msg << "!! executor $name > tasks to be completed: ${runningQueue.size()} -- pending tasks are shown below"
            // dump the first 10 tasks
            def i=0; def itr = runningQueue.iterator()
            while( i++<10 && itr.hasNext() )
                msg << "~> ${itr.next()}"
            if( pending>i )
                msg << ".. remaining tasks omitted."
            log.debug msg.join('\n')
        }
        catch (Throwable e) {
            log.debug "Oops.. expected exception", e
        }
    }

    protected void dumpSubmitQueue() {
        try {
            def pending = pendingQueue.size()
            if( !pending )
                return

            def msg = []
            msg << "%% executor $name > tasks in the submission queue: ${pending} -- tasks to be submitted are shown below"
            // dump the first 10 tasks
            def i=0; def itr = pendingQueue.iterator()
            while( i++<10 && itr.hasNext() )
                msg << "~> ${itr.next()}"
            if( pending>i )
                msg << ".. remaining tasks omitted."
            log.debug msg.join('\n')
        }
        catch (Throwable e) {
            log.debug "Oops.. expected exception", e
        }
    }


    /**
     * Await for one or more tasks to be processed
     *
     * @param time
     *      The wait timeout in millis
     */
    protected void await( long time ) {
        def delta = this.pollIntervalMillis - (System.currentTimeMillis() - time)
        if( delta <= 0 )
            return

        taskCompleteLock.withLock {
            taskComplete.await( delta, TimeUnit.MILLISECONDS )
        }
    }

    /**
     * Signal that a task has been completed
     */
    @Override
    void signal() {
        taskCompleteLock.withLock {
            taskComplete.signal()
        }
    }

    protected void setupBatchCollector() {
        Map<Class,BatchContext> collectors
        for( TaskHandler handler : runningQueue ) {
            // ignore tasks but BatchHandler
            if( handler instanceof BatchHandler ) {
                // create the main collectors map
                if( collectors == null )
                    collectors = new LinkedHashMap<>()
                // create a collector instance for all task of the same class
                BatchContext c = collectors.getOrCreate(handler.getClass()) { new BatchContext() }
                // set the collector in the handler instance
                handler.batch(c)
            }
        }
    }

    /**
     * Check and update the status of queued tasks
     */
    protected void checkAllTasks() {

        // -- find all task handlers that are *batch* aware
        //    this allows to group multiple calls to a remote system together
        setupBatchCollector()

        // -- iterate over the task and check the status
        for( TaskHandler handler : runningQueue ) {
            try {
                checkTaskStatus(handler)
            }
            catch (Throwable error) {
                handleException(handler, error)
            }
        }

    }

    /**
     * Loop over the queue of pending tasks and submit all
     * of which satisfy the {@link #canSubmit(nextflow.processor.TaskHandler)}  condition
     *
     * @return The number of tasks submitted for execution
     */
    protected int submitPendingTasks() {

        int count = 0
        def itr = pendingQueue.iterator()
        while( itr.hasNext() ) {
            final handler = itr.next()
            try {
                if( !canSubmit(handler))
                    continue

                if( !session.aborted && !session.cancelled ) {
                    itr.remove(); count++   // <-- remove the task in all cases
                    submit(handler)
                    session.notifyTaskSubmit(handler)
                }
                else
                    break
            }
            catch ( Exception e ) {
                handleException(handler, e)
                session.notifyTaskComplete(handler)
            }
        }

        return count
    }


    final protected void handleException( TaskHandler handler, Throwable error ) {
        def fault = null
        try {
            fault = handler.task.processor.resumeOrDie(handler?.task, error)
        }
        finally {
            // abort the session if a task task was returned
            if (fault instanceof TaskFault) {
                session.fault(fault)
            }
        }
    }


    /**
     * Check the status of the given task
     *
     * @param handler
     *      The {@link TaskHandler} instance of the task to check
     */
    protected void checkTaskStatus( TaskHandler handler ) {
        assert handler

        // check if it is started
        if( handler.checkIfRunning() ) {
            session.notifyTaskStart(handler)
        }

        // check if it is terminated
        if( handler.checkIfCompleted() ) {
            log.debug "Task completed > $handler"

            // since completed *remove* the task from the processing queue
            evict(handler)

            // finalize the tasks execution
            final fault = handler.task.processor.finalizeTask(handler.task)
            // trigger the count down latch when it is a blocking task
            handler.latch?.countDown()

            // notify task completion
            session.notifyTaskComplete(handler)

            // abort the execution in case of task failure
            if (fault instanceof TaskFault) {
                session.fault(fault)
            }
        }

    }

    /**
     * Kill all pending jobs when current execution session is aborted
     */
    protected void cleanup() {
        if( !runningQueue.size() ) return
        log.warn "Killing pending tasks (${runningQueue.size()})"

        def batch = new BatchCleanup()
        while( runningQueue.size() ) {

            TaskHandler handler = runningQueue.poll()
            try {
                if( handler instanceof GridTaskHandler ) {
                    ((GridTaskHandler)handler).batch = batch
                }
                handler.kill()
            }
            catch( Throwable e ) {
                log.debug "Failed to kill pending tasks: ${handler} -- cause: ${e.message}"
            }

            // notify task completion
            handler.task.aborted = true
            session.notifyTaskComplete(handler)
        }

        try {
            batch.kill()
        }
        catch( Throwable e ) {
            log.debug "Failed to kill pending tasks ${batch} -- cause: ${e.message}"
        }
    }
}

