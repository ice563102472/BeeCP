/*
 * Copyright Chris2018998
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.beecp.util;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.LockSupport;

import static java.lang.System.nanoTime;

/**
 * TransferQueue Implementation with class:{@link ConcurrentLinkedQueue}
 *
 * @author Chris.Liao
 */
public final class FastTransferQueue<E> extends AbstractQueue<E> {
    /**
     * Waiter normal status
     */
    private static final State STS_NORMAL = new State();
    /**
     * Waiter in waiting status
     */
    private static final State STS_WAITING = new State();
    /**
     * Waiter wait timeout
     */
    private static final State STS_TIMEOUT = new State();
    /**
     * Waiter thread interrupt
     */
    private static final State STS_INTERRUPTED = new State();

    /**
     * nanoSecond,spin min time value
     */
    private static final long spinForTimeoutThreshold = 1000L;
    /**
     * spin size
     */
    private static final int maxTimedSpins = (Runtime.getRuntime().availableProcessors() < 2) ? 0 : 32;
    /**
     * Thread Interrupted Exception
     */
    private static final InterruptedException RequestInterruptException=new InterruptedException();

    /**
     * CAS updater on waiter's state field
     */
    private static final AtomicReferenceFieldUpdater<Waiter, Object> TransferUpdater = AtomicReferenceFieldUpdater
            .newUpdater(Waiter.class, Object.class, "state");
    /**
     * store element
     */
    private final ConcurrentLinkedQueue<E> elementQueue = new ConcurrentLinkedQueue<E>();
    /**
     * store poll waiter
     */
    private final ConcurrentLinkedQueue<Waiter> waiterQueue = new ConcurrentLinkedQueue<Waiter>();

    /**
     * Retrieves, but does not remove, the head of this queue,
     * or returns {@code null} if this queue is empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     */
    public E peek() {
        return elementQueue.peek();
    }

    /**
     * Returns the number of elements in this queue.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        return elementQueue.size();
    }

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    public Iterator<E> iterator() {
        return elementQueue.iterator();
    }

    /**
     * if exists poll waiter,then transfer it to waiter directly,
     * if not exists,then add it to element queue;
     *
     * @param e element expect to add into queue
     * @return boolean ,true:successful to transfer or add into queue
     */
    public boolean offer(E e) {
        return tryTransfer(e)?true:elementQueue.offer(e);
    }

    /**
     * try to transfers the element to a consumer
     *
     * @param e the element to transfer
     * @return {@code true} transfer successful, or {@code false} transfer failed
     */
    public boolean tryTransfer(E e) {
        Waiter waiter;
        while ((waiter = waiterQueue.poll()) != null) {
            for (Object state = waiter.state; (state == STS_NORMAL || state == STS_WAITING); state = waiter.state) {
                if (TransferUpdater.compareAndSet(waiter, state, e)) {
                    if (state == STS_WAITING) LockSupport.unpark(waiter.thread);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Retrieves and removes the head of this queue,
     * or returns {@code null} if this queue is empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     */
    public E poll() {
        return elementQueue.poll();
    }

    /**
     * if exists element in queue,then retrieves and removes the head of this queue,
     * if not exists,then waiting for a transferred element by method:<tt>offer</tt>
     *
     * @param timeout how long to wait before giving up, in units of
     *        {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the
     *        {@code timeout} parameter
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element is available
     * @throws InterruptedException if interrupted while waiting
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = elementQueue.poll();
        if (e != null) return e;

        boolean isNotTimeout=true;
        boolean isInterrupted=false;

        Waiter waiter = new Waiter();
        Thread thread=waiter.thread;

        waiterQueue.offer(waiter);
        final long deadline = nanoTime()+unit.toNanos(timeout);
        int spinSize =(waiterQueue.peek()==waiter)?maxTimedSpins:0;

        while (true) {
            Object state = waiter.state;
            if (!(state instanceof State)) return (E) state;

            if (isInterrupted){
                if(TransferUpdater.compareAndSet(waiter,state,STS_INTERRUPTED)){
                    waiterQueue.remove(waiter);
                    throw RequestInterruptException;
                }
                continue;
            }

            if (isNotTimeout && (isNotTimeout = (timeout = deadline - nanoTime()) > 0L)) {
                if (spinSize > 0) {
                    --spinSize;
                } else if (timeout>spinForTimeoutThreshold && TransferUpdater.compareAndSet(waiter,state,STS_WAITING)) {
                    LockSupport.parkNanos(this,timeout);
                    if((isInterrupted=thread.isInterrupted())&& TransferUpdater.compareAndSet(waiter,state,STS_INTERRUPTED)){
                        waiterQueue.remove(waiter);
                        throw RequestInterruptException;
                    }
                }
            }else if(TransferUpdater.compareAndSet(waiter,state,STS_TIMEOUT)) {
                waiterQueue.remove(waiter);
                return null;
            }
        }
    }

    private static final class State { }
    private static final class Waiter {
        //poll thread
        Thread thread = Thread.currentThread();
        //transfer value or waiter status
        volatile Object state = STS_NORMAL;
    }
}