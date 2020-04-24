package kvstore.consistency;

import java.lang.annotation.Inherited;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;

public abstract class Scheduler implements Runnable {
    protected PriorityBlockingQueue<TaskEntry> tasksQ;

    public Scheduler(Comparator<TaskEntry> sortBy) {
        this.tasksQ = new PriorityBlockingQueue<TaskEntry>(16, sortBy);
    }

    /**
     * Check if allow deliver the task
     */
    abstract public boolean ifAllowDeliver(TaskEntry task);

    /**
     * Add a task to the scheduler
     * @throws InterruptedException
     */
    abstract public TaskEntry addTask(TaskEntry taskEntry);
}